package org.dpdns.zerodep.query.mysql;

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

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The MySQL facade: everything of {@link Sql} plus MySQL-only functions and clauses.
 * <pre>{@code import static org.dpdns.zerodep.query.mysql.Mysql.*;}</pre>
 */
public final class Mysql extends Sql {
    private static final FunctionDef DATEDIFF = FunctionDef.only(MysqlDialect.class, "DATEDIFF");
    private static final FunctionDef GREATEST = FunctionDef.only(MysqlDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(MysqlDialect.class, "LEAST");
    private static final FunctionDef DATE_FORMAT = FunctionDef.only(MysqlDialect.class, "DATE_FORMAT");
    private static final FunctionDef GROUP_CONCAT = FunctionDef.only(MysqlDialect.class, "GROUP_CONCAT", (w, args) -> {
        w.sql("GROUP_CONCAT(").render(args.get(0));
        if (args.size() > 2) w.sql(" ORDER BY ").join(args.subList(2, args.size()), ", ");
        w.sql(" SEPARATOR ").render(args.get(1)).sql(")");
    });

    private Mysql() { }

    // ---------- database ----------

    public static MysqlDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    public static MysqlDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    public static MysqlDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new MysqlDatabase(connections, configure);
    }

    // ---------- functions ----------

    /** DATEDIFF(later, earlier): days from earlier to later. */
    public static Expr<Integer> dateDiff(Expr<LocalDate> later, Expr<LocalDate> earlier) {
        return call(DATEDIFF, Integer.class, later, earlier);
    }

    /** DATE_FORMAT(value, format), e.g. {@code dateFormat(date, "%Y%m%d")}. */
    public static Expr<String> dateFormat(Expr<?> value, String format) { return call(DATE_FORMAT, String.class, value, lit(format)); }

    /** GROUP_CONCAT(value ORDER BY ... SEPARATOR separator). */
    public static Expr<String> groupConcat(Expr<String> value, String separator, Order... order) {
        List<Renderable> args = new ArrayList<>();
        args.add(value);
        args.add(lit(separator));
        args.addAll(List.of(order));
        return new Expr<>(value.type()) {
            @Override public void render(SqlWriter w) { GROUP_CONCAT.render(w, args); }
        };
    }

    // ---------- clauses ----------

    /** Optimizer hint {@code MAX_EXECUTION_TIME}: the statement is aborted after the given milliseconds. */
    public static Clause maxExecutionTime(int milliseconds) {
        if (milliseconds < 1) throw new IllegalArgumentException("maxExecutionTime needs a positive number of milliseconds");
        String hint = "/*+ MAX_EXECUTION_TIME(" + milliseconds + ") */";
        return new Clause() {
            @Override public Position position() { return Position.HINT; }

            @Override public Class<? extends Dialect> dialect() { return MysqlDialect.class; }

            @Override public void render(SqlWriter w) { w.sql(hint); }
        };
    }

    /** GREATEST(a, b, ...), constants as {@code val(x)}: NULL as soon as one argument is NULL. */
    @SafeVarargs
    public static <T> Expr<T> greatest(Expr<T> first, Expr<? extends T>... rest) {
        return call(GREATEST, first.type(), twoOrMore("GREATEST", first, rest));
    }

    /** LEAST(a, b, ...), constants as {@code val(x)}: NULL as soon as one argument is NULL. */
    @SafeVarargs
    public static <T> Expr<T> least(Expr<T> first, Expr<? extends T>... rest) {
        return call(LEAST, first.type(), twoOrMore("LEAST", first, rest));
    }
}
