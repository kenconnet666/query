package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

import java.util.List;

/** OVER (...) 里的窗口定义，在 {@code over(w -> w.partitionBy(...))} 里从 {@link #EMPTY} 开始构造。不可变。 */
public final class Window implements Renderable {
    /** 空窗口：没有分区、排序和窗口帧。 */
    public static final Window EMPTY = new Window(List.of(), List.of(), null);

    /** PARTITION BY 的键。 */
    private final List<Expr<?>> partition;

    /** 窗口内的 ORDER BY。 */
    private final List<Order> order;

    /** 窗口帧，null 表示不写。 */
    private final Renderable frame;

    /** 只能通过 {@link #EMPTY} 和各修改方法创建。 */
    private Window(List<Expr<?>> partition, List<Order> order, Renderable frame) {
        this.partition = partition;
        this.order = order;
        this.frame = frame;
    }

    /** 按 keys 分区。 */
    public Window partitionBy(Expr<?>... keys) { return new Window(List.of(keys), order, frame); }

    /** 窗口内的排序；单独的表达式按升序。 */
    public Window orderBy(OrderItem... items) { return new Window(partition, OrderList.of(items), frame); }

    /** 窗口帧 ROWS BETWEEN start AND end；RANGE 帧各数据库不同，放在方言包里，用 {@link #frame} 传入。 */
    public Window rows(Bound start, Bound end) {
        String sql = "ROWS BETWEEN " + start.sql + " AND " + end.sql;
        return new Window(partition, order, w -> w.sql(sql));
    }

    /** 方言包给出的窗口帧，例如 PostgreSQL、MySQL 的 {@code Postgres.range(...)}。 */
    public Window frame(Renderable frame) { return new Window(partition, order, frame); }

    /** 写出 OVER 括号里的内容：PARTITION BY、ORDER BY、窗口帧，缺的部分省略。 */
    @Override public void render(SqlWriter w) {
        String sep = "";
        if (!partition.isEmpty()) {
            w.sql("PARTITION BY ").join(partition, ", ");
            sep = " ";
        }
        if (!order.isEmpty()) {
            w.sql(sep).sql("ORDER BY ").join(order, ", ");
            sep = " ";
        }
        if (frame != null) w.sql(sep).render(frame);
    }

    /** 窗口帧的一端。 */
    public static final class Bound {
        /** 这一端的 SQL。 */
        private final String sql;

        /** 这一端的 SQL 文本，供方言包拼窗口帧。 */
        public String sql() { return sql; }

        /** 方言包构造的端点，例如 {@code INTERVAL '7 days' PRECEDING}；只放代码里的固定文本。 */
        public static Bound custom(String sql) { return new Bound(sql); }

        /** 只能通过下面的静态方法创建。 */
        private Bound(String sql) { this.sql = sql; }

        /** UNBOUNDED PRECEDING：分区的第一行。 */
        public static Bound unboundedPreceding() { return new Bound("UNBOUNDED PRECEDING"); }

        /** 当前行之前 rows 行；rows 不能为负。 */
        public static Bound preceding(int rows) { return new Bound(nonNegative(rows) + " PRECEDING"); }

        /** CURRENT ROW：当前行。 */
        public static Bound currentRow() { return new Bound("CURRENT ROW"); }

        /** 当前行之后 rows 行；rows 不能为负。 */
        public static Bound following(int rows) { return new Bound(nonNegative(rows) + " FOLLOWING"); }

        /** UNBOUNDED FOLLOWING：分区的最后一行。 */
        public static Bound unboundedFollowing() { return new Bound("UNBOUNDED FOLLOWING"); }

        /** 校验帧偏移不为负。 */
        private static int nonNegative(int rows) {
            if (rows < 0) throw new IllegalArgumentException("Frame offsets must not be negative");
            return rows;
        }
    }
}
