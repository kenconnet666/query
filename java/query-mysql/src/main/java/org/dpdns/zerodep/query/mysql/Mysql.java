package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Expr;

import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * MySQL 门面：包含 {@link Sql} 的全部能力，外加 MySQL 专属的函数和子句。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.mysql.Mysql.*;}</pre>
 * MySQL 专属函数在其他方言上渲染时会报错。
 */
public final class Mysql extends Sql {
    // 只能在 MySQL 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef GREATEST = FunctionDef.only(MysqlDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(MysqlDialect.class, "LEAST");
    private static final FunctionDef DATE_FORMAT = FunctionDef.only(MysqlDialect.class, "DATE_FORMAT");

    /** 只放静态方法，不能实例化。 */
    private Mysql() { }

    // ---------- 数据库 ----------

    /** 用默认配置创建 MySQL 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static MysqlDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 MySQL 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static MysqlDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 创建 MySQL 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。 */
    public static MysqlDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new MysqlDatabase(connections, configure);
    }

    // ---------- 函数 ----------

    /** DATE_FORMAT(value, format)，例如 {@code dateFormat(date, "%Y%m%d")}。 */
    public static Expr<String> dateFormat(Expr<?> value, String format) { return call(DATE_FORMAT, String.class, value, lit(format)); }

    // ---------- 子句 ----------

    /** 优化器提示 {@code MAX_EXECUTION_TIME}：语句运行超过给定毫秒数后被中止。 */
    public static Clause maxExecutionTime(int milliseconds) {
        if (milliseconds < 1) throw new IllegalArgumentException("maxExecutionTime needs a positive number of milliseconds");
        String hint = "/*+ MAX_EXECUTION_TIME(" + milliseconds + ") */";
        return new Clause() {
            @Override public Position position() { return Position.HINT; }

            @Override public Class<? extends Dialect> dialect() { return MysqlDialect.class; }

            @Override public void render(SqlWriter w) { w.sql(hint); }
        };
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
