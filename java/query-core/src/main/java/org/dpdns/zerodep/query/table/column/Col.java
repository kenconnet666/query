package org.dpdns.zerodep.query.table.column;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.write.Assignment;
import org.dpdns.zerodep.query.write.Assignments;

/**
 * 某个来源（{@link Ref}）里的一列，所有列接口的父接口。表接口里按库里的逻辑 SQL 类型声明具体的列接口：
 * {@link ColText}、{@link ColInteger}、{@link ColBigint}、{@link ColDecimal}、{@link ColDate}、{@link ColTimestamp} 等，
 * 泛型参数是 Java 一侧的类型；没有专门接口的类型（JSON、数组、UUID 等）声明成 {@code Col<T>}。
 * 只有个别库才有的物理类型（例如 MySQL 的无符号整数）不进接口，写在 {@code @Column} 上。结果里的列名是列方法（或行属性）的名字。
 *
 * @param <T> 值的 Java 类型
 */
public interface Col<T> extends Expr<T> {
    /** ref 里属性 property 对应的列，按属性的存储类型得到相应的列接口；表代理、CTE 的 {@code r.col(...)} 和写入都用它。 */
    static Col<?> of(Ref<?> ref, PropertyMeta property) { return Columns.create(ref, property); }

    /** 存储类型为 storageType 的列应当声明的接口，例如 String 是 {@link ColText}；没有专门接口的存储类型是 {@code Col}。 */
    static Class<?> interfaceFor(Class<?> storageType) { return Columns.declared(storageType); }

    /** 存储类型为 storageType 的列能不能声明成 declared：{@link #interfaceFor} 给出的接口，以及文本的 {@link ColClob}、字节串的 {@link ColBlob}。 */
    static boolean declarable(Class<?> declared, Class<?> storageType) { return Columns.allows(declared, storageType); }

    /** 这一列所属的来源出现。 */
    Ref<?> ref();

    /** 对应的属性元数据。 */
    PropertyMeta property();

    /** 只写出列名（不带别名），用于 INSERT 的列清单和 UPDATE 的 SET。 */
    void renderName(SqlWriter w);

    /** UPDATE 的 SET 项，写进 {@code set(t -> { ... })} 的赋值块：把这一列设为 value，value 为 null 时设为 NULL；开启 skipEmpty 时值为空则省略这一项。 */
    default void to(T value) {
        if (SkipEmpty.skips(value)) {
            Assignments.add(Assignment.SKIPPED);
            return;
        }
        Assignments.add(new Assignment(this, new Nodes.Param<>(type(), value)));
    }

    /** UPDATE 的 SET 项：把这一列设为表达式的值，例如 {@code set(l -> l.quantity().to(l.quantity().plus(BigDecimal.ONE)))}。 */
    default void to(Expr<T> value) { Assignments.add(new Assignment(this, value)); }
}
