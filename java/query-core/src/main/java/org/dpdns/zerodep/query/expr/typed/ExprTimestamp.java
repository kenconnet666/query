package org.dpdns.zerodep.query.expr.typed;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.type.SqlType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 库里存成不带时区的时间戳（TIMESTAMP、DATETIME）的表达式。
 *
 * @param <T> 值的 Java 类型
 */
public interface ExprTimestamp<T> extends Family<T, ExprTimestamp<T>> {
    /** 把节点包成时间戳表达式，例如原生 SQL 片段 {@code ExprTimestamp.of(sql(LocalDateTime.class, "..."))}；节点的渲染不变。 */
    static <T> ExprTimestamp<T> of(Expr<T> node) { return new Shells.OfTimestamp<>(node); }

    @Override default ExprTimestamp<T> retype(Expr<T> node) { return of(node); }

    // ---------- 结果仍是本类型 ----------

    @Override default ExprTimestamp<T> coalesce(T fallback) { return retype(Nodes.coalesce(this, param(fallback))); }

    @Override default ExprTimestamp<T> coalesce(Expr<T> fallback) { return retype(Nodes.coalesce(this, fallback)); }

    @Override default ExprTimestamp<T> coalesce(Expr<T> fallback, Expr<T> last) { return retype(Nodes.coalesce(this, fallback, last)); }

    @Override default ExprTimestamp<T> nullif(T value) { return retype(Nodes.nullif(this, value)); }

    @Override default ExprTimestamp<T> max() { return retype(Nodes.aggregate(this, "MAX")); }

    @Override default ExprTimestamp<T> min() { return retype(Nodes.aggregate(this, "MIN")); }

    @Override default ExprTimestamp<T> filter(Runnable condition) { return retype(Aggregate.filtered(this, condition)); }

    @Override default ExprTimestamp<T> over(UnaryOperator<Window> window) { return retype(Aggregate.windowed(this, window)); }

    // ---------- 时间戳函数 ----------

    /** 年份。 */
    default ExprInteger<Integer> year() { return part(Sql.YEAR); }

    /** 月份，1 到 12。 */
    default ExprInteger<Integer> month() { return part(Sql.MONTH); }

    /** 月中的第几天，1 到 31。 */
    default ExprInteger<Integer> day() { return part(Sql.DAY); }

    /** 小时，0 到 23。 */
    default ExprInteger<Integer> hour() { return part(Sql.HOUR); }

    /** 分钟，0 到 59。 */
    default ExprInteger<Integer> minute() { return part(Sql.MINUTE); }

    /** 秒，0 到 59，不足一秒的部分舍去。 */
    default ExprInteger<Integer> second() { return part(Sql.SECOND); }

    /** 加上 hours 小时，可以为负。 */
    default ExprTimestamp<LocalDateTime> addHours(int hours) { return addSeconds(hours * 3600L); }

    /** 加上 minutes 分钟，可以为负。 */
    default ExprTimestamp<LocalDateTime> addMinutes(int minutes) { return addSeconds(minutes * 60L); }

    /** 加上 seconds 秒，可以为负。 */
    default ExprTimestamp<LocalDateTime> addSeconds(long seconds) {
        return ExprTimestamp.of(Nodes.call(Stored.type(this, LocalDateTime.class), Sql.ADD_SECONDS,
            List.of(this, new Nodes.Literal<>(SqlType.of(Long.class), seconds))));
    }

    /** 从本时间戳到 to 相隔的整秒数（不足一秒的部分舍去），to 较早时为负；任一端为 NULL 时为 NULL。 */
    default ExprBigint<Long> secondsBetween(ExprTimestamp<?> to) {
        return ExprBigint.of(Nodes.call(SqlType.of(Long.class), Sql.SECONDS_BETWEEN, List.of(this, to)));
    }

    /** 取时间戳的一部分，结果是整数。 */
    private ExprInteger<Integer> part(FunctionDef function) { return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), function, List.of(this))); }
}
