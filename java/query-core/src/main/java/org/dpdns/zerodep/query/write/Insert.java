package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.dialect.Upsert;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.KeyList;
import org.dpdns.zerodep.query.expr.Keys;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.ShardRule;
import org.dpdns.zerodep.query.table.Sharding;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * INSERT 语句，由 {@code insertInto(Lips.class)} 开始：
 * <pre>{@code
 * record NewLine(String delivery, String material, BigDecimal quantity) { }
 * db.execute(insertInto(Lips.class).values(new NewLine("D9", "M1", BigDecimal.ONE)));
 * db.execute(insertInto(Lips.class).from(from(Staging.class).select(s -> cols(s.delivery(), s.material()))));
 * }</pre>
 * 行是 record 或普通类，属性按名字对应表的列：行里没有的列不写，null 写成 NULL，{@code @Generated} 的列总是不写；
 * 属性在表里找不到同名列、或类型对不上时报错。多行在一个事务里按批执行（SQL 相同，每批 500 行）。
 * {@code db.insertKeys(insert)} 插入并取回 {@code @Generated} 列生成的值。不可变，每个方法返回新的语句。
 *
 * @param <T> 被插入的表接口
 */
public final class Insert<T extends Entity> implements Write {
    /** 被插入的表接口的元数据。 */
    private final EntityMeta<T> table;

    /** 要插入的行，类型相同；用 {@link #from} 时为空。 */
    private final List<Object> rows;

    /** 要写的列：行类的属性对应的列，去掉 {@code @Generated} 的列；还没有行时为空。 */
    private final List<RowColumns.Column> columns;

    /** INSERT ... SELECT 的查询；用 {@link #values} 时为 null。 */
    private final Query<?> source;

    /** 插入或更新：冲突的键和冲突时更新的列；只是插入时为 null。 */
    private final OnConflict conflict;

    /** 是否套用数据库实例的写入填充（审计列）。 */
    private final boolean fills;

    /**
     * 插入或更新的设置。
     *
     * @param keys    判断冲突的列的属性名
     * @param updates 冲突时更新为插入值的列的属性名；null 表示插入的列里除了键以外的全部，空表示什么也不做
     */
    private record OnConflict(List<String> keys, List<String> updates) { }

    /** 由 {@link #into} 和各修改方法调用。 */
    private Insert(EntityMeta<T> table, List<Object> rows, List<RowColumns.Column> columns, Query<?> source, OnConflict conflict, boolean fills) {
        this.table = table;
        this.rows = rows;
        this.columns = columns;
        this.source = source;
        this.conflict = conflict;
        this.fills = fills;
    }

    /** 插入 table；通常写作 {@code Sql.insertInto(Lips.class)}。 */
    public static <T extends Entity> Insert<T> into(Class<T> table) {
        Ref.table(table);   // 校验它是带 @Table 的表接口
        return new Insert<>(Metadata.entity(table), List.of(), List.of(), null, null, true);
    }

    /**
     * 插入或更新：插入的行与已有的行在 keys 上冲突时改为更新，例如
     * {@code insertInto(Doc.class).values(row).onConflict(d -> d.code()).update()}。HANA 写成 MERGE，PostgreSQL 写成
     * {@code ON CONFLICT (键)}（键要有主键或唯一约束），MySQL 写成 {@code ON DUPLICATE KEY UPDATE}（由表的主键和唯一索引判断冲突）。
     * 键必须是插入的列。
     */
    public Conflict<T> onConflict(Function<T, ? extends Keys> keys) {
        T row = Ref.table(table.type()).param();
        return new Conflict<>(this, names(KeyList.of(keys.apply(row)), "onConflict(...)"));
    }

    /** 同上，按 {@code @Id} 的列判断冲突。 */
    public Conflict<T> onConflict() {
        if (table.writes().ids().isEmpty()) throw new IllegalArgumentException(table.type().getSimpleName() + " has no @Id; write onConflict(t -> keys(...))");
        return new Conflict<>(this, table.writes().ids());
    }

    /**
     * {@link #onConflict} 之后选择冲突时做什么。
     *
     * @param <T> 被插入的表接口
     */
    public static final class Conflict<T extends Entity> {
        /** 要改成插入或更新的语句。 */
        private final Insert<T> insert;

        /** 判断冲突的列的属性名。 */
        private final List<String> keys;

        /** 由 {@link Insert#onConflict} 创建。 */
        private Conflict(Insert<T> insert, List<String> keys) {
            this.insert = insert;
            this.keys = List.copyOf(keys);
        }

        /** 冲突时把插入的列（键以外的全部）更新为插入的值。 */
        public Insert<T> update() { return insert.withConflict(new OnConflict(keys, null)); }

        /** 冲突时只更新这些列为插入的值，例如 {@code update(d -> keys(d.title(), d.status()))}。 */
        public Insert<T> update(Function<T, ? extends Keys> columns) {
            T row = Ref.table(insert.table.type()).param();
            List<String> names = insert.names(KeyList.of(columns.apply(row)), "update(...)");
            for (String n : names) {
                if (insert.table.writes().readOnly().contains(n)) throw new IllegalArgumentException("onConflict(...).update(...) cannot write @ReadOnly " + n);
            }
            return insert.withConflict(new OnConflict(keys, names));
        }

