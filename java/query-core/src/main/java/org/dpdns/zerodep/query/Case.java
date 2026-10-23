package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;

/** 搜索型 CASE WHEN ... THEN ... 的构造器，第一个分支决定结果类型 T，其余分支必须一致。不可变：每次 {@code when} 返回新对象。 */
public final class Case<T> {
    /** 结果类型，用于把常量结果绑定成参数。 */
    private final SqlType<T> type;

    /** 各分支的 WHEN 条件。 */
    private final List<Cond> conditions;

    /** 各分支的 THEN 结果，与 conditions 一一对应。 */
    private final List<Expr<T>> results;

    /** 由 {@link Sql} 创建。 */
    Case(SqlType<T> type, List<Cond> conditions, List<Expr<T>> results) {
        this.type = type;
        this.conditions = conditions;
        this.results = results;
    }

    /** 追加一个分支：条件成立时取表达式 result。 */
    public Case<T> when(Cond condition, Expr<T> result) {
        List<Cond> c = new ArrayList<>(conditions);
        List<Expr<T>> r = new ArrayList<>(results);
        c.add(condition);
        r.add(result);
        return new Case<>(type, c, r);
    }

    /** 追加一个分支：条件成立时取常量 result，按结果类型绑定为参数。 */
    public Case<T> when(Cond condition, T result) { return when(condition, new Nodes.Param<>(type, result)); }

    /** 以表达式 result 作为 ELSE 并结束。 */
    public Expr<T> otherwise(Expr<T> result) { return new CaseExpr<>(type, null, conditions, results, result); }

    /** 以常量 result 作为 ELSE 并结束。 */
    public Expr<T> otherwise(T result) { return otherwise(new Nodes.Param<>(type, result)); }

    /** 不写 ELSE 就结束：没有分支命中的行得到 NULL。 */
    public Expr<T> end() { return new CaseExpr<>(type, null, conditions, results, null); }

    /** {@code CASE [subject] WHEN key THEN result ... [ELSE otherwise] END}；有 subject 时 key 是与它比较的值，没有时 key 是条件。 */
    static final class CaseExpr<T> extends Expr<T> {
        /** 简单 CASE 的比较对象；搜索型 CASE 为 null。 */
        private final Expr<?> subject;

        /** 各分支的 WHEN 部分：条件，或与 subject 比较的值。 */
        private final List<? extends Renderable> keys;

        /** 各分支的 THEN 结果，与 keys 一一对应。 */
        private final List<Expr<T>> results;

        /** ELSE 结果；为 null 时不写 ELSE。 */
        private final Expr<T> otherwise;

        /** 由 {@link Case} 和 {@link SimpleCase} 结束时创建。 */
        CaseExpr(SqlType<T> type, Expr<?> subject, List<? extends Renderable> keys, List<Expr<T>> results, Expr<T> otherwise) {
            super(type);
            this.subject = subject;
            this.keys = List.copyOf(keys);
            this.results = List.copyOf(results);
            this.otherwise = otherwise;
        }

        /** 写出 CASE 表达式；条件为 {@link Cond#EMPTY} 的分支（例如 skipEmpty 省略了条件）会报错，因为一个分支不能省略条件。 */
        @Override public void render(SqlWriter w) {
            w.sql("CASE");
            if (subject != null) w.sql(" ").render(subject);
            for (int i = 0; i < keys.size(); i++) {
                Renderable key = keys.get(i);
                if (key instanceof Cond c && c.isEmpty()) throw new IllegalStateException("A CASE branch has no condition");
                w.sql(" WHEN ").render(key).sql(" THEN ").render(results.get(i));
            }
            if (otherwise != null) w.sql(" ELSE ").render(otherwise);
            w.sql(" END");
        }
    }
}
