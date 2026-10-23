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
 * PostgreSQL 门面：包含 {@link Sql} 的全部能力，外加 PostgreSQL 专属的函数和子句。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.postgres.Postgres.*;}</pre>
 * PostgreSQL 专属函数在其他方言上渲染时会报错。
 */
public final class Postgres extends Sql {
    // 只能在 PostgreSQL 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef CUBE = FunctionDef.only(PostgresDialect.class, "CUBE");
    private static final FunctionDef GREATEST = FunctionDef.only(PostgresDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(PostgresDialect.class, "LEAST");
    private static final FunctionDef GROUPING_SETS = FunctionDef.only(PostgresDialect.class, "GROUPING SETS",
        (w, args) -> w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

    private Postgres() { }

    // ---------- 数据库 ----------

    /** 用默认配置创建 PostgreSQL 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static PostgresDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 PostgreSQL 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static PostgresDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 创建 PostgreSQL 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。 */
    public static PostgresDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new PostgresDatabase(connections, configure);
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

    /** DISTINCT ON (keys)：每个键只取第一行；ORDER BY 必须以相同的键开头。 */
    public static Clause distinctOn(Expr<?>... keys) {
        if (keys.length == 0) throw new IllegalArgumentException("distinctOn() needs at least one key");
        List<Expr<?>> list = List.of(keys);
        return clause(Clause.Position.DISTINCT, w -> w.sql("DISTINCT ON (").join(list, ", ").sql(")"));
    }

    // ---------- 函数 ----------

    /** PostgreSQL 专属的运算符，两个操作数必须同类型，类型由调用者 {@code expr} 决定。 */
    public static <T> Ops<T> pg(Expr<T> expr) { return new Ops<>(expr); }

    /** ILIKE：不区分大小写的 LIKE。skipEmpty 下模式为空时没有条件。 */
    public static Cond ilike(Expr<String> e, String pattern) {
        if (skipped(pattern)) return Cond.EMPTY;
        Expr<String> p = val(pattern, e.type());
        return cond(w -> w.render(e).sql(" ILIKE ").render(p));
    }

    /** aggregate FILTER (WHERE condition)：只聚合满足条件的行。 */
    public static <T> Expr<T> filter(Aggregate<T> aggregate, Cond condition) {
        return expr(aggregate, w -> w.render(aggregate).sql(" FILTER (WHERE ").render(condition).sql(")"));
    }

    /** STRING_AGG(value, separator ORDER BY ...)：把组内的值按 {@code order} 排序后用 {@code separator} 拼接。 */
    public static Expr<String> stringAgg(Expr<String> value, String separator, OrderItem... order) {
        List<Renderable> sort = new ArrayList<>(orders(order));
        Expr<String> sep = lit(separator);
        return expr(value, w -> {
            w.sql("STRING_AGG(").render(value).sql(", ").render(sep);
            if (!sort.isEmpty()) w.sql(" ORDER BY ").join(sort, ", ");
            w.sql(")");
        });
    }

    /** {@link #pg} 返回的运算符集合，绑定到一个表达式。 */
    public static final class Ops<T> {
        private final Expr<T> expr;

        private Ops(Expr<T> expr) { this.expr = expr; }

        /** {@code expr IS DISTINCT FROM other}：把 NULL 当作普通值比较的不等。 */
        public Cond isDistinctFrom(Expr<T> other) { return cond(w -> w.render(expr).sql(" IS DISTINCT FROM ").render(other)); }

        /** 与常量比较的 {@link #isDistinctFrom(Expr)}。 */
        public Cond isDistinctFrom(T value) { return isDistinctFrom(val(value, expr.type())); }

        /** {@code expr IS NOT DISTINCT FROM other}：把 NULL 当作普通值比较的相等。 */
        public Cond isNotDistinctFrom(Expr<T> other) { return cond(w -> w.render(expr).sql(" IS NOT DISTINCT FROM ").render(other)); }

        /** 与常量比较的 {@link #isNotDistinctFrom(Expr)}。 */
        public Cond isNotDistinctFrom(T value) { return isNotDistinctFrom(val(value, expr.type())); }
    }

    /** 构造一个只属于 PostgreSQL 方言的子句，放在 {@code position} 指定的位置。 */
    private static Clause clause(Clause.Position position, Consumer<SqlWriter> body) {
        return new Clause() {
            @Override public Position position() { return position; }

            @Override public Class<? extends Dialect> dialect() { return PostgresDialect.class; }

            @Override public void render(SqlWriter w) { body.accept(w); }
        };
    }

    /** 类型与 {@code like} 相同的 PostgreSQL 专属表达式；在其他方言上渲染会明确报错。 */
    private static <T> Expr<T> expr(Expr<T> like, Consumer<SqlWriter> body) {
        return new Expr<>(like.type()) {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                body.accept(w);
            }
        };
    }

    /** PostgreSQL 专属的条件；在其他方言上渲染会明确报错。 */
    private static Cond cond(Consumer<SqlWriter> body) {
        return new Cond() {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                body.accept(w);
            }
        };
    }

    /** 渲染目标不是 PostgreSQL 时抛出异常。 */
    private static void requirePostgres(SqlWriter w) {
        if (!(w.dialect() instanceof PostgresDialect)) {
            throw new UnsupportedOperationException("A PostgreSQL-only operation cannot run on " + w.dialect().name());
        }
    }

    /** GREATEST(a, b, ...)，常量写作 {@code val(x)}：忽略 NULL 参数，仅当全部为 NULL 时结果才是 NULL。 */
    @SafeVarargs
    public static <T> Expr<T> greatest(Expr<T> first, Expr<? extends T>... rest) {
        return call(GREATEST, first.type(), twoOrMore("GREATEST", first, rest));
    }

    /** LEAST(a, b, ...)，常量写作 {@code val(x)}：忽略 NULL 参数，仅当全部为 NULL 时结果才是 NULL。 */
    @SafeVarargs
    public static <T> Expr<T> least(Expr<T> first, Expr<? extends T>... rest) {
        return call(LEAST, first.type(), twoOrMore("LEAST", first, rest));
    }
}