        /** 冲突时什么也不做，保留已有的行。 */
        public Insert<T> doNothing() { return insert.withConflict(new OnConflict(keys, List.of())); }
    }

    /** 带上插入或更新设置的副本。 */
    private Insert<T> withConflict(OnConflict c) {
        if (source != null) throw new IllegalStateException("onConflict(...) needs values(...), not from(query)");
        return new Insert<>(table, rows, columns, source, c, fills);
    }

    /** 表达式都是这张表的列，返回它们的属性名；what 用于报错。 */
    private List<String> names(List<Expr<?>> exprs, String what) {
        List<String> out = new ArrayList<>();
        for (Expr<?> e : exprs) {
            if (!(e instanceof Col<?> c) || c.ref().entity() != table.type()) {
                throw new IllegalArgumentException(what + " takes columns of " + table.type().getSimpleName() + ", e.g. t -> keys(t.code())");
            }
            out.add(c.property().name());
        }
        return out;
    }

    /** 追加要插入的行。 */
    public Insert<T> values(Object... rows) { return values(Arrays.asList(rows)); }

    /** 追加要插入的行；所有行必须是同一个类。 */
    public Insert<T> values(Collection<?> rows) {
        if (source != null) throw new IllegalStateException("insertInto(...) already takes its rows from(query)");
        List<Object> all = new ArrayList<>(this.rows);
        all.addAll(rows);
        if (all.isEmpty()) return this;
        Class<?> type = RowColumns.sameClass(all, "insertInto(...).values(...)");
        List<RowColumns.Column> written = columns.isEmpty()
            ? RowColumns.of(table, type).stream().filter(c -> table.writes().inserts(c.name())).toList() : columns;
        if (written.isEmpty()) throw new IllegalArgumentException(type.getSimpleName() + " has no property to insert into " + table.type().getSimpleName());
        return new Insert<>(table, List.copyOf(all), written, null, conflict, fills);
    }

    /** INSERT ... SELECT：查询的每一列按列名对应表的同名列，例如 {@code cols(s.delivery(), s.material())}。 */
    public Insert<T> from(Query<?> query) {
        if (!rows.isEmpty()) throw new IllegalStateException("insertInto(...) already has values(...)");
        if (conflict != null) throw new IllegalStateException("onConflict(...) needs values(...), not from(query)");
        for (String label : query.labels()) {
            RowColumns.column(table, label, "the query's column");
            if (!table.writes().inserts(label)) throw new IllegalArgumentException("from(query) writes " + label + ", which is @ReadOnly or @Generated");
        }
        return new Insert<>(table, List.of(), List.of(), query, null, fills);
    }

    /** 不套用数据库实例的写入填充（审计列），例如迁移数据时保留行里原来的创建人和时间。 */
    public Insert<T> withoutFills() { return new Insert<>(table, rows, columns, source, conflict, false); }

    /** 插入的表。 */
    public Class<T> table() { return table.type(); }

    /** 插入不检查影响的行数。 */
    @Override public Long expected() { return null; }

    /** {@code @Generated} 的列，{@code db.insertKeys} 取回它的值；表上没有、或用的是 {@link #from} 时报错。 */
    public PropertyMeta generated() {
        if (table.writes().generated() == null) {
            throw new IllegalStateException(table.type().getSimpleName() + " has no @Generated column; use db.execute(insert) instead");
        }
        if (source != null) throw new IllegalStateException("insertKeys(...) needs values(...), not from(query)");
        return table.property(table.writes().generated());
    }

    /** 每行一条 {@code INSERT INTO 表 (列, ...) VALUES (?, ...)}；INSERT ... SELECT 是一条。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        if (source != null) {
            SqlWriter w = writers.get();
            if (Sharding.of(w).rule(table.type()) != null) {
                throw new UnsupportedOperationException("insertInto(" + table.type().getSimpleName() + ").from(query) cannot pick a shard; insert rows with values(...)");
            }
            head(w, null, source.labels().stream().map(l -> RowColumns.column(table, l, "the query's column")).toList());
            w.sql("\n");
            QueryRenderer.named(w, source);
            return List.of(w.statement());
        }
        List<Rendered> out = new ArrayList<>();
        for (Object row : rows) {
            SqlWriter w = writers.get();
            Values v = values(w, row);
            String shard = shard(w, v);
            if (conflict != null) {
                w.dialect().upsert(w, upsert(w, v, shard));
                out.add(w.statement());
                continue;
            }
            head(w, shard, v.columns());
            w.sql(" VALUES (").join(v.values(), ", ").sql(")");
            out.add(w.statement());
        }
        return out;
    }

    /** 一行要写的列和值，一一对应。 */
    private record Values(List<PropertyMeta> columns, List<Renderable> values) { }

