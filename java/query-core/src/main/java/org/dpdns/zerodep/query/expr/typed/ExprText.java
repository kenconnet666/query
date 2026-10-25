package org.dpdns.zerodep.query.expr.typed;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.ExprNode;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.expr.OrderList;
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 库里存成文本的表达式。T 是 Java 一侧的类型：普通文本列是 {@code ExprText<String>}；
 * 经转换器以文本保存的值（例如 'yyyyMMdd' 文本日期、按名字存的枚举）是 {@code ExprText<LocalDate>}、{@code ExprText<Status>}，
 * 比较时传 T，文本函数作用在库里的文本上，结果是 {@code ExprText<String>}。
 *
 * @param <T> 值的 Java 类型
 */
public interface ExprText<T> extends Family<T, ExprText<T>> {
    /** 把节点包成文本表达式，例如原生 SQL 片段 {@code ExprText.of(sql(String.class, "..."))}；节点的渲染不变。 */
    static <T> ExprText<T> of(Expr<T> node) { return new Shells.OfText<>(node); }

    @Override default ExprText<T> retype(Expr<T> node) { return of(node); }

    // ---------- 结果仍是本类型 ----------

    @Override default ExprText<T> coalesce(T fallback) { return retype(Nodes.coalesce(this, param(fallback))); }

    @Override default ExprText<T> coalesce(Expr<T> fallback) { return retype(Nodes.coalesce(this, fallback)); }

    @Override default ExprText<T> coalesce(Expr<T> fallback, Expr<T> last) { return retype(Nodes.coalesce(this, fallback, last)); }

    @Override default ExprText<T> nullif(T value) { return retype(Nodes.nullif(this, value)); }

    @Override default ExprText<T> max() { return retype(Nodes.aggregate(this, "MAX")); }

    @Override default ExprText<T> min() { return retype(Nodes.aggregate(this, "MIN")); }

    @Override default ExprText<T> filter(Runnable condition) { return retype(Aggregate.filtered(this, condition)); }

    @Override default ExprText<T> over(UnaryOperator<Window> window) { return retype(Aggregate.windowed(this, window)); }

    // ---------- 匹配：写进当前的条件块 ----------

    /** LIKE；模式原样使用，{@code %} 和 {@code _} 是通配符。skipEmpty 下模式为空时没有条件。 */
    default void like(String pattern) { match("LIKE", pattern); }

    /** NOT LIKE；skipEmpty 下模式为空时没有条件。 */
    default void notLike(String pattern) { match("NOT LIKE", pattern); }

    /** 文本包含 text：{@code LIKE '%text%'}，text 里的 % 和 _ 按普通字符匹配。skipEmpty 下 text 为空时没有条件。 */
    default void contains(String text) { likeLiteral("%", text, "%"); }

    /** 文本以 text 开头，规则同 {@link #contains}。 */
    default void startsWith(String text) { likeLiteral("", text, "%"); }

    /** 文本以 text 结尾，规则同 {@link #contains}。 */
    default void endsWith(String text) { likeLiteral("%", text, ""); }

    // ---------- 文本函数：结果是库里的文本 ----------

    /** 转大写。 */
    default ExprText<String> upper() { return text("UPPER", List.of(this)); }

    /** 转小写。 */
    default ExprText<String> lower() { return text("LOWER", List.of(this)); }

    /** 去掉首尾空格。 */
    default ExprText<String> trim() { return text("TRIM", List.of(this)); }

    /** SUBSTRING(this, start, length)，位置从 1 开始；位置写成字面量，所以这个调用可以作为分组键。 */
    default ExprText<String> substring(int start, int length) {
        return text("SUBSTRING", List.of(this, Sql.lit(checkedPosition(start)), Sql.lit(checkedLength(length))));
    }

    /** SUBSTRING(this, start)：从 start（从 1 开始）到末尾。 */
    default ExprText<String> substring(int start) { return text("SUBSTRING", List.of(this, Sql.lit(checkedPosition(start)))); }

    /** 起点是表达式的 SUBSTRING，例如 {@link #locate} 的结果加 1。 */
    default ExprText<String> substring(Expr<Integer> start) { return text("SUBSTRING", List.of(this, start)); }

    /** 起点和长度都是表达式的 SUBSTRING；常量写成 {@code lit(1)}。 */
    default ExprText<String> substring(Expr<Integer> start, Expr<Integer> length) { return text("SUBSTRING", List.of(this, start, length)); }

    /** 左边 length 个字符。 */
    default ExprText<String> left(int length) { return text("LEFT", List.of(this, Sql.lit(checkedLength(length)))); }

    /** 左边若干个字符，个数是表达式。 */
    default ExprText<String> left(Expr<Integer> length) { return text("LEFT", List.of(this, length)); }

