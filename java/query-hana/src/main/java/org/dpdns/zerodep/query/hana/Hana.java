package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Cond;
import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.Expr;
import org.dpdns.zerodep.query.Order;
import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.type.SqlType;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The HANA facade: everything of {@link Sql} plus HANA-only functions and clauses. One static
 * import gives the whole vocabulary:
 * <pre>{@code import static org.dpdns.zerodep.query.hana.Hana.*;}</pre>
 * HANA-only functions refuse to render on another dialect.
 */
public final class Hana extends Sql {
    private static final FunctionDef DAYS_BETWEEN = FunctionDef.only(HanaDialect.class, "DAYS_BETWEEN");
    private static final FunctionDef ADD_DAYS = FunctionDef.only(HanaDialect.class, "ADD_DAYS");
    private static final FunctionDef ADD_MONTHS = FunctionDef.only(HanaDialect.class, "ADD_MONTHS");
    private static final FunctionDef ADD_YEARS = FunctionDef.only(HanaDialect.class, "ADD_YEARS");
    private static final FunctionDef TO_VARCHAR = FunctionDef.only(HanaDialect.class, "TO_VARCHAR");
    private static final FunctionDef TO_DATE = FunctionDef.only(HanaDialect.class, "TO_DATE");
    private static final FunctionDef TO_NVARCHAR = FunctionDef.only(HanaDialect.class, "TO_NVARCHAR");
    private static final FunctionDef LENGTH = FunctionDef.only(HanaDialect.class, "LENGTH");
    private static final FunctionDef CUBE = FunctionDef.only(HanaDialect.class, "CUBE");
    private static final FunctionDef GROUPING_SETS = FunctionDef.only(HanaDialect.class, "GROUPING SETS",
        (w, args) -> w.sql("GROUPING SETS (").join(args, ", ").sql(")"));
    private static final FunctionDef STRING_AGG = FunctionDef.only(HanaDialect.class, "STRING_AGG", (w, args) -> {
        w.sql("STRING_AGG(").render(args.get(0)).sql(", ").render(args.get(1));
        if (args.size() > 2) w.sql(" ORDER BY ").join(args.subList(2, args.size()), ", ");
        w.sql(")");
    });

    private Hana() { }

    // ---------- database ----------

    public static HanaDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    public static HanaDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    public static HanaDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new HanaDatabase(connections, configure);
    }

    // ---------- functions ----------

    /** Days from {@code from} to {@code to}. */
    public static Expr<Integer> daysBetween(Expr<LocalDate> from, Expr<LocalDate> to) {
        return call(DAYS_BETWEEN, Integer.class, from, to);
    }

    public static Expr<LocalDate> addDays(Expr<LocalDate> date, int days) { return call(ADD_DAYS, date.type(), date, lit(days)); }

    public static Expr<LocalDate> addMonths(Expr<LocalDate> date, int months) { return call(ADD_MONTHS, date.type(), date, lit(months)); }

    public static Expr<LocalDate> addYears(Expr<LocalDate> date, int years) { return call(ADD_YEARS, date.type(), date, lit(years)); }

    /** TO_VARCHAR(value, format), e.g. {@code toVarchar(date, "YYYYMMDD")}. */
    public static Expr<String> toVarchar(Expr<?> value, String format) { return call(TO_VARCHAR, String.class, value, lit(format)); }

    public static Expr<LocalDate> toDate(Expr<String> text, String format) { return call(TO_DATE, LocalDate.class, text, lit(format)); }

    /** TO_NVARCHAR(value, format), the Unicode form of {@link #toVarchar}. */
    public static Expr<String> toNvarchar(Expr<?> value, String format) { return call(TO_NVARCHAR, String.class, value, lit(format)); }

    public static Expr<String> toNvarchar(Expr<?> value) { return call(TO_NVARCHAR, String.class, value); }

    /** {@code e LIKE_REGEXPR pattern}, HANA's regular-expression predicate. */
    public static Cond likeRegexpr(Expr<String> e, String pattern) {
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

    /** HANA's LENGTH counts UTF-16 code units, so a character outside the BMP counts as 2. */
    public static Expr<Integer> length(Expr<String> text) { return call(LENGTH, Integer.class, text); }

    /** STRING_AGG(value, separator ORDER BY ...). */
    public static Expr<String> stringAgg(Expr<String> value, String separator, Order... order) {
        List<Renderable> args = new ArrayList<>();
        args.add(value);
        args.add(lit(separator));
        args.addAll(List.of(order));
        return new StringAgg(value.type(), args);
    }

    // ---------- grouping keys ----------

    /** CUBE(keys) as a GROUP BY key: subtotals for every combination of the keys. */
    public static Expr<Object> cube(Expr<?>... keys) { return call(CUBE, Object.class, keys); }

    /** GROUPING SETS ((a, b), (a), ()) as a GROUP BY key: exactly the listed groupings; an empty list is the grand total. */
    @SafeVarargs
    public static Expr<Object> groupingSets(List<? extends Expr<?>>... sets) {
        List<Renderable> args = new ArrayList<>();
        for (List<? extends Expr<?>> set : sets) args.add(w -> w.sql("(").join(set, ", ").sql(")"));
        return new Expr<>(SqlType.of(Object.class)) {
            @Override public void render(SqlWriter w) { GROUPING_SETS.render(w, args); }
        };
    }

    // ---------- clauses ----------

    /** WITH HINT(...) at the end of the statement. */
    public static Clause hint(String... hints) {
        if (hints.length == 0) throw new IllegalArgumentException("hint() needs at least one hint");
        String text = "WITH HINT(" + String.join(", ", hints) + ")";
        return new Clause() {
            @Override public Position position() { return Position.END; }

            @Override public Class<? extends Dialect> dialect() { return HanaDialect.class; }

            @Override public void render(SqlWriter w) { w.sql(text); }
        };
    }

    /** The ORDER BY items ride along as arguments; the renderer places them inside the call. */
    private static final class StringAgg extends Expr<String> {
        private final List<Renderable> args;

        StringAgg(SqlType<String> type, List<Renderable> args) {
            super(type);
            this.args = List.copyOf(args);
        }

        @Override public void render(SqlWriter w) { STRING_AGG.render(w, args); }
    }
}
