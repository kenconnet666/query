package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprNumber;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * SQLite 门面：包含 {@link Sql} 的全部能力，外加 SQLite 专属的函数。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.sqlite.Sqlite.*;}</pre>
 * 有主操作数的 SQLite 函数经由视图调用：{@code sqlite(l.text()).glob("A*")}、{@code sqlite(l.quantity()).median()}。
 * SQLite 专属函数在其他方言上渲染时会报错。核心里 SQLite 不支持的能力（ROLLUP、{@code grouping()}、{@code forUpdate()}）
 * 渲染时报错；需要编译选项的函数（数学函数、百分位数）第一次渲染时记一条警告。
 */
public final class Sqlite extends Sql {
    // 只能在 SQLite 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef MEDIAN = FunctionDef.only(SqliteDialect.class, "median", (w, args) -> extension(w, "MEDIAN", "median", args)).aggregating();
    private static final FunctionDef PERCENTILE_CONT = FunctionDef.only(SqliteDialect.class, "percentile_cont", (w, args) -> extension(w, "PERCENTILE_CONT", "percentile_cont", args)).aggregating();
    private static final FunctionDef PERCENTILE_DISC = FunctionDef.only(SqliteDialect.class, "percentile_disc", (w, args) -> extension(w, "PERCENTILE_DISC", "percentile_disc", args)).aggregating();

    /** 只供静态使用。 */
    private Sqlite() { }

    // ---------- 数据库实例 ----------

    /** 创建 SQLite 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static SqliteDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 SQLite 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static SqliteDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /**
     * 创建 SQLite 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。表声明里的 schema 是 ATTACH 进来的库名：
     * 要么在取连接时 ATTACH 同名的库，要么用 {@code configure} 的 {@code schema("APP", "main")} 映射到主库。
     */
    public static SqliteDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new SqliteDatabase(connections, configure);
    }

    // ---------- 视图：有主操作数的 SQLite 函数 ----------

    /** 文本上的 SQLite 函数：{@code sqlite(l.text()).glob("A*")}。 */
    public static <T> ViewText<T> sqlite(ExprText<T> e) { return new ViewText<>(e); }

    /** 数值上的 SQLite 函数：{@code sqlite(l.quantity()).median()}。 */
    public static <T> ViewNumber<T> sqlite(ExprNumber<T> e) { return new ViewNumber<>(e); }

    /** 文本上的 SQLite 函数，由 {@code sqlite(文本表达式)} 得到。 */
    public static final class ViewText<T> {
        /** 视图的主操作数。 */
        private final ExprText<T> e;

        ViewText(ExprText<T> e) { this.e = Objects.requireNonNull(e, "e"); }

        /**
         * 区分大小写的通配匹配 {@code this GLOB pattern}，写进当前的条件块：{@code *} 任意串、{@code ?} 一个字符、{@code [...]} 字符集。
         * SQLite 的 LIKE 不区分 ASCII 大小写，要区分时用它。skipEmpty 下模式为空时没有条件。
         */
        public void glob(String pattern) {
            if (skipped(pattern)) {
                Conditions.add(Cond.EMPTY);
                return;
            }
            Expr<String> p = val(pattern);
            Conditions.add(new Cond() {
                @Override public void render(SqlWriter w) {
                    requireSqlite(w, "GLOB");
                    w.render(e).sql(" GLOB ").render(p);
                }
            });
        }
    }

    /** 数值上的 SQLite 函数，由 {@code sqlite(数值表达式)} 得到。 */
    public static final class ViewNumber<T> {
        /** 视图的主操作数。 */
        private final ExprNumber<T> e;

        ViewNumber(ExprNumber<T> e) { this.e = Objects.requireNonNull(e, "e"); }

        /** 中位数 {@code median(this)}（聚合，需要 SQLITE_ENABLE_PERCENTILE，3.51 起）。 */
        public ExprDouble<Double> median() {
            return ExprDouble.of(Nodes.call(SqlType.of(Double.class), MEDIAN, List.of(e.cast(CastType.doublePrecision()))));
        }
    }

    // ---------- 百分位数 ----------

    /** 连续百分位数 {@code percentile_cont(e, p)}，p 取 0 到 1，是普通聚合（不是 WITHIN GROUP）；需要 SQLITE_ENABLE_PERCENTILE。 */
    public static ExprDouble<Double> percentileCont(double p, ExprNumber<?> e) {
        return ExprDouble.of(Nodes.call(SqlType.of(Double.class), PERCENTILE_CONT, List.of(e.cast(CastType.doublePrecision()), lit(fraction(p)))));
    }

    /** 离散百分位数 {@code percentile_disc(e, p)}：取实际存在的一个值，结果与 e 同一类型；规则同 {@link #percentileCont}。 */
    public static <T, F extends Expr<T>> F percentileDisc(double p, Family<T, F> e) {
        return e.retype(Nodes.call(e.type(), PERCENTILE_DISC, List.of(e, lit(fraction(p)))));
    }

    // ---------- 联表写入 ----------

    /** 联表更新：SET 和 WHERE 可以引用 joined 表，写成 UPDATE ... FROM；见 {@link JoinUpdate}。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> updateJoin(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        return JoinUpdate.of(table, joined, on);
    }

    /** 联表删除：WHERE 可以引用 joined 表，写成 WHERE EXISTS 子查询；见 {@link JoinDelete}。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> deleteJoin(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        return JoinDelete.of(table, joined, on);
    }

    // ---------- 内部 ----------

    /** 百分位数 p 必须在 0 到 1 之间。 */
    private static double fraction(double p) {
        if (!(p >= 0 && p <= 1)) throw new IllegalArgumentException("A percentile is between 0 and 1, got " + p);
        return p;
    }

    /** 需要编译选项的方言函数：第一次渲染时警告，再写成 {@code name(args)}。 */
    private static void extension(SqlWriter w, String key, String name, List<? extends org.dpdns.zerodep.query.dialect.Renderable> args) {
        ((SqliteDialect) w.dialect()).warnOnce(w, key);
        FunctionDef.call(w, name, args);
    }

    /** SQLite 专属的写法在别的方言上报错。 */
    private static void requireSqlite(SqlWriter w, String what) {
        if (!(w.dialect() instanceof SqliteDialect)) throw new UnsupportedOperationException(what + " is a SQLite operator; this database uses " + w.dialect().name());
    }
}