    /** 分表时这一行写到的物理表（按要写的分表键的值）；不分表时为 null。 */
    private String shard(SqlWriter w, Values v) {
        Sharding sharding = Sharding.of(w);
        ShardRule rule = sharding.rule(table.type());
        if (rule == null) return null;
        for (int i = 0; i < v.columns().size(); i++) {
            if (v.columns().get(i).name().equals(rule.key()) && v.values().get(i) instanceof Nodes.Param<?> p) return sharding.routeValue(table.type(), p.value());
        }
        throw new IllegalArgumentException("A row inserted into " + table.type().getSimpleName() + " needs its shard key " + rule.key() + " as a value");
    }

    /**
     * 一行要写的列和值：行里的属性，加上数据库实例的插入时填充（覆盖同名的属性），以及行里没有逻辑删除列时它的"未删除"值
     * （记录删除时间的列未删除就是 NULL，不用写）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Values values(SqlWriter w, Object row) {
        List<PropertyMeta> cols = new ArrayList<>();
        List<Renderable> vals = new ArrayList<>();
        for (RowColumns.Column c : columns) {
            cols.add(c.column());
            vals.add(value(c, row));
        }
        Ref<T> ref = Ref.table(table.type());
        for (Assignment a : (fills ? Fills.of(w) : Fills.NONE).onInsert(ref)) put(cols, vals, a.column().property(), a.value());
        for (Fills.Generated g : Fills.of(w).generators(ref)) {
            RowColumns.Column written = columns.stream().filter(c -> c.column().name().equals(g.column().name())).findFirst().orElse(null);
            if (written == null || written.value(row) == null) put(cols, vals, g.column(), new Nodes.Param<>((SqlType) g.column().type(), g.value().get()));
        }
        WriteMeta.LogicDeleteMeta logic = table.writes().logicDelete();
        if (logic != null && !logic.timestamp() && cols.stream().noneMatch(p -> p.name().equals(logic.property()))) {
            PropertyMeta column = table.property(logic.property());
            put(cols, vals, column, new Nodes.Param<>((SqlType) column.type(), logic.active()));
        }
        return new Values(cols, vals);
    }

    /** 把 column 的值设为 value：已有这一列时替换，没有时追加。 */
    private static void put(List<PropertyMeta> cols, List<Renderable> vals, PropertyMeta column, Renderable value) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).name().equals(column.name())) {
                vals.set(i, value);
                return;
            }
        }
        cols.add(column);
        vals.add(value);
    }

    /** 一行的插入或更新交给方言的内容：列名按命名策略，键必须是插入的列；更新时的填充作为另外更新的列。 */
    private Upsert upsert(SqlWriter w, Values v, String shard) {
        NamingStrategy naming = naming(w);
        List<String> names = v.columns().stream().map(PropertyMeta::name).toList();
        for (String k : conflict.keys()) {
            if (!names.contains(k)) throw new IllegalArgumentException("onConflict(...) key '" + k + "' must be one of the inserted columns " + names);
        }
        Map<String, Renderable> assignments = new LinkedHashMap<>();
        for (Assignment a : (fills ? Fills.of(w) : Fills.NONE).onUpdate(Ref.table(table.type()))) assignments.put(a.column().property().columnName(naming), a.value());
        List<String> updates = (conflict.updates() != null ? conflict.updates()
                : names.stream().filter(n -> !conflict.keys().contains(n) && !table.writes().writeOnce().contains(n)).toList())
            .stream().map(u -> table.property(u).columnName(naming)).filter(c -> !assignments.containsKey(c)).toList();
        return new Upsert(x -> tableName(x, shard), v.columns().stream().map(p -> p.columnName(naming)).toList(), v.values(),
            conflict.keys().stream().map(k -> table.property(k).columnName(naming)).toList(), updates, assignments);
    }

    /** 写出 {@code schema.表名}；shard 不为 null 时写这张物理表。 */
    private void tableName(SqlWriter w, String shard) {
        w.table(table.schema(), shard == null ? table.table() : shard);
    }

    /** 写入器上的命名策略。 */
    private static NamingStrategy naming(SqlWriter w) {
        Object naming = w.attribute(NamingStrategy.class);
        return naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming;
    }

    /** 写出 {@code INSERT INTO 表 (列, ...)}；shard 不为 null 时写这张物理表。 */
    private void head(SqlWriter w, String shard, List<PropertyMeta> written) {
        w.sql("INSERT INTO ");
        tableName(w, shard);
        w.sql(" (");
        NamingStrategy naming = naming(w);
        for (int i = 0; i < written.size(); i++) {
            if (i > 0) w.sql(", ");
            w.identifier(written.get(i).columnName(naming));
        }
        w.sql(")");
    }

    /** 行里一个属性的值，按表的列的类型绑定（经过列的转换器）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Nodes.Param<?> value(RowColumns.Column c, Object row) {
        return new Nodes.Param<>((SqlType) c.column().type(), c.value(row));
    }
}
