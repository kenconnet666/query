package org.dpdns.zerodep.query.mysql;

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
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.TableProxy;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * MySQL 门面：包含 {@link Sql} 的全部能力，外加 MySQL 专属的函数和子句。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.mysql.Mysql.*;}</pre>
 * MySQL 专属函数在其他方言上渲染时会报错。
 */
public class Mysql extends Sql {
    // 只能在 MySQL 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef GREATEST = FunctionDef.only(MysqlDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(MysqlDialect.class, "LEAST");
    private static final FunctionDef DATE_FORMAT = FunctionDef.only(MysqlDialect.class, "DATE_FORMAT");

    // ---------- 集合运算、窗口帧、递归 CTE、加锁 ----------

    /** INTERSECT ALL：两边都有的行，保留重复（8.0.31 起）。规则同 {@code q1.unionAll(q2)}。 */
    public static <R> Query<R> intersectAll(Query<R> left, Query<R> right) { return left.combine("INTERSECT ALL", right); }

    /** EXCEPT ALL：左边有、右边没有的行，按次数相减（8.0.31 起）。规则同 {@code q1.unionAll(q2)}。 */
    public static <R> Query<R> exceptAll(Query<R> left, Query<R> right) { return left.combine("EXCEPT ALL", right); }

    /**
     * RANGE 窗口帧：按排序值的距离取行，排序值相同的行一起进出，例如近 7 天的滚动合计
     * {@code over(w -> w.orderBy(l.createdOn()).frame(range(daysPreceding(6), Bound.currentRow())))}。
     * 有限的偏移要求窗口只有一个排序项；数值偏移用 {@code Bound.preceding(n)}。
     */
    public static Renderable range(Window.Bound start, Window.Bound end) {
        String sql = "RANGE BETWEEN " + start.sql() + " AND " + end.sql();
        return w -> {
            requireMysql(w);
            w.sql(sql);
        };
    }

    /** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
    public static Window.Bound daysPreceding(int days) { return Window.Bound.custom("INTERVAL " + nonNegative(days) + " DAY PRECEDING"); }

    /** RANGE 帧的终点：排序值之后 days 天。 */
    public static Window.Bound daysFollowing(int days) { return Window.Bound.custom("INTERVAL " + nonNegative(days) + " DAY FOLLOWING"); }

    /**
     * 递归 CTE：{@code anchor UNION ALL step(自己)}，step 里 {@code from(self)} 取上一轮的行；写法见 PostgreSQL 方言包的同名方法。
     * 递归层数受 {@code cte_max_recursion_depth}（默认 1000）限制。
     */
    public static <E> Cte<E> recursiveCte(String name, Query<E> anchor, Function<Cte<E>, Query<E>> step) {
        return Cte.recursive(name, anchor, step, MysqlDialect.class);
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

    /** 渲染目标不是 MySQL 时抛出异常。 */
    private static void requireMysql(SqlWriter w) {
        if (!(w.dialect() instanceof MysqlDialect)) throw new UnsupportedOperationException("A MySQL-only operation cannot run on " + w.dialect().name());
    }

    /** 构造一个只属于 MySQL 方言的子句，放在 {@code position} 指定的位置。 */
    private static Clause clause(Clause.Position position, Consumer<SqlWriter> body) {
        return new Clause() {
            @Override public Position position() { return position; }

            @Override public Class<? extends Dialect> dialect() { return MysqlDialect.class; }

            @Override public void render(SqlWriter w) { body.accept(w); }
        };
    }

    /** 只放静态方法，不能实例化；兼容 MySQL 的方言门面（PolarDB）继承它，一次静态导入拿到 MySQL 的全部词汇。 */
    protected Mysql() { }

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
        String hint = "MAX_EXECUTION_TIME(" + milliseconds + ")";
        return new Hint(w -> w.sql(hint));
    }

    /** 优化器提示 {@code INDEX(别名 索引, ...)}：只考虑这些索引；table 是查询的表参数。与其他提示合并进同一个注释。 */
    public static Clause index(Entity table, String... indexes) { return indexHint("INDEX", table, indexes); }

    /** 优化器提示 {@code NO_INDEX(别名 索引, ...)}：不用这些索引；不写索引时不用这张表的任何索引。 */
    public static Clause noIndex(Entity table, String... indexes) { return indexHint("NO_INDEX", table, indexes); }

    /** {@code NAME(别名 [索引, ...])}。 */
    private static Clause indexHint(String name, Entity table, String... indexes) {
        Ref<?> ref = TableProxy.refOf(table);
        List<String> list = List.of(indexes);
        return new Hint(w -> {
            w.sql(name).sql("(").alias(ref.alias(w));
            for (int i = 0; i < list.size(); i++) w.sql(i == 0 ? " " : ", ").identifier(list.get(i));
            w.sql(")");
        });
    }

    /**
     * 一个优化器提示：MySQL 只认 SELECT 后面第一个 {@code /*+ ... *\/} 注释，所以同一段查询的提示由方言合并进一个注释。
     * 由 {@link #maxExecutionTime}、{@link #index} 等创建。
     */
    public static final class Hint implements Clause {
        private final Consumer<SqlWriter> body;

        private Hint(Consumer<SqlWriter> body) { this.body = body; }

