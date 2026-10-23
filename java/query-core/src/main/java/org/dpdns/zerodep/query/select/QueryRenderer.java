package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.Order;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
            if (i > 0) w.sql("\n").sql(c.operators().get(i - 1)).sql("\n");
            w.sql("(".repeat(c.opens(i)));
            arm(w, arms.get(i), i == 0 && labeled ? q.labels() : null, ordered);
            w.sql(")".repeat(c.closes(i)));
        }
        if (arms.size() > 1) w.dialect().limit(w, q.limitValue(), q.offsetValue());
    }

    /**
     * 渲染一段 SELECT：提示、DISTINCT、列、FROM 与各 JOIN、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT 以及方言子句。
     * labels 为 null 时列不带名字。
     */
    private static void arm(SqlWriter w, Query.Arm arm, List<String> labels, boolean ordered) {
        SelectSpec s = arm.spec();
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
        boolean outer = w.inlining(true);
        List<Expr<?>> items = arm.items();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(items.get(i));
            if (labels != null) w.sql(" AS ").identifier(labels.get(i));
        }
        w.inlining(false);
        if (s.joins.isEmpty()) w.dialect().fromNothing(w);
        SourceFilters filters = s.filtered ? (SourceFilters) w.attribute(SourceFilters.class) : null;
        if (filters == null) filters = SourceFilters.NONE;
        List<Cond> where = new ArrayList<>();
        Set<Ref.Relation> implicit = usedRelations(w, arm, groupBy, orderBy);
        for (SelectSpec.Join j : s.joins) {
            List<Cond> extra = filters.conditionsFor(j.ref());
            switch (j.kind()) {
                case FROM -> {
                    w.sql("\nFROM ");
                    j.ref().renderSource(w);
                    where.addAll(extra);
                }
                case CROSS -> {
                    w.sql("\nCROSS JOIN ");
                    j.ref().renderSource(w);
                    where.addAll(extra);
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
            emitRelations(w, j.ref(), implicit, filters);
        }
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

    /**
     * 这一段真正用到的隐式连接（导航关系）。先用探查写入器把 SELECT、条件、分组、排序试渲染一遍，记下引用到的数据源；
     * 用到了某个关系的目标（或它更深的关系）就连接它，并把它的连接条件也试渲染，直到不再增加。没有关系时不做探查。
     * groupBy 和 orderBy 是去掉被排除的列之后实际要写出的。
     */
    private static Set<Ref.Relation> usedRelations(SqlWriter w, Query.Arm arm, List<Expr<?>> groupBy, List<Order> orderBy) {
        SelectSpec s = arm.spec();
        if (s.joins.stream().allMatch(j -> j.ref().relations().isEmpty())) return Set.of();
        SqlWriter probe = w.probe();
        for (Expr<?> e : arm.items()) probe.render(e);
        for (SelectSpec.Join j : s.joins) if (!j.on().isEmpty()) probe.render(j.on());
        for (Cond c : s.where) if (!c.isEmpty()) probe.render(c);
        for (Expr<?> g : groupBy) probe.render(g);
        for (Cond c : s.having) if (!c.isEmpty()) probe.render(c);
        for (Order o : orderBy) probe.render(o);
        for (Clause c : s.clauses) probe.render(c);
        Set<Ref.Relation> used = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (SelectSpec.Join j : s.joins) grew |= collect(j.ref(), probe, used);
        }
        return used;
    }

    /** 把 ref 出发的、用到了的关系加入 used，并试渲染它们的连接条件；有新增时返回 true。 */
    private static boolean collect(Ref<?> ref, SqlWriter probe, Set<Ref.Relation> used) {
        boolean grew = false;
        for (Ref.Relation r : ref.relations()) {
            if (!used.contains(r) && reaches(r.target(), probe.touched())) {
                used.add(r);
                probe.render(r.on());
                grew = true;
            }
            if (used.contains(r)) grew |= collect(r.target(), probe, used);
        }
        return grew;
    }

    /** ref 或从它出发的某个关系的目标是否被引用到。 */
    private static boolean reaches(Ref<?> ref, Set<Object> touched) {
        if (touched.contains(ref)) return true;
        for (Ref.Relation r : ref.relations()) if (reaches(r.target(), touched)) return true;
        return false;
    }

    /** 紧跟在 ref 的 FROM 或 JOIN 之后写出它用到的隐式连接（LEFT JOIN），再写更深的关系；全局过滤器放进 ON。 */
    private static void emitRelations(SqlWriter w, Ref<?> ref, Set<Ref.Relation> used, SourceFilters filters) {
        for (Ref.Relation r : ref.relations()) {
            if (!used.contains(r)) continue;
            w.sql("\nLEFT JOIN ");
            r.target().renderSource(w);
            List<Cond> on = new ArrayList<>();
            on.add(r.on());
            on.addAll(filters.conditionsFor(r.target()));
            w.sql(" ON ").render(Nodes.all(on));
            emitRelations(w, r.target(), used, filters);
        }
    }
}
