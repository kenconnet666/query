package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.type.SqlType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * HANA 门面：包含 {@link Sql} 的全部能力，外加 HANA 专属的函数和子句。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.hana.Hana.*;}</pre>
 * HANA 专属函数在其他方言上渲染时会报错。
 */
public final class Hana extends Sql {
    // 只能在 HANA 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef DAYS_BETWEEN = FunctionDef.only(HanaDialect.class, "DAYS_BETWEEN");
    private static final FunctionDef ADD_DAYS = FunctionDef.only(HanaDialect.class, "ADD_DAYS");
    private static final FunctionDef ADD_MONTHS = FunctionDef.only(HanaDialect.class, "ADD_MONTHS");
    private static final FunctionDef ADD_YEARS = FunctionDef.only(HanaDialect.class, "ADD_YEARS");
    private static final FunctionDef TO_VARCHAR = FunctionDef.only(HanaDialect.class, "TO_VARCHAR");
    private static final FunctionDef TO_DATE = FunctionDef.only(HanaDialect.class, "TO_DATE");
    private static final FunctionDef TO_NVARCHAR = FunctionDef.only(HanaDialect.class, "TO_NVARCHAR");
    private static final FunctionDef LENGTH = FunctionDef.only(HanaDialect.class, "LENGTH");
    private static final FunctionDef TO_INTEGER = FunctionDef.only(HanaDialect.class, "TO_INTEGER");
    private static final FunctionDef GREATEST = FunctionDef.only(HanaDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(HanaDialect.class, "LEAST");
    private static final FunctionDef CUBE = FunctionDef.only(HanaDialect.class, "CUBE");
    private static final FunctionDef GROUPING_SETS = FunctionDef.only(HanaDialect.class, "GROUPING SETS",
        (w, args) -> w.sql("GROUPING SETS (").join(args, ", ").sql(")"));
    private static final FunctionDef STRING_AGG = FunctionDef.only(HanaDialect.class, "STRING_AGG", (w, args) -> {
        w.sql("STRING_AGG(").render(args.get(0)).sql(", ").render(args.get(1));
        if (args.size() > 2) w.sql(" ORDER BY ").join(args.subList(2, args.size()), ", ");
        w.sql(")");
    });

    /** 只放静态方法，不能实例化。 */
    private Hana() { }

    // ---------- 数据库 ----------

    /** 用默认配置创建 HANA 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static HanaDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 HANA 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static HanaDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 创建 HANA 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。 */
    public static HanaDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new HanaDatabase(connections, configure);
    }

    // ---------- 函数 ----------

    /** DAYS_BETWEEN：从 {@code from} 到 {@code to} 相隔的天数。 */
    public static Expr<Integer> daysBetween(Expr<LocalDate> from, Expr<LocalDate> to) {
        return call(DAYS_BETWEEN, Integer.class, from, to);
    }

    /** ADD_DAYS：日期加上 {@code days} 天。 */
    public static Expr<LocalDate> addDays(Expr<LocalDate> date, int days) { return call(ADD_DAYS, date.type(), date, lit(days)); }

    /** ADD_MONTHS：日期加上 {@code months} 个月。 */
    public static Expr<LocalDate> addMonths(Expr<LocalDate> date, int months) { return call(ADD_MONTHS, date.type(), date, lit(months)); }

    /** ADD_YEARS：日期加上 {@code years} 年。 */
    public static Expr<LocalDate> addYears(Expr<LocalDate> date, int years) { return call(ADD_YEARS, date.type(), date, lit(years)); }

    /** TO_VARCHAR(value, format)，例如 {@code toVarchar(date, "YYYYMMDD")}。 */
    public static Expr<String> toVarchar(Expr<?> value, String format) { return call(TO_VARCHAR, String.class, value, lit(format)); }

    /** TO_DATE(text, format)：按 {@code format} 把文本解析为日期。 */
    public static Expr<LocalDate> toDate(Expr<String> text, String format) { return call(TO_DATE, LocalDate.class, text, lit(format)); }

    /** TO_NVARCHAR(value, format)，{@link #toVarchar} 的 Unicode 版本。 */
    public static Expr<String> toNvarchar(Expr<?> value, String format) { return call(TO_NVARCHAR, String.class, value, lit(format)); }

