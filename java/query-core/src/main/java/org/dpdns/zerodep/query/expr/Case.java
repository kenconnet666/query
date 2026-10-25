package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 搜索型 CASE WHEN ... THEN ... 的构造器，由 {@code caseWhen(() -> 条件, 结果)} 开始：
 * {@code caseWhen(() -> l.unit().eq("KG"), l.quantity()).when(() -> l.unit().eq("G"), x).otherwise(BigDecimal.ZERO)}。
 * 第一个分支决定结果的值类型 T 和类型接口 F（结果是 {@code ExprDecimal} 时可以接着 {@code .times(...)}），其余分支的值类型必须一致。
 * 不可变：每次 {@code when} 返回新对象。
 *
 * @param <T> 结果的值类型
 * @param <F> 结果的类型接口
 */
public final class Case<T, F extends Expr<T>> {
    /** 结果类型，用于把常量结果绑定成参数。 */
    private final SqlType<T> type;

    /** 各分支的 WHEN 条件。 */
    private final List<Cond> conditions;

    /** 各分支的 THEN 结果，与 conditions 一一对应。 */
    private final List<Expr<T>> results;

    /** 把 CASE 节点包成结果的类型接口。 */
    private final Function<Expr<T>, F> family;

    /** 全部字段的构造。 */
    private Case(SqlType<T> type, List<Cond> conditions, List<Expr<T>> results, Function<Expr<T>, F> family) {
        this.type = type;
        this.conditions = conditions;
        this.results = results;
        this.family = family;
    }

    /** 第一个分支：收集 condition 写下的条件，成立时取 result；family 把结果包成 result 的类型接口。供 {@code caseWhen} 使用。 */
    public static <T, F extends Expr<T>> Case<T, F> start(Runnable condition, Expr<T> result, Function<Expr<T>, F> family) {
        return new Case<T, F>(result.type(), List.of(), List.of(), family).when(condition, result);
    }

    /** 追加一个分支：条件成立时取表达式 result。 */
    public Case<T, F> when(Runnable condition, Expr<T> result) {
        List<Cond> c = new ArrayList<>(conditions);
        List<Expr<T>> r = new ArrayList<>(results);
        c.add(Conditions.collect(condition));
        r.add(result);
        return new Case<>(type, c, r, family);
    }

    /** 追加一个分支：条件成立时取常量 result，按结果类型绑定为参数。 */
    public Case<T, F> when(Runnable condition, T result) { return when(condition, new Nodes.Param<>(type, result)); }

    /** 以表达式 result 作为 ELSE 并结束。 */
    public F otherwise(Expr<T> result) { return family.apply(new CaseExpr<>(type, null, conditions, results, result)); }

    /** 以常量 result 作为 ELSE 并结束。 */
    public F otherwise(T result) { return otherwise(new Nodes.Param<>(type, result)); }

    /** 不写 ELSE 就结束：没有分支命中的行得到 NULL。 */
    public F end() { return family.apply(new CaseExpr<>(type, null, conditions, results, null)); }

    /** {@code CASE [subject] WHEN key THEN result ... [ELSE otherwise] END}；有 subject 时 key 是与它比较的值，没有时 key 是条件。 */
    static final class CaseExpr<T> extends ExprNode<T> {
        /** 简单 CASE 的比较对象；搜索型 CASE 为 null。 */
        private final Expr<?> subject;

        /** 各分支的 WHEN 部分：条件，或与 subject 比较的值。 */
        private final List<? extends Renderable> keys;

        /** 各分支的 THEN 结果，与 keys 一一对应。 */
        private final List<Expr<T>> results;

        /** ELSE 结果；为 null 时不写 ELSE。 */
        private final Expr<T> otherwise;

        /** 由 {@link Case} 和 {@link Simple} 结束时创建。 */
        CaseExpr(SqlType<T> type, Expr<?> subject, List<? extends Renderable> keys, List<Expr<T>> results, Expr<T> otherwise) {
            super(type);
            this.subject = subject;
            this.keys = List.copyOf(keys);
            this.results = List.copyOf(results);
            this.otherwise = otherwise;
        }

        /** 写出 CASE 表达式；分支的条件块里没有条件（例如都被 skipEmpty 省略）时报错，因为一个分支不能省略条件。 */
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

    /**
     * {@code CASE subject WHEN value THEN result ... END}，把代码对照表直接写在查询里。由 {@code caseOf(subject)}（{@link CaseOf}）开始，
     * 第一个分支决定结果的值类型 T 和类型接口 F。不可变：每次 {@code when} 返回新对象。
     *
     * @param <V> 比较对象的类型
     * @param <T> 结果的值类型
     * @param <F> 结果的类型接口
     */
    public static final class Simple<V, T, F extends Expr<T>> {
        /** 被比较的表达式。 */
        private final Expr<V> subject;

        /** 结果类型，用于把常量结果绑定成参数。 */
        private final SqlType<T> type;

        /** 各分支的 WHEN 值，已绑定为参数。 */
        private final List<Expr<V>> values;

        /** 各分支的 THEN 结果，与 values 一一对应。 */
        private final List<Expr<T>> results;

        /** 把 CASE 节点包成结果的类型接口。 */
        private final Function<Expr<T>, F> family;

        /** 全部字段的构造。 */
        private Simple(Expr<V> subject, SqlType<T> type, List<Expr<V>> values, List<Expr<T>> results, Function<Expr<T>, F> family) {
            this.subject = subject;
            this.type = type;
            this.values = values;
            this.results = results;
            this.family = family;
        }

        /** 第一个分支：subject 等于 value 时取 result；family 把结果包成 result 的类型接口。供 {@link CaseOf} 使用。 */
        static <V, T, F extends Expr<T>> Simple<V, T, F> start(Expr<V> subject, V value, Expr<T> result, Function<Expr<T>, F> family) {
            return new Simple<V, T, F>(subject, result.type(), List.of(), List.of(), family).when(value, result);
        }

        /** 追加一个分支：subject 等于 value 时取表达式 result。 */
        public Simple<V, T, F> when(V value, Expr<T> result) {
            List<Expr<V>> v = new ArrayList<>(values);
            List<Expr<T>> r = new ArrayList<>(results);
            v.add(subject.param(value));
            r.add(result);
            return new Simple<>(subject, type, v, r, family);
        }

        /** 追加一个分支：subject 等于 value 时取常量 result。 */
        public Simple<V, T, F> when(V value, T result) { return when(value, new Nodes.Param<>(type, result)); }

        /** 以表达式 result 作为 ELSE 并结束。 */
        public F otherwise(Expr<T> result) { return family.apply(new CaseExpr<>(type, subject, values, results, result)); }

        /** 以常量 result 作为 ELSE 并结束。 */
        public F otherwise(T result) { return otherwise(new Nodes.Param<>(type, result)); }

        /** 不写 ELSE 就结束：没有分支命中的行得到 NULL。 */
        public F end() { return family.apply(new CaseExpr<>(type, subject, values, results, null)); }
    }
}
