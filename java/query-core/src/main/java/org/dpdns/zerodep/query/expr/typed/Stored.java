package org.dpdns.zerodep.query.expr.typed;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.type.SqlType;

/**
 * 按库里存的类型计算的结果与参数的类型。函数作用在库里的值上：文本日期列 {@code upper()} 的结果是文本，
 * 而不是经过日期转换器的 LocalDate，所以结果和参数要用存储类型；Java 类型本来就是存储类型时沿用表达式自己的类型，
 * 挂在它上面的处理（例如文本到文本的转换器）照样生效。
 */
final class Stored {
    /** 工具类，不能实例化。 */
    private Stored() { }

    /** e 按 stored 存储时，函数结果的类型。 */
    @SuppressWarnings("unchecked")
    static <S> SqlType<S> type(Expr<?> e, Class<S> stored) {
        return e.type().javaType() == stored ? (SqlType<S>) e.type() : SqlType.of(stored);
    }

    /** 与 e 一起使用的常量，按 e 的存储类型绑定；value 为 null 时与其他比较一样报错。 */
    static <S> Nodes.Param<S> param(Expr<?> e, Class<S> stored, S value) {
        if (value == null) {
            throw new IllegalArgumentException("Comparing with NULL never matches; use isNull(), or skipEmpty() on the query to drop conditions on empty values");
        }
        return new Nodes.Param<>(type(e, stored), value);
    }
}
