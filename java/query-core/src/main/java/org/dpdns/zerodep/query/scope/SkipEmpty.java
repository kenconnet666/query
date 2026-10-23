package org.dpdns.zerodep.query.scope;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * 作用域的 {@code skipEmpty()} 模式：在它的 WHERE、HAVING、ON lambda 运行期间，与 null、空白字符串或空集合的比较
 * 不产生任何条件。在这类 lambda 里构造的子查询继承该模式；模式之外，与 null 比较仍然是错误。
 */
public final class SkipEmpty {
    /** 当前线程是否处于 skipEmpty 模式；用 ThreadLocal 是因为条件在 lambda 里构造，模式要传到其中的所有表达式方法。 */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    /** 工具类，不能实例化。 */
    private SkipEmpty() { }

    /** on 为 true 时在 skipEmpty 模式下执行 body 并在结束后退出；已经在模式内（例如子查询）或 on 为 false 时直接执行。 */
    static <T> T within(boolean on, Supplier<T> body) {
        if (!on || active()) return body.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            ACTIVE.remove();
        }
    }

    /** 当前线程是否处于 skipEmpty 模式。 */
    static boolean active() { return ACTIVE.get() != null; }

    /** 针对这个值的条件是否应当省略：处于 skipEmpty 模式，且值为空。 */
    public static boolean skips(Object value) { return active() && isEmpty(value); }

    /** null、空白字符串或空集合算“空”。 */
    private static boolean isEmpty(Object value) {
        return value == null || value instanceof String s && s.isBlank() || value instanceof Collection<?> c && c.isEmpty();
    }
}