        /** 只写提示本身，不带注释符号。 */
        void renderBody(SqlWriter w) { body.accept(w); }

        @Override public Position position() { return Position.HINT; }

        @Override public Class<? extends Dialect> dialect() { return MysqlDialect.class; }

        /** 单独写出时带上注释符号；同一段里有多个提示时由方言合并。 */
        @Override public void render(SqlWriter w) {
            w.sql("/*+ ");
            body.accept(w);
            w.sql(" */");
        }
    }

    // ---------- JSON ----------

    /**
     * JSON 文档里一个路径上的标量文本：{@code JSON_UNQUOTE(JSON_EXTRACT(doc, ?))}，路径如 {@code "$.order.id"} 绑定成参数；
     * 路径不存在、是 JSON null、对象或数组时为 NULL。
     */
    public static Expr<String> jsonText(Expr<String> document, String path) {
        Expr<String> p = utf8(Objects.requireNonNull(path, "path"));
        return new Expr<>(SqlType.of(String.class)) {
            @Override public void render(SqlWriter w) {
                requireMysql(w);
                w.sql("CASE WHEN JSON_TYPE(JSON_EXTRACT(").render(document).sql(", ").render(p).sql(")) IN ('OBJECT', 'ARRAY', 'NULL') THEN NULL ELSE JSON_UNQUOTE(JSON_EXTRACT(")
                    .render(document).sql(", ").render(p).sql(")) END");
            }
        };
    }

    /** JSON 文档包含 json 这段内容 {@code JSON_CONTAINS(doc, CAST(? AS JSON))}，例如 {@code jsonContains(t.payload(), "{\"status\":\"open\"}")}。 */
    public static Cond jsonContains(Expr<String> document, String json) {
        if (SkipEmpty.skips(json)) return Cond.EMPTY;
        Expr<String> value = utf8(json);
        return cond(w -> w.sql("JSON_CONTAINS(").render(document).sql(", CAST(").render(value).sql(" AS JSON))"));
    }

    /**
     * 绑定成 utf8mb4 文本的参数 {@code CONVERT(? USING utf8mb4)}：Connector/J 在客户端预处理且开着 NO_BACKSLASH_ESCAPES 时
     * 会把字符串绑成二进制，JSON 函数遇到二进制会报错（前身仓库实测）。
     */
    private static Expr<String> utf8(String text) {
        Expr<String> value = val(text);
        return new Expr<>(SqlType.of(String.class)) {
            @Override public void render(SqlWriter w) { w.sql("CONVERT(").render(value).sql(" USING utf8mb4)"); }
        };
    }

    // ---------- 全文检索 ----------

    /**
     * 全文检索 {@code MATCH (a, b) AGAINST (? IN NATURAL LANGUAGE MODE)}；列必须正好是某个 FULLTEXT 索引的列。
     * InnoDB 的全文索引在提交时才更新，同一个事务里刚插入的行搜不到。skipEmpty 下搜索词为空时没有条件。
     */
    @SafeVarargs
    public static Cond match(String query, Expr<String>... columns) { return fulltext("NATURAL LANGUAGE MODE", query, columns); }

    /** 布尔模式的全文检索 {@code AGAINST (? IN BOOLEAN MODE)}：{@code +必须 -排除 "短语" 前缀*}。 */
    @SafeVarargs
    public static Cond matchBoolean(String query, Expr<String>... columns) { return fulltext("BOOLEAN MODE", query, columns); }

    /** 自然语言模式的相关度，用来排序；不能跨库比较。 */
    @SafeVarargs
    public static Expr<Double> matchScore(String query, Expr<String>... columns) {
        Cond c = fulltext("NATURAL LANGUAGE MODE", Objects.requireNonNull(query, "query"), columns);
        return new Expr<>(SqlType.of(Double.class)) {
            @Override public void render(SqlWriter w) { w.render(c); }
        };
    }

    /** {@code MATCH (...) AGAINST (? IN mode)}。 */
    private static Cond fulltext(String mode, String query, Expr<String>[] columns) {
        if (columns.length == 0) throw new IllegalArgumentException("match(...) needs at least one column");
        if (SkipEmpty.skips(query)) return Cond.EMPTY;
        Expr<String> q = val(query);
        List<Expr<String>> list = List.of(columns);
        return cond(w -> w.sql("MATCH (").join(list, ", ").sql(") AGAINST (").render(q).sql(" IN ").sql(mode).sql(")"));
    }

    /** MySQL 专属的条件；在其他方言上渲染会明确报错。 */
    private static Cond cond(Consumer<SqlWriter> body) {
        return new Cond() {
            @Override public void render(SqlWriter w) {
                requireMysql(w);
                body.accept(w);
            }
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

    // ---------- 联表写入 ----------

    /** 联表更新：SET 和 WHERE 可以引用 joined 表，写成 UPDATE ... JOIN ... SET；见 {@link JoinUpdate}。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> updateJoin(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
        return JoinUpdate.of(table, joined, on);
    }

    /** 联表删除：WHERE 可以引用 joined 表，写成 DELETE 别名 FROM ... JOIN；见 {@link JoinDelete}。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> deleteJoin(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
        return JoinDelete.of(table, joined, on);
    }
}
