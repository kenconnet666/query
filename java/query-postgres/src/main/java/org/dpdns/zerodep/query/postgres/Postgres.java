package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Aggregate;
import org.dpdns.zerodep.query.Cond;
import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.Expr;
import org.dpdns.zerodep.query.OrderItem;
import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.type.SqlType;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The PostgreSQL facade: everything of {@link Sql} plus PostgreSQL-only functions and clauses.
 * <pre>{@code import static org.dpdns.zerodep.query.postgres.Postgres.*;}</pre>
 */
public final class Postgres extends Sql {
    private static final FunctionDef CUBE = FunctionDef.only(PostgresDialect.class, "CUBE");
    private static final FunctionDef GREATEST = FunctionDef.only(PostgresDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(PostgresDialect.class, "LEAST");
    private static final FunctionDef GROUPING_SETS = FunctionDef.only(PostgresDialect.class, "GROUPING SETS",
        (w, args) -> w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

    private Postgres() { }

    // ---------- database ----------

    public static PostgresDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    public static PostgresDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    public static PostgresDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new PostgresDatabase(connections, configure);
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

    /** DISTINCT ON (keys): the first row per key; ORDER BY must start with the same keys. */
    public static Clause distinctOn(Expr<?>... keys) {
        if (keys.length == 0) throw new IllegalArgumentException("distinctOn() needs at least one key");
        List<Expr<?>> list = List.of(keys);
        return clause(Clause.Position.DISTINCT, w -> w.sql("DISTINCT ON (").join(list, ", ").sql(")"));
    }

    // ---------- functions ----------

    /** PostgreSQL-only operators whose operands must share a type; the receiver fixes the type. */
    public static <T> Ops<T> pg(Expr<T> expr) { return new Ops<>(expr); }

    public static Cond ilike(Expr<String> e, String pattern) {
        if (skipped(pattern)) return Cond.EMPTY;
        Expr<String> p = val(pattern, e.type());
        return cond(w -> w.render(e).sql(" ILIKE ").render(p));
    }

    /** aggregate FILTER (WHERE condition). */
    public static <T> Expr<T> filter(Aggregate<T> aggregate, Cond condition) {
        return expr(aggregate, w -> w.render(aggregate).sql(" FILTER (WHERE ").render(condition).sql(")"));
    }

    /** STRING_AGG(value, separator ORDER BY ...). */
    public static Expr<String> stringAgg(Expr<String> value, String separator, OrderItem... order) {
        List<Renderable> sort = new ArrayList<>(orders(order));
        Expr<String> sep = lit(separator);
        return expr(value, w -> {
            w.sql("STRING_AGG(").render(value).sql(", ").render(sep);
            if (!sort.isEmpty()) w.sql(" ORDER BY ").join(sort, ", ");
            w.sql(")");
        });
    }

    public static final class Ops<T> {
        private final Expr<T> expr;

        private Ops(Expr<T> expr) { this.expr = expr; }

        public Cond isDistinctFrom(Expr<T> other) { return cond(w -> w.render(expr).sql(" IS DISTINCT FROM ").render(other)); }

        public Cond isDistinctFrom(T value) { return isDistinctFrom(val(value, expr.type())); }

        public Cond isNotDistinctFrom(Expr<T> other) { return cond(w -> w.render(expr).sql(" IS NOT DISTINCT FROM ").render(other)); }

        public Cond isNotDistinctFrom(T value) { return isNotDistinctFrom(val(value, expr.type())); }
    }

    private static Clause clause(Clause.Position position, Consumer<SqlWriter> body) {
        return new Clause() {
            @Override public Position position() { return position; }

            @Override public Class<? extends Dialect> dialect() { return PostgresDialect.class; }

            @Override public void render(SqlWriter w) { body.accept(w); }
        };
    }

    /** A PostgreSQL-only expression typed like {@code like}; rendering elsewhere fails clearly. */
    private static <T> Expr<T> expr(Expr<T> like, Consumer<SqlWriter> body) {
        return new Expr<>(like.type()) {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                body.accept(w);
            }
        };
    }

    private static Cond cond(Consumer<SqlWriter> body) {
        return new Cond() {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                body.accept(w);
            }
        };
    }

    private static void requirePostgres(SqlWriter w) {
        if (!(w.dialect() instanceof PostgresDialect)) {
            throw new UnsupportedOperationException("A PostgreSQL-only operation cannot run on " + w.dialect().name());
        }
    }

    /** GREATEST(a, b, ...), constants as {@code val(x)}: NULL arguments are skipped; NULL only when all are NULL. */
    @SafeVarargs
    public static <T> Expr<T> greatest(Expr<T> first, Expr<? extends T>... rest) {
        return call(GREATEST, first.type(), twoOrMore("GREATEST", first, rest));
    }

    /** LEAST(a, b, ...), constants as {@code val(x)}: NULL arguments are skipped; NULL only when all are NULL. */
    @SafeVarargs
    public static <T> Expr<T> least(Expr<T> first, Expr<? extends T>... rest) {
        return call(LEAST, first.type(), twoOrMore("LEAST", first, rest));
    }
}