    /** 右边 length 个字符。 */
    default ExprText<String> right(int length) { return text("RIGHT", List.of(this, Sql.lit(checkedLength(length)))); }

    /** 右边若干个字符，个数是表达式。 */
    default ExprText<String> right(Expr<Integer> length) { return text("RIGHT", List.of(this, length)); }

    /** search 在本文本中第一次出现的位置，从 1 开始，找不到为 0。 */
    default ExprInteger<Integer> locate(String search) {
        return locate(ExprText.of(Stored.param(this, String.class, Objects.requireNonNull(search, "search"))));
    }

    /** 同上，search 是表达式。 */
    default ExprInteger<Integer> locate(ExprText<?> search) {
        return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), Sql.LOCATE, List.of(this, search)));
    }

    /** 把 search 全部替换为 replacement。 */
    default ExprText<String> replace(String search, String replacement) {
        SqlType<String> text = SqlType.of(String.class);
        return text("REPLACE", List.of(this, new Nodes.Param<>(text, Objects.requireNonNull(search, "search")),
            new Nodes.Param<>(text, Objects.requireNonNull(replacement, "replacement"))));
    }

    /** 依次拼上 more；MySQL 渲染为 CONCAT(...)，其余为 ||。 */
    default ExprText<String> concat(ExprText<?>... more) {
        List<Expr<?>> args = new ArrayList<>();
        args.add(this);
        args.addAll(List.of(more));
        return ExprText.of(Nodes.call(SqlType.of(String.class), Sql.CONCAT, args));
    }

    /** 左边用 pad 补足到 length 个字符，例如物料号补零 {@code m.material().lpad(18, "0")}；比 length 长时截断成前 length 个字符。 */
    default ExprText<String> lpad(int length, String pad) { return text("LPAD", List.of(this, Sql.lit(length), Sql.lit(pad))); }

    /** 右边用 pad 补足到 length 个字符；比 length 长时截断成前 length 个字符。 */
    default ExprText<String> rpad(int length, String pad) { return text("RPAD", List.of(this, Sql.lit(length), Sql.lit(pad))); }

    /**
     * 把组内的文本用 separator 拼接（聚合），可以指定拼接顺序：{@code l.delivery().stringAgg(",", l.delivery().asc())}。
     * HANA、PostgreSQL 写成 STRING_AGG，MySQL 写成 GROUP_CONCAT（结果长度受 group_concat_max_len 限制，默认 1024 字节）。
     */
    default ExprText<String> stringAgg(String separator, OrderItem... order) {
        List<Renderable> args = new ArrayList<>();
        args.add(this);
        args.add(Sql.lit(Objects.requireNonNull(separator, "separator")));
        args.addAll(OrderList.of(order));
        Expr<?> value = this;
        return ExprText.of(new ExprNode<>(Stored.type(this, String.class)) {
            @Override public void render(SqlWriter w) { Sql.STRING_AGG.render(w, args); }

            @Override public String nameHint() { return value.nameHint(); }
        });
    }

    /** 按名字调用、结果是文本的函数；参数里有本表达式时结果沿用库里的文本类型。 */
    private ExprText<String> text(String function, List<? extends Expr<?>> args) {
        return ExprText.of(Nodes.call(Stored.type(this, String.class), function, args));
    }

    /** {@code this operator pattern}，pattern 按库里的文本绑定；skipEmpty 下 pattern 为空时没有条件。 */
    private void match(String operator, String pattern) {
        if (SkipEmpty.skips(pattern)) {
            Conditions.add(Cond.EMPTY);
            return;
        }
        Conditions.add(Nodes.compare(this, operator, Stored.param(this, String.class, pattern)));
    }

    /** prefix + 转义后的 text + suffix 作为 LIKE 模式；用 ! 作转义符，三个库的写法相同。 */
    private void likeLiteral(String prefix, String text, String suffix) {
        if (SkipEmpty.skips(text)) {
            Conditions.add(Cond.EMPTY);
            return;
        }
        Stored.param(this, String.class, text);  // 与其他比较一样报错：与 NULL 比较永远不成立
        String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        Conditions.add(Nodes.template("{0} LIKE {1} ESCAPE '!'", List.of(this, Stored.param(this, String.class, prefix + escaped + suffix))));
    }

    /** 校验文本位置从 1 开始。 */
    private static int checkedPosition(int start) {
        if (start < 1) throw new IllegalArgumentException("Text positions start at 1; negative positions differ between databases");
        return start;
    }

    /** 校验字符个数不为负。 */
    private static int checkedLength(int length) {
        if (length < 0) throw new IllegalArgumentException("A length must not be negative");
        return length;
    }
}