    /** TO_NVARCHAR(value)，不带格式。 */
    public static Expr<String> toNvarchar(Expr<?> value) { return call(TO_NVARCHAR, String.class, value); }

    /** {@code e LIKE_REGEXPR pattern}，HANA 的正则谓词。skipEmpty 下模式为空时没有条件。 */
    public static Cond likeRegexpr(Expr<String> e, String pattern) {
        if (skipped(pattern)) return Cond.EMPTY;
        Expr<String> p = val(pattern, SqlType.of(String.class));
        return new Cond() {
            @Override public void render(SqlWriter w) {
                if (!(w.dialect() instanceof HanaDialect)) {
                    throw new UnsupportedOperationException("LIKE_REGEXPR is a HanaDialect predicate; this database uses " + w.dialect().name());
                }
                w.render(e).sql(" LIKE_REGEXPR ").render(p);
            }
        };
    }

    /** LENGTH：HANA 按 UTF-16 码元计数，所以 BMP 之外的字符算 2。 */
    public static Expr<Integer> length(Expr<String> text) { return call(LENGTH, Integer.class, text); }

    /** TO_INTEGER(value)，也可写作 TO_INT：把文本或数字转成整数；文本不是数字时查询会失败。 */
    public static Expr<Integer> toInteger(Expr<?> value) { return call(TO_INTEGER, Integer.class, value); }

    /** STRING_AGG(value, separator ORDER BY ...)：把组内的值按 {@code order} 排序后用 {@code separator} 拼接。 */
    public static Expr<String> stringAgg(Expr<String> value, String separator, OrderItem... order) {
        List<Renderable> args = new ArrayList<>();
        args.add(value);
        args.add(lit(separator));
        args.addAll(orders(order));
        return new StringAgg(value.type(), args);
    }

    // ---------- 分组键 ----------

    /** CUBE(keys) 作为 GROUP BY 键：对键的每种组合都给出小计。 */
    public static Expr<Object> cube(Expr<?>... keys) { return call(CUBE, Object.class, keys); }

    /** GROUPING SETS ((a, b), (a), ()) 作为 GROUP BY 键：恰好给出所列的分组；空列表表示总计。 */
    @SafeVarargs
    public static Expr<Object> groupingSets(List<? extends Expr<?>>... sets) {
        List<Renderable> args = new ArrayList<>();
        for (List<? extends Expr<?>> set : sets) args.add(w -> w.sql("(").join(set, ", ").sql(")"));
        return new Expr<>(SqlType.of(Object.class)) {
            @Override public void render(SqlWriter w) { GROUPING_SETS.render(w, args); }
        };
    }

    // ---------- 子句 ----------

    /** 语句末尾的 WITH HINT(...)。 */
    public static Clause hint(String... hints) {
        if (hints.length == 0) throw new IllegalArgumentException("hint() needs at least one hint");
        String text = "WITH HINT(" + String.join(", ", hints) + ")";
        return new Clause() {
            @Override public Position position() { return Position.END; }

            @Override public Class<? extends Dialect> dialect() { return HanaDialect.class; }

            @Override public void render(SqlWriter w) { w.sql(text); }
        };
    }

    /** STRING_AGG 表达式：ORDER BY 项作为参数一起传入，由渲染器放进函数调用内部。 */
    private static final class StringAgg extends Expr<String> {
        private final List<Renderable> args;

        StringAgg(SqlType<String> type, List<Renderable> args) {
            super(type);
            this.args = List.copyOf(args);
        }

        @Override public void render(SqlWriter w) { STRING_AGG.render(w, args); }
    }

    /** GREATEST(a, b, ...)，常量写作 {@code val(x)}：只要有一个参数为 NULL，结果就是 NULL。 */
    @SafeVarargs
    public static <T> Expr<T> greatest(Expr<T> first, Expr<? extends T>... rest) {
        return call(GREATEST, first.type(), twoOrMore("GREATEST", first, rest));
    }

    /** LEAST(a, b, ...)，常量写作 {@code val(x)}：只要有一个参数为 NULL，结果就是 NULL。 */
    @SafeVarargs
    public static <T> Expr<T> least(Expr<T> first, Expr<? extends T>... rest) {
        return call(LEAST, first.type(), twoOrMore("LEAST", first, rest));
    }
}
