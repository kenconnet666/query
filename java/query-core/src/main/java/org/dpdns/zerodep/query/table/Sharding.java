package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.table.column.Col;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库实例上注册的同库分表，以及按条件选表：分表键与常量的 {@code =}、{@code IN}、{@code BETWEEN} 和范围比较，
 * AND 取交、OR 取并；推不出来时报错，除非语句写了 {@code allShards()} 或 {@code onShards(...)}。
 * 查询把逻辑表写成所选物理表 {@code UNION ALL} 的派生表（只选中一张时直接写那张表），写语句每张物理表一条。
 */
public final class Sharding {
    /** 没有注册分表。 */
    public static final Sharding NONE = new Sharding(Map.of());

    /** {@link SqlWriter#state} 的键：外层 SELECT 显式的分表选择，子查询没有自己的选择时沿用。 */
    private static final Object INHERITED = new Object();

    private final Map<Class<?>, ShardRule> rules;

    /** 表接口到它的规则；分表键必须是表的属性。 */
    public Sharding(Map<Class<?>, ShardRule> rules) {
        for (Map.Entry<Class<?>, ShardRule> e : rules.entrySet()) {
            EntityMeta<?> meta = Metadata.entity(e.getKey());
            if (!meta.isTable()) throw new IllegalArgumentException(e.getKey().getSimpleName() + " has no @Table, so it cannot be sharded");
            if (!meta.has(e.getValue().key())) {
                throw new IllegalArgumentException("Shard key '" + e.getValue().key() + "' is not a column of " + e.getKey().getSimpleName());
            }
        }
        this.rules = Map.copyOf(rules);
    }

    /** 写入器所属数据库实例的分表设置；没有时为 {@link #NONE}。 */
    public static Sharding of(SqlWriter w) {
        Object s = w.attribute(Sharding.class);
        return s == null ? NONE : (Sharding) s;
    }

    /** table 的规则；不分表时为 null。 */
    public ShardRule rule(Class<?> table) { return rules.get(table); }

    /** table 的全部物理表；不分表时为空。 */
    public List<String> tables(Class<?> table) {
        ShardRule rule = rules.get(table);
        return rule == null ? List.of() : rule.tables(Metadata.entity(table).table());
    }

    /**
     * 一段 SELECT 开始时调用：记下这段 SELECT 显式的分表选择（子查询沿用），返回之前的选择，结束时交给 {@link #leave} 恢复。
     */
    public static Object enter(SqlWriter w, Filtering filtering) {
        Object previous = w.state(INHERITED);
        if (filtering.choosesShards()) w.state(INHERITED, filtering);
        return previous;
    }

    /** 恢复 {@link #enter} 之前的分表选择。 */
    public static void leave(SqlWriter w, Object previous) { w.state(INHERITED, previous); }

    /**
     * 为一段 SELECT 里的来源选表并登记到写入器上，写 FROM 时 {@link Ref#renderSource} 按它写出物理表。
     * 一段 SELECT 里最多一张分表；where 是这段 SELECT 的 WHERE 条件。
     */
    public void routeSelect(SqlWriter w, List<Ref<?>> refs, List<Cond> where, Filtering filtering) {
        if (rules.isEmpty()) return;
        Ref<?> sharded = null;
        for (Ref<?> r : refs) {
            if (!rules.containsKey(r.entity())) continue;
            if (sharded != null) {
                throw new IllegalStateException("Only one sharded table may take part in a SELECT; " + sharded.entity().getSimpleName() + " and "
                    + r.entity().getSimpleName() + " both are. Read one of them in a CTE or a separate query");
            }
            sharded = r;
        }
        if (sharded == null) return;
        Filtering choice = filtering.choosesShards() ? filtering : (Filtering) w.state(INHERITED);
        w.state(sharded, route(sharded, where, choice == null ? filtering : choice));
    }

    /**
     * ref 这次出现要读写的物理表，按规则里的顺序：语句显式选了分表时用它，否则按 where 选表，推不出来时报错。
     * ref 的表不分表时返回 null。
     */
    public List<String> route(Ref<?> ref, List<Cond> where, Filtering filtering) {
        ShardRule rule = rules.get(ref.entity());
        if (rule == null) return null;
        String table = Metadata.entity(ref.entity()).table();
        List<String> all = rule.tables(table);
        if (!filtering.onShards().isEmpty()) {
            for (String t : filtering.onShards()) {
                if (!all.contains(t)) throw new IllegalArgumentException("onShards(...) names " + t + ", which is not a shard of " + table + ": " + all);
            }
            return all.stream().filter(filtering.onShards()::contains).toList();
        }
        if (filtering.everyShard()) return all;
        Set<String> routed = analyze(Nodes.all(where), ref, rule, table);
        if (routed == null) {
            throw new IllegalStateException("Cannot tell which shards of " + table + " the statement needs: filter on " + ref.entity().getSimpleName()
                + "." + rule.key() + " with =, IN, BETWEEN or a range (combined with AND or OR), or add allShards() or onShards(...)");
        }
        return all.stream().filter(routed::contains).toList();
    }

