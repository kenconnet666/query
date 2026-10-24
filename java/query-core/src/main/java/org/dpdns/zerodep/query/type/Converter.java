package org.dpdns.zerodep.query.type;

/**
 * 在应用代码看到的 Java 类型 J 与经 JDBC 读写的数据库值 D 之间转换。
 * 通过注解引用的实现类需要有公开的无参构造函数；每个类只创建一个实例并共享，因此实现应当无状态。
 *
 * @param <J> Java 类型
 * @param <D> 数据库侧的存储类型
 */
public interface Converter<J, D> {
    /** Java 值转成要写入数据库的值。 */
    D toDatabase(J value);

    /** 从数据库读到的值转成 Java 值。 */
    J fromDatabase(D value);
}
