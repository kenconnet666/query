package org.dpdns.zerodep.query.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * 语句式条件的接收处。条件方法（{@code eq}、{@code in}、{@code isNull}、{@code exists} 等）不返回值，而是把条件写进最内层的条件块；
 * 块结束时，块里的条件用 AND 合成一个：
 * <pre>{@code
 * from(Lips.class).where(l -> {
 *     l.delivery().contains("D");
 *     if (maker != null) l.mara().manufacturer().eq(maker);
 *     any(() -> { l.unit().eq("EA"); l.unit().eq("KG"); });
 * })
 * }</pre>
 * 条件块有 where、having、qualify、连接的 on、关系的 where/any/none、{@code any}、{@code not}、{@code bool}、
 * {@code caseWhen}、聚合的 {@code filter} 和全局过滤器。在块外写条件立即报错；在块内的投影、分组、排序 lambda 里写条件也报错，
 * 而不是悄悄并进外层的条件。块按线程记录，lambda 在调用方的线程里同步执行。
 */
public final class Conditions {
    /** 当前线程上嵌套的条件块，最内层在栈顶；{@link #BARRIER} 表示不收条件的 lambda（投影、分组等）。 */
    private static final ThreadLocal<Deque<List<Cond>>> BLOCKS = ThreadLocal.withInitial(ArrayDeque::new);

    /** 工具类，不能实例化。 */
    private Conditions() { }

    /** 在新的条件块里运行 block，返回块里写下的条件的 AND；没有写下条件（或都因 skipEmpty 省略）时返回 {@link Cond#EMPTY}。 */
    public static Cond collect(Runnable block) { return Nodes.all(list(block)); }

    /** 在新的条件块里运行 block，返回块里写下的各个条件，供 OR 这类按项组合的块使用。 */
    public static List<Cond> list(Runnable block) {
        Deque<List<Cond>> blocks = BLOCKS.get();
        List<Cond> written = new ArrayList<>();
        blocks.push(written);
        try {
            block.run();
        } finally {
            blocks.pop();
        }
        return written;
    }

    /** 运行不收条件的 lambda（投影、分组、排序等）：其中写下的条件报错，而不是并进外层的条件块。 */
    public static <T> T outside(Supplier<T> body) {
        Deque<List<Cond>> blocks = BLOCKS.get();
        blocks.push(BARRIER);
        try {
            return body.get();
        } finally {
            blocks.pop();
        }
    }

    /** 把条件写进最内层的条件块；{@link Cond#EMPTY} 被丢掉。不在条件块里时报错。 */
    public static void add(Cond condition) {
        List<Cond> block = BLOCKS.get().peek();
        if (block == null || block == BARRIER) {
            throw new IllegalStateException("A condition was written outside a condition block (where, having, on, any(() -> ...) and the like); "
                + "to use a condition as a value, wrap it in bool(() -> ...) or caseWhen(() -> ..., result)");
        }
        if (!condition.isEmpty()) block.add(condition);
    }

    /** 栈上不收条件的标记；用一个不可变的空列表，与真正的条件块按引用区分。 */
    private static final List<Cond> BARRIER = List.of();
}