    /** 一行写到的物理表：行里分表键的值所在的表。 */
    public String routeValue(Class<?> table, Object value) {
        ShardRule rule = rules.get(table);
        if (value == null) throw new IllegalArgumentException("A row of " + table.getSimpleName() + " has no " + rule.key() + "; it is the shard key");
        return rule.route(Metadata.entity(table).table(), value);
    }

    /**
     * 条件能选出的物理表；null 表示推不出来（需要全部表）。AND 取交，推不出来的项不参与；OR 取并，有一项推不出来就推不出来。
     */
    private static Set<String> analyze(Cond c, Ref<?> ref, ShardRule rule, String table) {
        if (c instanceof Nodes.Conjunction and) {
            Set<String> acc = null;
            for (Cond part : and.parts) {
                Set<String> s = analyze(part, ref, rule, table);
                if (s == null) continue;
                if (acc == null) acc = new LinkedHashSet<>(s);
                else acc.retainAll(s);
            }
            return acc;
        }
        if (c instanceof Nodes.Disjunction or) {
            Set<String> acc = new LinkedHashSet<>();
            for (Cond part : or.parts) {
                Set<String> s = analyze(part, ref, rule, table);
                if (s == null) return null;
                acc.addAll(s);
            }
            return acc;
        }
        if (c instanceof Nodes.Comparison cmp) {
            if (isKey(cmp.left, ref, rule) && constant(cmp.right)) return compare(cmp.operator, value(cmp.right), rule, table);
            if (isKey(cmp.right, ref, rule) && constant(cmp.left)) return compare(flip(cmp.operator), value(cmp.left), rule, table);
            return null;
        }
        if (c instanceof Nodes.InList in && !in.negate && isKey(in.e, ref, rule)) {
            Set<String> acc = new LinkedHashSet<>();
            for (Expr<?> v : in.values) {
                if (!constant(v) || value(v) == null) return null;
                acc.add(rule.route(table, value(v)));
            }
            return acc;
        }
        if (c instanceof Nodes.Between b && isKey(b.e, ref, rule) && constant(b.low) && constant(b.high) && value(b.low) != null && value(b.high) != null) {
            return new LinkedHashSet<>(rule.range(table, value(b.low), true, value(b.high), true));
        }
        return null;
    }

    /** {@code key op value} 能选出的表。 */
    private static Set<String> compare(String operator, Object value, ShardRule rule, String table) {
        if (value == null) return null;
        return switch (operator) {
            case "=" -> Set.of(rule.route(table, value));
            case ">" -> new LinkedHashSet<>(rule.range(table, value, false, null, false));
            case ">=" -> new LinkedHashSet<>(rule.range(table, value, true, null, false));
            case "<" -> new LinkedHashSet<>(rule.range(table, null, false, value, false));
            case "<=" -> new LinkedHashSet<>(rule.range(table, null, false, value, true));
            default -> null;
        };
    }

    /** 两侧对调后的运算符：{@code 5 < k} 即 {@code k > 5}。 */
    private static String flip(String operator) {
        return switch (operator) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> operator;
        };
    }

    /** e 是不是 ref 这次出现的分表键列。 */
    private static boolean isKey(Expr<?> e, Ref<?> ref, ShardRule rule) {
        return Nodes.unwrap(e) instanceof Col<?> col && col.ref() == ref && col.property().name().equals(rule.key());
    }

    /** e 是不是参数或字面量。 */
    private static boolean constant(Expr<?> e) { return Nodes.unwrap(e) instanceof Nodes.Param<?> || Nodes.unwrap(e) instanceof Nodes.Literal<?>; }

    /** 参数或字面量的值。 */
    private static Object value(Expr<?> e) { return Nodes.unwrap(e) instanceof Nodes.Param<?> p ? p.value() : ((Nodes.Literal<?>) Nodes.unwrap(e)).value(); }

    /**
     * 写出 ref 的物理表：只有一张时直接写它，多张时写成 {@code (SELECT 列 FROM 表1 UNION ALL SELECT 列 FROM 表2 ...)}，
     * 一张都没有时写成取不到行的 {@code (SELECT 列 FROM 第一张表 WHERE 1 = 0)}。
     */
    static void renderSource(SqlWriter w, Ref<?> ref, List<String> tables) {
        EntityMeta<?> meta = Metadata.entity(ref.entity());
        if (tables.size() == 1) {
            w.table(meta.schema(), tables.get(0));
            return;
        }
        Object naming = w.attribute(NamingStrategy.class);
        NamingStrategy n = naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming;
        List<String> columns = new ArrayList<>();
        for (PropertyMeta p : meta.properties()) columns.add(w.dialect().quote(p.columnName(n)));
        String list = String.join(", ", columns);
        List<String> from = tables.isEmpty() ? List.of(Sharding.of(w).tables(ref.entity()).get(0)) : tables;
        w.sql("(");
        for (int i = 0; i < from.size(); i++) {
            if (i > 0) w.sql(" UNION ALL ");
            w.sql("SELECT ").sql(list).sql(" FROM ").table(meta.schema(), from.get(i));
        }
        if (tables.isEmpty()) w.sql(" WHERE 1 = 0");
        w.sql(")");
    }
}
