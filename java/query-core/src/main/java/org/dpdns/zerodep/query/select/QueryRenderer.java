package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.Order;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** 写出 SELECT 语句。数据库实例级的过滤器通过写入器的 attributes 传给它。 */
public final class QueryRenderer {
    /** 工具类，不能实例化。 */
    private QueryRenderer() { }

    /** 渲染成一条完整的语句：列带上结果列名，ORDER BY 保留。 */
    public static Rendered statement(SqlWriter w, Query<?> q) {
        query(w, q, true, true);
        return w.statement();
    }

    /** 对查询套一层 SELECT COUNT(*)；除非有 LIMIT 需要，否则去掉 ORDER BY。 */
    public static Rendered count(SqlWriter w, Query<?> q) {
        w.sql("SELECT COUNT(*) FROM (");
        query(w, q, false, true);
        w.sql(") ").alias(w.aliasFor(new Object(), "counted"));
        return w.statement();
    }

    /** {@code SELECT CASE WHEN EXISTS (查询) THEN 1 ELSE 0 END}：总是返回一行，查询找到行时为 1。 */
    public static Rendered exists(SqlWriter w, Query<?> q) {
        w.sql("SELECT CASE WHEN EXISTS (");
        subquery(w, q);
        w.sql(") THEN 1 ELSE 0 END");
        w.dialect().fromNothing(w);
        return w.statement();
    }

    /** 渲染列名有意义的查询：语句本身、CTE 的查询体或派生表。 */
    public static void named(SqlWriter w, Query<?> q) { query(w, q, true, true); }

    /** 渲染作为值使用的查询：EXISTS、IN 或标量子查询；列不需要名字。 */
    public static void subquery(SqlWriter w, Query<?> q) { query(w, q, true, false); }

    /**
     * 渲染一个查询：把各段（UNION 等的分支）按顺序写出，段之间写合并运算符和必要的括号，
     * 多段时最后再写整体的 LIMIT 和 OFFSET。
     *
     * @param ordered false 时去掉没有 LIMIT 或 OFFSET 的段里的 ORDER BY
     * @param labeled true 时第一段的每一列带上结果列名
     */
    private static void query(SqlWriter w, Query<?> q, boolean ordered, boolean labeled) {
        List<Query.Arm> arms = q.arms();
        Query.Combination c = q.combination();
        for (int i = 0; i < arms.size(); i++) {
            if (i > 0) w.sql("\n").sql(w.dialect().setOperator(c.operators().get(i - 1))).sql("\n");
            w.sql("(".repeat(c.opens(i)));
            arm(w, arms.get(i), q.labels(), i == 0 && labeled, ordered);
            w.sql(")".repeat(c.closes(i)));
        }
        if (arms.size() > 1) w.dialect().limit(w, q.limitValue(), q.offsetValue());
    }

