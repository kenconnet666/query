package org.dpdns.zerodep.query.meta;

import java.io.Serializable;

/**
 * 对 getter 或 record 访问器的引用，例如 {@code Lips::getMaterial}、{@code Material::partNumber}。
 * 它可序列化，因此能还原出被引用方法的名字；不是方法引用的 lambda 会被拒绝。
 *
 * @param <E> 行类型
 * @param <T> 属性值类型
 */
@FunctionalInterface
public interface Getter<E, T> extends Serializable {
    /** 读取一行的属性值；元数据只用它的方法名，不会真正调用。 */
    T get(E row);
}
