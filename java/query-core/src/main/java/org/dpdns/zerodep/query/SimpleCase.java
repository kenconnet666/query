package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code CASE subject WHEN value THEN result ... END}，把代码对照表直接写在查询里。由 {@link Sql#caseOf} 开始，
 * 第一个分支决定结果类型 T。不可变：每次 {@code when} 返回新对象。
 *
 * @param <V> 比较对象的类型
 * @param <T> 结果的类型
 */
public final class SimpleCase<V, T> {
    /** 被比较的表达式。 */
    private final Expr<V> subject;

    /** 结果类型，用于把常量结果绑定成参数。 */
    private final SqlType<T> type;

    /** 各分支的 WHEN 值，已绑定为参数。 */
    private final List<Expr<V>> values;

    /** 各分支的 THEN 结果，与 values 一一对应。 */
    private final List<Expr<T>> results;

    /** 只能通过 {@link Start#when} 创建。 */
    private SimpleCase(Expr<V> subject, SqlType<T> type, List<Expr<V>> values, List<Expr<T>> results) {
        this.subject = subject;
        this.type = type;
        this.values = values;
        this.results = results;
    }

    /** 只有比较对象、还没有任何分支的状态，第一次 {@code when} 之后才确定结果类型。 */
    public static final class Start<V> {
        /** 被比较的表达式。 */
        private final Expr<V> subject;

        /** 由 {@link Sql#caseOf} 创建。 */
        Start(Expr<V> subject) { this.subject = subject; }

        /** 第一个分支：subject 等于 value 时取表达式 result，result 的类型成为结果类型。 */
        public <T> SimpleCase<V, T> when(V value, Expr<T> result) {
            return new SimpleCase<>(subject, result.type(), List.<Expr<V>>of(), List.<Expr<T>>of()).when(value, result);
        }

        /** 第一个分支：subject 等于 value 时取常量 result，结果类型取自 result 的运行时类。 */
        @SuppressWarnings("unchecked")
        public <T> SimpleCase<V, T> when(V value, T result) {
            return when(value, new Nodes.Param<>((SqlType<T>) SqlType.of(result.getClass()), result));
        }
    }

    /** 追加一个分支：subject 等于 value 时取表达式 result。 */
    public SimpleCase<V, T> when(V value, Expr<T> result) {
        List<Expr<V>> v = new ArrayList<>(values);
        List<Expr<T>> r = new ArrayList<>(results);
        v.add(subject.param(value));
        r.add(result);
        return new SimpleCase<>(subject, type, v, r);
    }

    /** 追加一个分支：subject 等于 value 时取常量 result。 */
    public SimpleCase<V, T> when(V value, T result) { return when(value, new Nodes.Param<>(type, result)); }

    /** 以表达式 result 作为 ELSE 并结束。 */
    public Expr<T> otherwise(Expr<T> result) { return new Case.CaseExpr<>(type, subject, values, results, result); }

    /** 以常量 result 作为 ELSE 并结束。 */
    public Expr<T> otherwise(T result) { return otherwise(new Nodes.Param<>(type, result)); }

    /** 不写 ELSE 就结束：没有分支命中的行得到 NULL。 */
    public Expr<T> end() { return new Case.CaseExpr<>(type, subject, values, results, null); }
}
