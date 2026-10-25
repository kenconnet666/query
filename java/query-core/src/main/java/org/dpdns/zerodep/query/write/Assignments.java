package org.dpdns.zerodep.query.write;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * UPDATE 赋值块的接收处：{@code set(l -> { l.unit().to("KG"); l.quantity().to(x); })} 运行期间，列的 {@code to(...)} 写进最内层的赋值块。
 * 在赋值块外写 {@code to(...)} 立即报错。块按线程记录，lambda 在调用方的线程里同步执行。
 */
public final class Assignments {
    /** 当前线程上嵌套的赋值块，最内层在栈顶。 */
    private static final ThreadLocal<Deque<List<Assignment>>> BLOCKS = ThreadLocal.withInitial(ArrayDeque::new);

    /** 工具类，不能实例化。 */
    private Assignments() { }

    /** 在新的赋值块里运行 block，返回写下的 SET 项（不含 skipEmpty 省略的）。 */
    public static List<Assignment> collect(Runnable block) {
        Deque<List<Assignment>> blocks = BLOCKS.get();
        List<Assignment> written = new ArrayList<>();
        blocks.push(written);
        try {
            block.run();
        } finally {
            blocks.pop();
        }
        return List.copyOf(written);
    }

    /** 把 SET 项写进最内层的赋值块；省略的项被丢掉。不在赋值块里时报错。 */
    public static void add(Assignment assignment) {
        List<Assignment> block = BLOCKS.get().peek();
        if (block == null) throw new IllegalStateException("to(...) assigns a column only inside set(t -> { ... }) of an update");
        if (!assignment.skipped()) block.add(assignment);
    }
}
