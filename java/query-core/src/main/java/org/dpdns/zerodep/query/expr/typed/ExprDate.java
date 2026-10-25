package org.dpdns.zerodep.query.expr.typed;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.type.SqlType;

import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 库里存成日期（DATE）的表达式。以文本保存的日期（例如 SAP 的 'yyyyMMdd'）不是它，而是 {@code ExprText<LocalDate>}：
 * 日期函数作用在库里的值上，对文本会生成错误的 SQL。
 *
 * @param <T> 值的 Java 类型
 */
public interface ExprDate<T> extends Family<T, ExprDate<T>> {
    /** 把节点包成日期表达式，例如原生 SQL 片段 {@code ExprDate.of(sql(LocalDate.class, "..."))}；节点的渲染不变。 */
    static <T> ExprDate<T> of(Expr<T> node) { return new Shells.OfDate<>(node); }

    @Override default ExprDate<T> retype(Expr<T> node) { return of(node); }

    // ---------- 结果仍是本类型 ----------

    @Override default ExprDate<T> coalesce(T fallback) { return retype(Nodes.coalesce(this, param(fallback))); }

    @Override default ExprDate<T> coalesce(Expr<T> fallback) { return retype(Nodes.coalesce(this, fallback)); }

    @Override default ExprDate<T> coalesce(Expr<T> fallback, Expr<T> last) { return retype(Nodes.coalesce(this, fallback, last)); }

    @Override default ExprDate<T> nullif(T value) { return retype(Nodes.nullif(this, value)); }

    @Override default ExprDate<T> max() { return retype(Nodes.aggregate(this, "MAX")); }

    @Override default ExprDate<T> min() { return retype(Nodes.aggregate(this, "MIN")); }

    @Override default ExprDate<T> filter(Runnable condition) { return retype(Aggregate.filtered(this, condition)); }

    @Override default ExprDate<T> over(UnaryOperator<Window> window) { return retype(Aggregate.windowed(this, window)); }

    // ---------- 日期函数 ----------

    /** 年份：{@code groupBy(l -> keys(l.onDay().year(), l.onDay().month()))}。 */
    default ExprInteger<Integer> year() { return part(Sql.YEAR); }

    /** 月份，1 到 12。 */
    default ExprInteger<Integer> month() { return part(Sql.MONTH); }

    /** 月中的第几天，1 到 31。 */
    default ExprInteger<Integer> day() { return part(Sql.DAY); }

    /** 加上 days 天，可以为负。 */
    default ExprDate<LocalDate> addDays(int days) { return shift(Sql.ADD_DAYS, days); }

    /** 加上 months 个月；目标月没有那一天时取月末，例如 1 月 31 日加一个月是 2 月末。 */
    default ExprDate<LocalDate> addMonths(int months) { return shift(Sql.ADD_MONTHS, months); }

    /** 加上 years 年；2 月 29 日加一年是 2 月 28 日。 */
    default ExprDate<LocalDate> addYears(int years) { return shift(Sql.ADD_YEARS, years); }

    /** 当月第一天，用于按月汇总时得到一个日期：{@code groupBy(e -> e.onDay().monthStart())}。 */
    default ExprDate<LocalDate> monthStart() { return ExprDate.of(Nodes.call(Stored.type(this, LocalDate.class), Sql.MONTH_START, List.of(this))); }

    /** 从本日期到 to 相隔的天数，to 较早时为负。 */
    default ExprInteger<Integer> daysBetween(ExprDate<?> to) {
        return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), Sql.DAYS_BETWEEN, List.of(this, to)));
    }

    /** 取日期的一部分，结果是整数。 */
    private ExprInteger<Integer> part(FunctionDef function) { return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), function, List.of(this))); }

    /** 日期加上 amount 个单位，单位由函数决定。 */
    private ExprDate<LocalDate> shift(FunctionDef function, int amount) {
        return ExprDate.of(Nodes.call(Stored.type(this, LocalDate.class), function, List.of(this, new Nodes.Literal<>(SqlType.of(Integer.class), amount))));
    }
}