    /**
     * 渲染一段 SELECT：提示、DISTINCT、列、FROM 与各 JOIN、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT 以及方言子句。
     * labeled 为 false 时列不带名字；有 QUALIFY 时交给 {@link #qualified}。
     */
    private static void arm(SqlWriter w, Query.Arm arm, List<String> labels, boolean labeled, boolean ordered) {
        SelectSpec s = arm.spec();
        if (!Nodes.all(s.qualify).isEmpty()) {
            qualified(w, arm, labels, labeled, ordered);
            return;
        }
        // 先按连接顺序分配别名，这样即使 SELECT 先提到了被连接的表，FROM 的表也能拿到不带序号的名字。
        for (SelectSpec.Join j : s.joins) j.ref().alias(w);
        for (Clause c : s.clauses) {
            if (c.dialect() != null && !c.dialect().isInstance(w.dialect())) {
                throw new UnsupportedOperationException("A " + c.dialect().getSimpleName() + " clause cannot run on " + w.dialect().name());
            }
        }
        w.sql("SELECT ");
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.HINT) w.render(c).sql(" ");
        }
        boolean replacedDistinct = false;
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.DISTINCT) {
                w.render(c).sql(" ");
                replacedDistinct = true;
            }
        }
        if (s.distinct && !replacedDistinct) w.sql("DISTINCT ");
        List<Expr<?>> groupBy = s.groupBy;
        List<Order> orderBy = s.orderBy;
        Set<String> excluded = excludedKeys(w, arm);
        if (!excluded.isEmpty()) {
            SqlWriter probe = w.probe();
            groupBy = groupBy.stream().filter(g -> !excluded.contains(text(probe, g))).toList();
            orderBy = orderBy.stream().filter(o -> !excluded.contains(text(probe, o.expr()))).toList();
        }
        // SELECT 列表里的常量写成字面量：HANA 无法给这里的参数定类型（CTE 里的 COALESCE(x, ?)
        // 对外层的 SUM 来说不是数值）。GROUP BY、DISTINCT 及其 ORDER BY 按文本匹配表达式，
        // 所以它们里面的常量也必须是字面量。
        boolean grouped = !groupBy.isEmpty() || s.distinct || replacedDistinct;
        List<GroupJoin> groupJoins = groupJoins(w, arm, groupBy, orderBy);
        UnaryOperator<Renderable> previous = w.substitution(null);
        if (!groupJoins.isEmpty()) w.substitution(node -> {
            for (GroupJoin g : groupJoins) {
                String column = g.columns().get(node);
                if (column != null) return x -> ((Many.Grouped) node).renderGrouped(x, y -> y.alias(g.alias()).sql(".").identifier(column));
            }
            return previous == null ? node : previous.apply(node);
        });
        else w.substitution(previous);
        boolean outer = w.inlining(true);
        List<Expr<?>> items = arm.items();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(items.get(i));
            if (labeled) w.sql(" AS ").identifier(labels.get(i));
        }
        w.inlining(false);
        if (s.joins.isEmpty()) w.dialect().fromNothing(w);
        SourceFilters configured = (SourceFilters) w.attribute(SourceFilters.class);
        SourceFilters filters = (configured == null ? SourceFilters.NONE : configured).select(s.filtering);
        // FROM、CROSS JOIN 的表的过滤条件先记下：后面有 RIGHT JOIN 时它们变成可空的一侧，
        // 条件要放进那个 RIGHT JOIN 的 ON，否则 WHERE 会把补出来的行过滤掉。
        List<Cond> pending = new ArrayList<>();
        Set<Ref.Relation> implicit = usedRelations(w, arm, groupBy, orderBy);
        for (SelectSpec.Join j : s.joins) {
            List<Cond> extra = filters.conditionsFor(j.ref());
            switch (j.kind()) {
                case FROM -> {
                    w.sql("\nFROM ");
                    j.ref().renderSource(w);
                    pending.addAll(extra);
                }
                case CROSS -> {
                    w.sql("\nCROSS JOIN ");
                    j.ref().renderSource(w);
                    pending.addAll(extra);
                }
                case FULL -> {
                    if (!w.dialect().supportsFullJoin()) throw new UnsupportedOperationException(w.dialect().name() + " has no FULL JOIN");
                    // 两侧都会补空行：过滤条件放在 ON 里会让被过滤掉的行作为不匹配的行留下，放在 WHERE 里会去掉补出来的行，
                    // 所以有全局过滤条件的表不能参与 FULL JOIN。
                    if (!pending.isEmpty() || !extra.isEmpty()) {
                        throw new IllegalStateException("FULL JOIN cannot keep the global filters of its tables; use unfiltered() and filter each side in a CTE or derived table");
                    }
                    w.sql("\nFULL JOIN ");
                    j.ref().renderSource(w);
                    w.sql(" ON ").render(j.on());
                }
                case RIGHT -> {
                    w.sql("\nRIGHT JOIN ");
                    j.ref().renderSource(w);
                    List<Cond> on = new ArrayList<>();
                    on.add(j.on());
                    on.addAll(pending);
                    w.sql(" ON ").render(Nodes.all(on));
                    pending = new ArrayList<>(extra);
                }
                default -> {
                    w.sql("\n").sql(j.kind().sql).sql(" ");
                    j.ref().renderSource(w);
                    // 被连接的表的过滤条件放进 ON，LEFT JOIN 才仍然是 LEFT JOIN。
                    List<Cond> on = new ArrayList<>();
                    on.add(j.on());
                    on.addAll(extra);
                    w.sql(" ON ").render(Nodes.all(on));
                }
            }
            ImplicitJoins.emit(w, j.ref(), implicit, filters);
            for (GroupJoin g : groupJoins) {
                if (g.owner() == j.ref()) emitGroupJoin(w, g);
            }
        }
        List<Cond> where = new ArrayList<>(pending);
        where.addAll(s.where);
        Cond whereAll = Nodes.all(where);
        if (!whereAll.isEmpty()) w.sql("\nWHERE ").render(whereAll);
        w.inlining(grouped);
        if (!groupBy.isEmpty()) {
            w.sql("\nGROUP BY ");
            w.dialect().groupBy(w, groupBy, s.rollup);
        }
        Cond having = Nodes.all(s.having);
        if (!having.isEmpty()) w.sql("\nHAVING ").render(having);
        boolean limited = s.limit != null || s.offset != null;
        if (!orderBy.isEmpty() && (ordered || limited)) w.sql("\nORDER BY ").join(orderBy, ", ");
        w.inlining(outer);
        w.dialect().limit(w, s.limit, s.offset);
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.END) w.sql("\n").render(c);
        }
        w.substitution(previous);
    }

    /** groupJoin 的一个关系：所属的表出现、派生表的别名、派生表，以及每个集合运算节点对应的列名。 */
    private record GroupJoin(Ref<?> owner, String alias, Many.Grouping grouping, Map<Renderable, String> columns) { }

    /**
     * 这一段 groupJoin 的关系：用探查写入器找出各子句里用到的、属于这些关系的集合运算（count、sum、any 等），
     * 每个关系生成一个按连接键分组的派生表，每个运算一列。没有用到运算的关系不连接。
     */
    private static List<GroupJoin> groupJoins(SqlWriter w, Query.Arm arm, List<Expr<?>> groupBy, List<Order> orderBy) {
        SelectSpec s = arm.spec();
        if (s.groupJoins.isEmpty()) return List.of();
        Map<Many<?>, List<Many.Grouped>> found = new LinkedHashMap<>();
        SqlWriter probe = w.probe();
        probe.substitution(node -> {
            if (!(node instanceof Many.Grouped op)) return node;
            for (Many<?> relation : s.groupJoins) {
                if (!relation.sameRelation(op.relation())) continue;
                List<Many.Grouped> ops = found.computeIfAbsent(relation, k -> new ArrayList<>());
                if (ops.stream().noneMatch(o -> o == op)) ops.add(op);
                return x -> { };
            }
            return node;
        });
        for (Expr<?> e : arm.items()) probe.render(e);
        for (SelectSpec.Join j : s.joins) if (!j.on().isEmpty()) probe.render(j.on());
        for (Cond c : s.where) if (!c.isEmpty()) probe.render(c);
        for (Expr<?> g : groupBy) probe.render(g);
        for (Cond c : s.having) if (!c.isEmpty()) probe.render(c);
        for (Order o : orderBy) probe.render(o);
        List<GroupJoin> result = new ArrayList<>();
        for (Map.Entry<Many<?>, List<Many.Grouped>> e : found.entrySet()) {
            Many<?> relation = e.getKey();
            if (s.joins.stream().noneMatch(j -> j.ref() == relation.owner())) {
                throw new IllegalArgumentException("groupJoin(...) needs a relation of a table in from(...) or a join");
            }
            Map<Renderable, String> columns = new IdentityHashMap<>();
            for (int i = 0; i < e.getValue().size(); i++) columns.put(e.getValue().get(i), "c" + (i + 1));
            String alias = w.aliasFor(new Object(), relation.owner().entity().getSimpleName() + "_g");
            result.add(new GroupJoin(relation.owner(), alias, relation.grouping(e.getValue()), columns));
        }
        return result;
    }

    /** 写出 {@code LEFT JOIN (派生表) 别名 ON 别名.k1 = 所属列1 AND ...}。 */
    private static void emitGroupJoin(SqlWriter w, GroupJoin g) {
        w.sql("\nLEFT JOIN (\n");
        named(w, g.grouping().query());
        w.sql("\n) ").alias(g.alias()).sql(" ON ");
        List<Expr<?>> outer = g.grouping().outer();
        for (int i = 0; i < outer.size(); i++) {
            if (i > 0) w.sql(" AND ");
            w.alias(g.alias()).sql(".").identifier("k" + (i + 1)).sql(" = ").render(outer.get(i));
        }
    }

    /**
     * 带 QUALIFY 的一段。HANA 没有 QUALIFY 子句，所以三个库都把这段查询包成派生表：条件里的窗口函数、
     * 不在结果里的排序项作为额外的列放进去，外层按条件过滤，再排序和分页。条件只能用窗口函数，
     * 其他条件应写在 where 或 having 里，否则外层引用不到原来的表。
     */
    private static void qualified(SqlWriter w, Query.Arm arm, List<String> labels, boolean labeled, boolean ordered) {
        SelectSpec s = arm.spec();
        List<Expr<?>> items = new ArrayList<>(arm.items());
        List<String> names = new ArrayList<>(labels);
        Map<Renderable, String> columns = new IdentityHashMap<>();
        SqlWriter probe = w.probe();
        UnaryOperator<Renderable> windows = node -> {
            if (!Aggregate.isWindow(node)) return node;
            columns.computeIfAbsent(node, n -> hidden((Expr<?>) n, items, names, "_q"));
            return x -> { };
        };
        probe.substitution(windows);
        for (Cond c : s.qualify) probe.render(c);
        if (!probe.touched().isEmpty()) {
            throw new IllegalArgumentException("qualify(...) may only compare window functions; put other conditions in where(...) or having(...)");
        }
        Set<String> excluded = excludedKeys(w, arm);
        SqlWriter texts = w.probe();
        List<Order> orderBy = new ArrayList<>();
        for (Order o : s.orderBy) {
            String text = text(texts, o.expr());
            if (excluded.contains(text)) continue;
            orderBy.add(o);
            int at = -1;
            for (int i = 0; i < arm.items().size() && at < 0; i++) {
                Expr<?> item = arm.items().get(i);
                if (!(item instanceof Omitted<?>) && text(texts, item).equals(text)) at = i;
            }
            if (at >= 0) columns.put(o.expr(), labels.get(at));
            else if (s.distinct) throw new IllegalArgumentException("With distinct() and qualify(...), order by columns that are selected");
            else columns.computeIfAbsent(o.expr(), e -> hidden((Expr<?>) e, items, names, "_o"));
        }
        String alias = w.aliasFor(new Object(), "q");
        w.sql("SELECT ");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) w.sql(", ");
            w.alias(alias).sql(".").identifier(labels.get(i));
            if (labeled) w.sql(" AS ").identifier(labels.get(i));
        }
        w.sql("\nFROM (\n");
        arm(w, new Query.Arm(s.inner(), items, arm.slots()), names, true, false);
        w.sql("\n) ").alias(alias);
        UnaryOperator<Renderable> previous = w.substitution(node -> {
            String name = columns.get(node);
            return name == null ? node : x -> x.alias(alias).sql(".").identifier(name);
        });
        w.sql("\nWHERE ").render(Nodes.all(s.qualify));
        boolean limited = s.limit != null || s.offset != null;
        if (!orderBy.isEmpty() && (ordered || limited)) w.sql("\nORDER BY ").join(orderBy, ", ");
        w.substitution(previous);
        w.dialect().limit(w, s.limit, s.offset);
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.END) w.sql("\n").render(c);
        }
    }

    /** 把 e 作为派生表的一列追加到 items，列名是 prefix 加序号，返回列名。 */
    private static String hidden(Expr<?> e, List<Expr<?>> items, List<String> names, String prefix) {
        String name = prefix + (items.size() + 1);
        items.add(e);
        names.add(name);
        return name;
    }

    /**
     * {@link Query#exclude} 去掉的列原来的写法，用来去掉与它们相同的分组键和排序项；没有分组时为空，
     * 因为不分组时按不在结果里的列排序仍然成立。常量按字面量写出，只差参数值的两个表达式不会被当成相同。
     */
    private static Set<String> excludedKeys(SqlWriter w, Query.Arm arm) {
        if (arm.spec().groupBy.isEmpty()) return Set.of();
        SqlWriter probe = null;
        Set<String> keys = new HashSet<>();
        for (Expr<?> e : arm.items()) {
            if (!(e instanceof Omitted<?> o)) continue;
            if (probe == null) probe = w.probe();
            keys.add(text(probe, o.original));
        }
        return keys;
    }

    /** node 在 probe 里的写法；同一个 probe 里别名一致，所以写法相同就是同一个表达式。 */
    private static String text(SqlWriter probe, Renderable node) {
        SqlWriter f = probe.fork();
        f.inlining(true);
        f.render(node);
        return f.text();
    }

    /** 这一段真正用到的隐式连接（导航关系）：SELECT、条件、分组、排序和子句里引用到的，见 {@link ImplicitJoins#used}。 */
    private static Set<Ref.Relation> usedRelations(SqlWriter w, Query.Arm arm, List<Expr<?>> groupBy, List<Order> orderBy) {
        SelectSpec s = arm.spec();
        List<Renderable> parts = new ArrayList<>(arm.items());
        for (SelectSpec.Join j : s.joins) parts.add(j.on());
        parts.addAll(s.where);
        parts.addAll(groupBy);
        parts.addAll(s.having);
        parts.addAll(orderBy);
        parts.addAll(s.clauses);
        return ImplicitJoins.used(w, s.joins.stream().map(SelectSpec.Join::ref).toList(), parts);
    }
}
