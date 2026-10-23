package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.List;

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
        // SELECT 列表里的常量写成字面量：HANA 无法给这里的参数定类型（CTE 里的 COALESCE(x, ?)
        // 对外层的 SUM 来说不是数值）。GROUP BY、DISTINCT 及其 ORDER BY 按文本匹配表达式，
        // 所以它们里面的常量也必须是字面量。
        boolean grouped = !s.groupBy.isEmpty() || s.distinct || replacedDistinct;
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
        }
        where.addAll(s.where);
        Cond whereAll = Nodes.all(where);
        if (!whereAll.isEmpty()) w.sql("\nWHERE ").render(whereAll);
        w.inlining(grouped);
        if (!s.groupBy.isEmpty()) {
            w.sql("\nGROUP BY ");
            w.dialect().groupBy(w, s.groupBy, s.rollup);
        }
        Cond having = Nodes.all(s.having);
        if (!having.isEmpty()) w.sql("\nHAVING ").render(having);
        boolean limited = s.limit != null || s.offset != null;
        if (!s.orderBy.isEmpty() && (ordered || limited)) w.sql("\nORDER BY ").join(s.orderBy, ", ");
        w.inlining(outer);
        w.dialect().limit(w, s.limit, s.offset);
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.END) w.sql("\n").render(c);
        }
    }
}
