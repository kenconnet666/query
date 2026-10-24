package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.TableProxy;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

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
    private static final FunctionDef PERCENTILE_CONT = FunctionDef.only(PostgresDialect.class, "PERCENTILE_CONT",
        (w, args) -> w.sql("PERCENTILE_CONT(").render(args.get(0)).sql(") WITHIN GROUP (ORDER BY ").render(args.get(1)).sql(")"));
    private static final FunctionDef PERCENTILE_DISC = FunctionDef.only(PostgresDialect.class, "PERCENTILE_DISC",
        (w, args) -> w.sql("PERCENTILE_DISC(").render(args.get(0)).sql(") WITHIN GROUP (ORDER BY ").render(args.get(1)).sql(")"));
    private static final FunctionDef MODE = FunctionDef.only(PostgresDialect.class, "MODE",
        (w, args) -> w.sql("MODE() WITHIN GROUP (ORDER BY ").render(args.get(0)).sql(")"));
    private static final FunctionDef CORR = FunctionDef.only(PostgresDialect.class, "CORR");

    /** 只放静态方法，不能实例化。 */
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

    // ---------- 集合运算、窗口帧、递归 CTE、加锁 ----------

    /** INTERSECT ALL：两边都有的行，保留重复（取两边次数的较小值）。规则同 {@code q1.unionAll(q2)}。 */
    public static <R> Query<R> intersectAll(Query<R> left, Query<R> right) { return left.combine("INTERSECT ALL", right); }

    /** EXCEPT ALL：左边有、右边没有的行，按次数相减。规则同 {@code q1.unionAll(q2)}。 */
    public static <R> Query<R> exceptAll(Query<R> left, Query<R> right) { return left.combine("EXCEPT ALL", right); }

    /**
     * RANGE 窗口帧：按排序值的距离取行，排序值相同的行一起进出，例如近 7 天的滚动合计
     * {@code over(w -> w.orderBy(l.createdOn()).frame(range(daysPreceding(6), Bound.currentRow())))}。
     * 有限的偏移要求窗口只有一个排序项；数值偏移用 {@code Bound.preceding(n)}。
     */
    public static Renderable range(Window.Bound start, Window.Bound end) {
        String sql = "RANGE BETWEEN " + start.sql() + " AND " + end.sql();
        return w -> {
            requirePostgres(w);
            w.sql(sql);
        };
    }

    /** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
    public static Window.Bound daysPreceding(int days) { return Window.Bound.custom("INTERVAL '" + nonNegative(days) + " days' PRECEDING"); }

    /** RANGE 帧的终点：排序值之后 days 天。 */
    public static Window.Bound daysFollowing(int days) { return Window.Bound.custom("INTERVAL '" + nonNegative(days) + " days' FOLLOWING"); }

    /**
     * 递归 CTE：{@code anchor UNION ALL step(自己)}，step 里 {@code from(self)} 取上一轮的行，例如沿上级编号展开组织树：
     * <pre>{@code
     * Cte<Node> tree = recursiveCte("tree",
     *     from(Org.class).where(o -> o.parent().isNull()).select(o -> cols(o.id(), lit(1).as("depth"))).into(Node.class),
     *     self -> from(self).join(Org.class, (t, o) -> o.parent().eq(t.col(Node::id)))
     *         .select((t, o) -> cols(o.id(), plus(t.col(Node::depth), lit(1)).as("depth"))).into(Node.class));
     * }</pre>
     * 语句里有递归 CTE 时写成 {@code WITH RECURSIVE}；step 必须有终止条件。
     */
    public static <E> Cte<E> recursiveCte(String name, Query<E> anchor, Function<Cte<E>, Query<E>> step) {
        return Cte.recursive(name, anchor, step, PostgresDialect.class);
    }

    /** 共享锁读 {@code FOR SHARE}：别人可以读、也可以再加共享锁，但改不了；lock 同 {@code forUpdate(lock)}。 */
    public static Clause forShare(Lock lock) { return lockClause("FOR SHARE", lock, List.of()); }

    /** 只锁 tables 这几张表的行：{@code .with((l, m) -> forUpdateOf(Lock.SKIP_LOCKED, l))} 写成 {@code FOR UPDATE OF 别名 SKIP LOCKED}。 */
    public static Clause forUpdateOf(Lock lock, Entity... tables) {
        if (tables.length == 0) throw new IllegalArgumentException("forUpdateOf(...) needs at least one table");
        return lockClause("FOR UPDATE", lock, List.of(tables));
    }

    /** {@code FOR UPDATE / FOR SHARE [OF 别名, ...] [NOWAIT | SKIP LOCKED]}。 */
    private static Clause lockClause(String mode, Lock lock, List<Entity> tables) {
        Objects.requireNonNull(lock, "lock");
        List<Ref<?>> refs = tables.stream().<Ref<?>>map(TableProxy::refOf).toList();
        return clause(Clause.Position.END, w -> {
            w.sql(mode);
            for (int i = 0; i < refs.size(); i++) w.sql(i == 0 ? " OF " : ", ").alias(refs.get(i).alias(w));
            if (lock == Lock.NOWAIT) w.sql(" NOWAIT");
            else if (lock == Lock.SKIP_LOCKED) w.sql(" SKIP LOCKED");
        });
    }

    /** 校验天数不为负。 */
    private static int nonNegative(int days) {
        if (days < 0) throw new IllegalArgumentException("Frame offsets must not be negative");
        return days;
    }

    // ---------- 统计 ----------

    /** 连续百分位数 {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)}，在相邻两行之间插值；p 取 0 到 1，中位数是 0.5。只能作聚合。 */
    public static Expr<Double> percentileCont(double p, Expr<? extends Number> e) {
        return Nodes.call(SqlType.of(Double.class), PERCENTILE_CONT, List.of(lit(fraction(p)), cast(e, CastType.doublePrecision())));
    }

    /** 离散百分位数 {@code PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)}：取排序后第一个累计比例不小于 p 的值。只能作聚合。 */
    public static <T> Expr<T> percentileDisc(double p, Expr<T> e) { return Nodes.call(e.type(), PERCENTILE_DISC, List.of(lit(fraction(p)), e)); }

    /** 众数 {@code MODE() WITHIN GROUP (ORDER BY e)}：出现次数最多的值，并列时取最小的。只能作聚合。 */
    public static <T> Expr<T> mode(Expr<T> e) { return Nodes.call(e.type(), MODE, List.of(e)); }

    /** 相关系数 {@code CORR(y, x)}，取值 -1 到 1。 */
    public static Expr<Double> corr(Expr<? extends Number> y, Expr<? extends Number> x) { return Nodes.call(SqlType.of(Double.class), CORR, List.of(y, x)); }

    /** 校验百分位数在 0 到 1 之间。 */
    private static double fraction(double p) {
        if (!(p >= 0 && p <= 1)) throw new IllegalArgumentException("A percentile must be between 0 and 1, got " + p);
        return p;
    }

    // ---------- 函数 ----------

    /** ILIKE：不区分大小写的 LIKE。skipEmpty 下模式为空时没有条件。 */
    public static Cond ilike(Expr<String> e, String pattern) {
        if (skipped(pattern)) return Cond.EMPTY;
        Expr<String> p = val(pattern, e.type());
        return cond(w -> w.render(e).sql(" ILIKE ").render(p));
    }

    /** 构造一个只属于 PostgreSQL 方言的子句，放在 {@code position} 指定的位置。 */
    private static Clause clause(Clause.Position position, Consumer<SqlWriter> body) {
        return new Clause() {
            @Override public Position position() { return position; }

            @Override public Class<? extends Dialect> dialect() { return PostgresDialect.class; }

            @Override public void render(SqlWriter w) { body.accept(w); }
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

    // ---------- 联表写入 ----------

    /** 联表更新：SET 和 WHERE 可以引用 joined 表，写成 UPDATE ... FROM；见 {@link JoinUpdate}。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> updateJoin(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
        return JoinUpdate.of(table, joined, on);
    }

    /** 联表删除：WHERE 可以引用 joined 表，写成 DELETE ... USING；见 {@link JoinDelete}。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> deleteJoin(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
        return JoinDelete.of(table, joined, on);
    }
}
