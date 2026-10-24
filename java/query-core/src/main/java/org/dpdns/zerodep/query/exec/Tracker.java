package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.write.Update;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link Database#tracking} 的工作单元：登记读到的行和它们的快照，范围正常结束时把变了的行按主键写回。
 * <pre>{@code
 * db.tracking(t -> {
 *     DocBean d = t.one(Doc.class, from(Doc.class).where(x -> x.id().eq("D1")).into(DocBean.class));
 *     d.setTitle("new");
 * });   // UPDATE ... SET TITLE = ?, VERSION = VERSION + 1 WHERE ID = ? AND VERSION = ?，d 的版本变成新值
 * }</pre>
 * 同一张表、同一主键只登记一份：再次读到时返回已登记的对象，库里的新值不覆盖范围内的修改。
 * 行是有无参构造器的可变类（可以用 Lombok），快照是浅拷贝，所以改值要换成新对象，不要原地改数组或集合。
 * 不做级联、不自动判断插入或更新、不懒加载；插入和删除仍然显式写，删掉的行用 {@link #forget} 取消登记。
 */
public final class Tracker {
    /** 一个登记的行：表、行对象和修改前的快照。 */
    private static final class Entry {
        /** 行所在的表。 */
        final Class<? extends Entity> table;

        /** 用户手里的行对象。 */
        final Object row;

        /** 上次写回（或登记）时的浅拷贝。 */
        Object snapshot;

        Entry(Class<? extends Entity> table, Object row, Object snapshot) {
            this.table = table;
            this.row = row;
            this.snapshot = snapshot;
        }
    }

    /** 表和主键值定位一个登记的行。 */
    private record Key(Class<?> table, List<Object> id) { }

    private final Database db;

    /** 按登记顺序，写回时也按这个顺序分组。 */
    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    /** 行对象到它的登记，用于 forget 和避免重复登记同一个对象。 */
    private final Map<Object, Key> keys = new IdentityHashMap<>();

    /** 已经核对过能按主键写回的（表，行类）。 */
    private final Set<List<Class<?>>> checked = new HashSet<>();

    Tracker(Database db) { this.db = db; }

    /** 执行查询并登记读到的那一行；查询必须恰好返回一行。 */
    public <R> R one(Class<? extends Entity> table, Query<R> q) { return track(table, db.one(q)); }

    /** 执行查询并登记读到的行（最多一行）。 */
    public <R> Optional<R> optional(Class<? extends Entity> table, Query<R> q) { return db.optional(q).map(row -> track(table, row)); }

    /** 执行查询并登记读到的每一行，返回的列表里已登记过的主键换成已登记的对象。 */
    public <R> List<R> list(Class<? extends Entity> table, Query<R> q) { return track(table, db.list(q)); }

    /** 登记多行；见 {@link #track(Class, Object)}。 */
    public <R> List<R> track(Class<? extends Entity> table, List<R> rows) {
        List<R> out = new ArrayList<>(rows.size());
        for (R row : rows) out.add(track(table, row));
        return out;
    }

    /**
     * 登记 table 的一行并记下快照，返回要使用的对象：这个主键已经登记过时返回先登记的对象，否则返回 row。
     * 行类的属性必须都是表的列，带表的全部主键属性（表上有版本列时还要带版本属性），且主键不为 null。
     */
    @SuppressWarnings("unchecked")
    public <R> R track(Class<? extends Entity> table, R row) {
        if (row == null) throw new IllegalArgumentException("tracking needs a row, got null");
        if (keys.containsKey(row)) return row;
        // 先按写回时的规则核对一次：行类的属性都是表的列、类型对得上、带主键和版本。
        if (checked.add(List.of(table, row.getClass()))) Sql.update(table).byId(row);
        Key key = new Key(table, idOf(table, row));
        Entry entry = entries.get(key);
        if (entry != null) return (R) entry.row;
        entries.put(key, new Entry(table, row, copy(row)));
        keys.put(row, key);
        return row;
    }

    /** 取消登记一行（例如已经删除了它），范围结束时不再比较和写回。 */
    public void forget(Object row) {
        Key key = keys.remove(row);
        if (key != null) entries.remove(key);
    }

    /**
     * 把变了的行写回：按表和行类分组，每组一条 {@code update(表).byId(行).diffFrom(快照)}，全部在一个事务里执行
     * （已经在事务里、或连接来源自己管理事务时直接执行）。成功后变了的行的版本属性加一并刷新快照；失败时都不动。
     */
    void flush() {
        Map<List<Class<?>>, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries.values()) groups.computeIfAbsent(List.of(e.table, e.row.getClass()), k -> new ArrayList<>()).add(e);
        List<Update<?>> updates = new ArrayList<>();
        for (List<Entry> group : groups.values()) {
            Update<?> u = Sql.update(group.get(0).table).byId(group.stream().map(e -> e.row).toList()).diffFrom(group.stream().map(e -> e.snapshot).toList());
            if (!u.changedRows().isEmpty()) updates.add(u);
        }
        if (updates.isEmpty()) return;
        db.atomically(() -> {
            for (Update<?> u : updates) db.execute(u);
            return null;
        });
        for (Update<?> u : updates) {
            for (Object row : u.changedRows()) {
                Entry e = entries.get(keys.get(row));
                bumpVersion(e.table, row);
                e.snapshot = copy(row);
            }
        }
    }

    /** 行的主键值，按表的 {@code @Id} 顺序；缺少主键属性或值为 null 时报错。 */
    private static List<Object> idOf(Class<? extends Entity> table, Object row) {
        WriteMeta writes = Metadata.entity(table).writes();
        if (writes.ids().isEmpty()) throw new IllegalArgumentException("tracking needs @Id columns on " + table.getSimpleName());
        EntityMeta<?> rowMeta = Metadata.entity(row.getClass());
        List<Object> id = new ArrayList<>();
        for (String name : writes.ids()) {
            Object value = value(rowMeta, name, row);
            if (value == null) throw new IllegalArgumentException(row.getClass().getSimpleName() + "." + name + " is null; tracking needs the key");
            id.add(value);
        }
        if (writes.version() != null) value(rowMeta, writes.version(), row);
        return List.copyOf(id);
    }

    /** 行类里名为 name 的属性的值；没有这个属性时报错。 */
    private static Object value(EntityMeta<?> rowMeta, String name, Object row) {
        if (!rowMeta.has(name)) throw new IllegalArgumentException(row.getClass().getSimpleName() + " needs the property '" + name + "' to be tracked");
        Field f = rowMeta.property(name).field();
        f.trySetAccessible();
        try {
            return f.get(row);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + row.getClass().getSimpleName() + "." + name, e);
        }
    }

    /** 写成功后把行的版本属性加一，与库里的 {@code VERSION = VERSION + 1} 一致；表上没有版本列时什么也不做。 */
    private static void bumpVersion(Class<? extends Entity> table, Object row) {
        String version = Metadata.entity(table).writes().version();
        if (version == null) return;
        PropertyMeta p = Metadata.entity(row.getClass()).property(version);
        Field f = p.field();
        f.trySetAccessible();
        try {
            // 不用条件表达式：Integer 和 Long 混在一个 ?: 里会被提升成 Long。
            Object v = f.get(row);
            if (v instanceof Integer i) f.set(row, i + 1);
            else if (v instanceof Long l) f.set(row, l + 1);
            else if (v instanceof Short s) f.set(row, (short) (s + 1));
            else throw new IllegalStateException(row.getClass().getSimpleName() + "." + version + " must be an Integer, Long or Short version");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write " + row.getClass().getSimpleName() + "." + version, e);
        }
    }

    /** 行的浅拷贝：无参构造器加逐个实例字段复制（含父类）；record 不可变，不能登记。 */
    private static Object copy(Object row) {
        Class<?> type = row.getClass();
        if (type.isRecord()) throw new IllegalArgumentException(type.getSimpleName() + " is a record; tracked rows must be mutable classes");
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object copy = ctor.newInstance();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(copy, f.get(row));
                }
            }
            return copy;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getSimpleName() + " needs a no-argument constructor to be tracked", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot copy " + type.getSimpleName() + " for tracking", e);
        }
    }
}
