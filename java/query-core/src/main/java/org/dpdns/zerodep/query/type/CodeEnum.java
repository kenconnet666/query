package org.dpdns.zerodep.query.type;

/**
 * 按编码存储的枚举：实现它的枚举在查询、条件和写入里都按 {@link #code()} 存取，而不是按名字。编码通常是码表里的值，
 * 例如 {@code enum Status implements CodeEnum<String> { OPEN("A"), CLOSED("C"); ... }}。
 * 编码的类型是 C 的类（String、Integer、Long 等）；读到表里没有的编码时报错。
 *
 * @param <C> 编码的 Java 类型
 */
public interface CodeEnum<C> {
    /** 这个常量在数据库里的编码，不能为 null，同一个枚举里不能重复。 */
    C code();
}
