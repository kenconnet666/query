package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * SQLite 门面：包含 {@link Sql} 的全部能力，外加 SQLite 专属的函数。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.sqlite.Sqlite.*;}</pre>
 * SQLite 专属函数在其他方言上渲染时会报错。核心里 SQLite 不支持的能力（ROLLUP、{@code grouping()}、{@code forUpdate()}）
 * 渲染时报错；需要编译选项的函数（数学函数、百分位数）第一次渲染时记一条警告。
 */
public final class Sqlite extends Sql {
    // 只能在 SQLite 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef MEDIAN = FunctionDef.only(SqliteDialect.class, "median", (w, args) -> extension(w, "MEDIAN", "median", args));
    private static final FunctionDef PERCENTILE_CONT = FunctionDef.only(SqliteDialect.class, "percentile_cont", (w, args) -> extension(w, "PERCENTILE_CONT", "percentile_cont", args));
    private static final FunctionDef PERCENTILE_DISC = FunctionDef.only(SqliteDialect.class, "percentile_disc", (w, args) -> extension(w, "PERCENTILE_DISC", "percentile_disc", args));

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

    // ---------- 函数 ----------

    /**
     * 区分大小写的通配匹配 {@code e GLOB pattern}：{@code *} 任意串、{@code ?} 一个字符、{@code [...]} 字符集。
     * SQLite 的 LIKE 不区分 ASCII 大小写，要区分时用它。pattern 为 null 时按 skipEmpty 的规则处理。
     */
    public static Cond glob(Expr<String> e, String pattern) {
        Expr<String> p = val(pattern);
        return new Cond() {
            @Override public void render(SqlWriter w) {
                requireSqlite(w, "GLOB");
                w.render(e).sql(" GLOB ").render(p);
            }
        };
    }

    /** 中位数 {@code median(e)}（需要 SQLITE_ENABLE_PERCENTILE，3.51 起）。 */
    public static Expr<Double> median(Expr<? extends Number> e) {
        return Nodes.call(SqlType.of(Double.class), MEDIAN, List.of(cast(e, CastType.doublePrecision())));
    }

    /** 连续百分位数 {@code percentile_cont(e, p)}，p 取 0 到 1，是普通聚合（不是 WITHIN GROUP）；需要 SQLITE_ENABLE_PERCENTILE。 */
    public static Expr<Double> percentileCont(double p, Expr<? extends Number> e) {
        return Nodes.call(SqlType.of(Double.class), PERCENTILE_CONT, List.of(cast(e, CastType.doublePrecision()), lit(fraction(p))));
    }

    /** 离散百分位数 {@code percentile_disc(e, p)}：取实际存在的一个值；规则同 {@link #percentileCont}。 */
    public static <T> Expr<T> percentileDisc(double p, Expr<T> e) {
        return Nodes.call(e.type(), PERCENTILE_DISC, List.of(e, lit(fraction(p))));
    }

    // ---------- 联表写入 ----------

    /** 联表更新：SET 和 WHERE 可以引用 joined 表，写成 UPDATE ... FROM；见 {@link JoinUpdate}。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> updateJoin(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
        return JoinUpdate.of(table, joined, on);
    }

    /** 联表删除：WHERE 可以引用 joined 表，写成 WHERE EXISTS 子查询；见 {@link JoinDelete}。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> deleteJoin(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
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
        ((SqliteDialect) w.dialect()).warnOnce(key);
        FunctionDef.call(w, name, args);
    }

    /** SQLite 专属的写法在别的方言上报错。 */
    private static void requireSqlite(SqlWriter w, String what) {
        if (!(w.dialect() instanceof SqliteDialect)) throw new UnsupportedOperationException(what + " is a SQLite operator; this database uses " + w.dialect().name());
    }
}
