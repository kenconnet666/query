package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.DialectProvider;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.CaseOf;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.KeyList;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.expr.OrderList;
import org.dpdns.zerodep.query.expr.SqlCond;
import org.dpdns.zerodep.query.expr.SqlExpr;
import org.dpdns.zerodep.query.expr.SqlTemplate;
import org.dpdns.zerodep.query.expr.WindowFunction;
import org.dpdns.zerodep.query.expr.typed.ExprBigint;
import org.dpdns.zerodep.query.expr.typed.ExprBoolean;
import org.dpdns.zerodep.query.expr.typed.ExprDate;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprTimestamp;
import org.dpdns.zerodep.query.scope.AbstractFrom;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.Cols;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.Source;
import org.dpdns.zerodep.query.table.SqlTable;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.Delete;
import org.dpdns.zerodep.query.write.Insert;
import org.dpdns.zerodep.query.write.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * 核心入口，以及在各支持的数据库上语义一致、又没有主操作数的函数。方言门面（{@code Hana}、{@code Postgres}、{@code Mysql}）
 * 继承本类，所以静态导入选定的门面就同时得到核心函数和方言函数。
 *
 * <p>有主操作数的运算是表达式的实例方法：{@code l.unit().upper()}、{@code l.quantity().sum()}、{@code e.onDay().addDays(3)}，
 * 见 {@link Expr} 与各类型接口。这里只放来源、写入、条件块（{@link #any}、{@link #not}、{@link #exists} 等）、
 * 窗口函数、CASE、原生 SQL 等；按值的类型给出结果类型的 {@code val}、{@code lit}、{@code caseWhen} 在 {@link SqlValues}。
 */
public class Sql extends SqlValues {
    /** {@code a || b || ...}；没有这个运算符的方言注册自己的写法。 */
    public static final FunctionDef CONCAT = FunctionDef.of("CONCAT", (w, args) -> w.sql("(").join(args, " || ").sql(")"));

    /** {@code LOCATE(text, search)}；PostgreSQL 与 MySQL 注册自己的写法。 */
    public static final FunctionDef LOCATE = FunctionDef.of("LOCATE");

    /** {@code YEAR(d)}；PostgreSQL 注册 EXTRACT 写法，下面的 MONTH、DAYOFMONTH 相同。 */
    public static final FunctionDef YEAR = FunctionDef.of("YEAR");

    /** {@code MONTH(d)}。 */
    public static final FunctionDef MONTH = FunctionDef.of("MONTH");

    /** {@code DAYOFMONTH(d)}。 */
    public static final FunctionDef DAY = FunctionDef.of("DAYOFMONTH");

    /** {@code ADD_DAYS(d, n)}；PostgreSQL、MySQL 注册自己的写法，下面的 ADD_MONTHS、ADD_YEARS 相同。 */
    public static final FunctionDef ADD_DAYS = FunctionDef.of("ADD_DAYS");

    /** {@code ADD_MONTHS(d, n)}。 */
    public static final FunctionDef ADD_MONTHS = FunctionDef.of("ADD_MONTHS");

    /** {@code ADD_YEARS(d, n)}。 */
    public static final FunctionDef ADD_YEARS = FunctionDef.of("ADD_YEARS");

    /** {@code DAYS_BETWEEN(from, to)}；PostgreSQL 写成相减，MySQL 写成 DATEDIFF(to, from)。 */
    public static final FunctionDef DAYS_BETWEEN = FunctionDef.of("DAYS_BETWEEN");

    /** {@code SECONDS_BETWEEN(from, to)}；PostgreSQL 写成 EXTRACT(EPOCH ...)，MySQL 写成 TIMESTAMPDIFF(SECOND, from, to)。 */
    public static final FunctionDef SECONDS_BETWEEN = FunctionDef.of("SECONDS_BETWEEN");

    /** {@code HOUR(t)}；PostgreSQL 写成 EXTRACT，下面的 MINUTE 相同。 */
    public static final FunctionDef HOUR = FunctionDef.of("HOUR");

    /** {@code MINUTE(t)}。 */
    public static final FunctionDef MINUTE = FunctionDef.of("MINUTE");

    /** 秒的整数部分：HANA 的 SECOND 带小数，写成 {@code CAST(FLOOR(SECOND(t)) AS INTEGER)}；PostgreSQL、MySQL 注册自己的写法。 */
    public static final FunctionDef SECOND = FunctionDef.of("SECOND", (w, args) -> w.sql("CAST(FLOOR(SECOND(").render(args.get(0)).sql(")) AS INTEGER)"));

    /** {@code ADD_SECONDS(t, n)}；PostgreSQL 加 INTERVAL，MySQL 写成 TIMESTAMPADD。加小时、分钟也换算成秒用它。 */
    public static final FunctionDef ADD_SECONDS = FunctionDef.of("ADD_SECONDS");

    /** 当月第一天：{@code ADD_DAYS(d, 1 - DAYOFMONTH(d))}；PostgreSQL 写成 DATE_TRUNC，MySQL 写成 DATE_SUB。 */
    public static final FunctionDef MONTH_START = FunctionDef.of("MONTH_START", (w, args) ->
        w.sql("ADD_DAYS(").render(args.get(0)).sql(", 1 - DAYOFMONTH(").render(args.get(0)).sql("))"));

    /** {@code STRING_AGG(value, separator ORDER BY ...)}，参数依次是值、分隔符和排序项；MySQL 写成 GROUP_CONCAT。 */
    public static final FunctionDef STRING_AGG = FunctionDef.of("STRING_AGG", (w, args) -> {
        w.sql("STRING_AGG(").render(args.get(0)).sql(", ").render(args.get(1));
        if (args.size() > 2) w.sql(" ORDER BY ").join(args.subList(2, args.size()), ", ");
        w.sql(")");
    }).aggregating();

    /** 当前日期；SQLite 写成本地时区的 {@code date('now', 'localtime')}。 */
    public static final FunctionDef CURRENT_DATE = FunctionDef.of("CURRENT_DATE", (w, args) -> w.sql("CURRENT_DATE"));

    /** 当前时间戳（会话时区）；SQLite 写成本地时区的 {@code strftime(..., 'now', 'localtime')}。 */
    public static final FunctionDef CURRENT_TIMESTAMP = FunctionDef.of("CURRENT_TIMESTAMP", (w, args) -> w.sql("CURRENT_TIMESTAMP"));

    /** 只供方言门面继承，不能直接实例化。 */
    protected Sql() { }

    // ---------- 数据库实例 ----------

    /**
     * 通用的数据库实例：取一个连接读出数据库的产品名，交给类路径上认识它的方言包创建（见 {@link DialectProvider}）。
     * 只用本类里的通用写法的代码（例如建在本库上的流程引擎）因此不依赖具体的方言包；要用方言专属的写法时改用方言门面的
     * {@code database}。PolarDB 报告的产品名是 MySQL，得到的是 MySQL 方言的实例。
     */
    public static Database database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 通用的数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置；方言的选法同上。 */
    public static Database database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 通用的数据库实例，连接由 {@code connections} 提供（例如 Spring 适配器）；{@code configure} 可为 null，方言的选法同上。 */
    public static Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return DialectProvider.detect(connections, configure);
    }

    // ---------- 来源 ----------

    /** 从一张表开始查询：{@code from(Lips.class).where(l -> l.material().eq("M-1"))}，lambda 参数就是表接口。 */
    public static <T extends Entity> From1<T> from(Class<T> table) {
        Ref<T> ref = Ref.table(table);
        return From1.start(ref, ref.param());
    }

    /** 从 CTE、派生表或常量行开始查询；lambda 参数是 Ref，用 {@code r.col(Row::x)} 取列。 */
    public static <E> From1<Ref<E>> from(Source<E> source) {
        Ref<E> ref = Ref.of(source);
        return From1.start(ref, ref);
    }

    /** 命名的 CTE：body 要先映射到行类（{@code into(Row.class)}、{@code selectAs(Row.class)} 或 {@code values(...)}）。 */
    public static <E> Cte<E> cte(String name, Query<E> body) { return new Cte<>(name, body); }

    /**
     * 常量行，例如码表：每行渲染成一条字面量 SELECT，用 UNION ALL 连接（HANA 上加 {@code FROM DUMMY}）。
     * 可以做 CTE 或派生表；每个属性都要有值。
     */
    public static <V> Query<V> values(Class<V> shape, List<? extends V> rows) { return Query.constants(shape, rows); }

    // ---------- 写入 ----------

    /** 插入 table：{@code insertInto(Lips.class).values(rows)} 或 {@code .from(query)}，见 {@link Insert}。 */
    public static <T extends Entity> Insert<T> insertInto(Class<T> table) { return Insert.into(table); }

    /** 更新 table：{@code update(Lips.class).set(l -> l.unit().to("KG")).where(...)}，见 {@link Update}。 */
    public static <T extends Entity> Update<T> update(Class<T> table) { return Update.of(table); }

    /** 从 table 删除：{@code deleteFrom(Lips.class).where(...)}，见 {@link Delete}。 */
    public static <T extends Entity> Delete<T> deleteFrom(Class<T> table) { return Delete.of(table); }

    /**
     * 加锁读：{@code from(Task.class).where(...).with(forUpdate()).select(...)} 写成 {@code ... FOR UPDATE}，读到的行在事务结束前
     * 别人改不了；要在事务里执行（{@code db.transaction(...)} 或 Spring 的 {@code @Transactional}）。
     */
    public static Clause forUpdate() { return forUpdate(Lock.WAIT); }

    /** 同上，lock 指定遇到被锁住的行时不等待（NOWAIT）或跳过（SKIP_LOCKED，多个工作进程各自领取任务时用）。 */
    public static Clause forUpdate(Lock lock) {
        Objects.requireNonNull(lock, "lock");
        return new Clause() {
            @Override public Position position() { return Position.END; }

            @Override public Class<? extends Dialect> dialect() { return null; }

            @Override public void render(SqlWriter w) { w.dialect().lock(w, lock); }
        };
    }

    // ---------- 投影 ----------

    /**
     * 按列名选列：{@code select(l -> cols(l.material(), l.quantity().sum().as("total")))}，结果每行是 Map，
     * 再 {@code into(Row.class)} 按名映射到类。列名默认取列方法名，函数或聚合包一列时沿用该列的名字。
     */
    public static Cols cols(Expr<?>... columns) { return new Cols(List.of(columns)); }

    /** 在一组列（例如 {@code l.columns()}）后面再加几列。 */
    public static Cols cols(Cols first, Expr<?>... more) {
        List<Expr<?>> all = new ArrayList<>(first.items());
        all.addAll(List.of(more));
        return new Cols(all);
    }

    // ---------- 值 ----------

    /** 指定类型的绑定参数，例如带转换器的类型。 */
    public static <T> Expr<T> val(T value, SqlType<T> type) { return new Nodes.Param<>(type, Objects.requireNonNull(value, "value")); }

    /** 有类型的 NULL。 */
    public static <T> Expr<T> nullOf(Class<T> type) { return new Nodes.Raw<>(SqlType.of(type), "NULL"); }

    // ---------- 条件块 ----------

    /**
     * 任一成立（OR），块里一行一个选项：{@code any(() -> { l.unit().eq("EA"); l.unit().eq("KG"); })}，写进当前的条件块。
     * 一个选项要由几个条件同时成立时，用 {@link #all} 把它们合成一项。块里的条件都被 skipEmpty 省略时没有条件。
     */
    public static void any(Runnable options) { Conditions.add(Nodes.any(Conditions.list(options))); }

    /** 全部成立（AND）合成一项，用在 {@link #any} 的块里：{@code any(() -> { all(() -> { a; b; }); c; })} 写成 {@code (a AND b) OR c}。 */
    public static void all(Runnable conditions) { Conditions.add(Conditions.collect(conditions)); }

    /** 取反：{@code NOT (块里条件的 AND)}，写进当前的条件块；块里没有条件时没有条件。 */
    public static void not(Runnable conditions) { Conditions.add(Nodes.not(Conditions.collect(conditions))); }

    /** EXISTS (子查询)，写进当前的条件块。 */
    public static void exists(Query<?> subquery) { Conditions.add(Nodes.exists(subquery, false)); }

    /** EXISTS，直接接收作用域，不用写 SELECT 列表：{@code exists(from(Vbkd.class).where(v -> ...))}。 */
    public static void exists(AbstractFrom<?> scope) { exists(scope.selectOne()); }

    /** NOT EXISTS (子查询)，写进当前的条件块。 */
    public static void notExists(Query<?> subquery) { Conditions.add(Nodes.exists(subquery, true)); }

    /** NOT EXISTS，直接接收作用域。 */
    public static void notExists(AbstractFrom<?> scope) { notExists(scope.selectOne()); }

    /**
     * 原生 SQL 写成的条件，写进当前的条件块：{0}、{1} 是参数，表达式原位渲染，其他值绑定，集合展开成多个参数，
     * 例如 {@code sqlCond("{0} IN ({1})", l.unit(), units)}；接着写 {@code .on(方言类, 模板)} 给某个方言另一种写法。
     */
    public static SqlCond sqlCond(String template, Object... args) {
        SqlCond condition = new SqlCond(SqlTemplate.of(template, args));
        Conditions.add(condition);
        return condition;
    }

    /** 条件作为布尔值：{@code cols(bool(() -> l.quantity().gt(BigDecimal.ZERO)).as("positive"))}；条件未知时为 NULL，块里没有条件时报错。 */
    public static ExprBoolean<Boolean> bool(Runnable condition) { return ExprBoolean.of(new Nodes.Truth(Conditions.collect(condition))); }

    /** 供方言门面的比较函数使用：在 skipEmpty 模式下，这个值上的条件是否应当省略。 */
    protected static boolean skipped(Object value) { return SkipEmpty.skips(value); }

    // ---------- 聚合 ----------

    /** COUNT(*)；某一列的个数写 {@code l.material().count()}。 */
    public static ExprBigint<Long> count() { return ExprBigint.of(new Aggregate<>(SqlType.of(Long.class), "COUNT", false, List.of())); }

    /** groupByRollup 的汇总行上，被汇总掉的键为 1，其余为 0。 */
    public static ExprInteger<Integer> grouping(Expr<?> key) { return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), "GROUPING", List.of(key))); }

    // ---------- 窗口函数 ----------

    /** ROW_NUMBER()，再加 .over(...)。 */
    public static WindowFunction<Long, ExprBigint<Long>> rowNumber() { return ranking("ROW_NUMBER"); }

    /** RANK()，再加 .over(...)。 */
    public static WindowFunction<Long, ExprBigint<Long>> rank() { return ranking("RANK"); }

    /** DENSE_RANK()，再加 .over(...)。 */
    public static WindowFunction<Long, ExprBigint<Long>> denseRank() { return ranking("DENSE_RANK"); }

    /** 相对排名 {@code (rank - 1) / (行数 - 1)}，取值 0 到 1；再加 .over(...)。 */
    public static WindowFunction<Double, ExprDouble<Double>> percentRank() {
        return new WindowFunction<>(SqlType.of(Double.class), "PERCENT_RANK", List.of(), ExprDouble::of);
    }

    /** 累计分布：不大于当前行的行数占分区行数的比例，取值大于 0、不超过 1；再加 .over(...)。 */
    public static WindowFunction<Double, ExprDouble<Double>> cumeDist() {
        return new WindowFunction<>(SqlType.of(Double.class), "CUME_DIST", List.of(), ExprDouble::of);
    }

    /** 按窗口的顺序把分区分成 buckets 组，给出组号（1 起），例如四分位；再加 .over(...)。 */
    public static WindowFunction<Long, ExprBigint<Long>> ntile(int buckets) {
        if (buckets < 1) throw new IllegalArgumentException("ntile needs at least one bucket");
        return new WindowFunction<>(SqlType.of(Long.class), "NTILE", List.of(Nodes.literal(Integer.class, buckets)), ExprBigint::of);
    }

    /** 前 offset 行的值，再加 .over(...)；结果与 e 同一类型，例如 {@code lag(l.quantity(), 1).over(...)} 是 {@code ExprDecimal}。 */
    public static <T, F extends Expr<T>> WindowFunction<T, F> lag(Family<T, F> e, int offset) { return valueAt("LAG", e, e::retype, offset(offset)); }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> lag(Expr<T> e, int offset) { return valueAt("LAG", e, x -> x, offset(offset)); }

    /** 前 offset 行的值，越过分区开头时取 fallback，再加 .over(...)。 */
    public static <T, F extends Expr<T>> WindowFunction<T, F> lag(Family<T, F> e, int offset, T fallback) {
        return valueAt("LAG", e, e::retype, offset(offset), e.param(fallback));
    }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> lag(Expr<T> e, int offset, T fallback) { return valueAt("LAG", e, x -> x, offset(offset), e.param(fallback)); }

    /** 后 offset 行的值，再加 .over(...)；结果与 e 同一类型。 */
    public static <T, F extends Expr<T>> WindowFunction<T, F> lead(Family<T, F> e, int offset) { return valueAt("LEAD", e, e::retype, offset(offset)); }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> lead(Expr<T> e, int offset) { return valueAt("LEAD", e, x -> x, offset(offset)); }

    /** 后 offset 行的值，越过分区末尾时取 fallback，再加 .over(...)。 */
    public static <T, F extends Expr<T>> WindowFunction<T, F> lead(Family<T, F> e, int offset, T fallback) {
        return valueAt("LEAD", e, e::retype, offset(offset), e.param(fallback));
    }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> lead(Expr<T> e, int offset, T fallback) { return valueAt("LEAD", e, x -> x, offset(offset), e.param(fallback)); }

    /** 窗口里第一行的值，再加 .over(...)；结果与 e 同一类型。 */
    public static <T, F extends Expr<T>> WindowFunction<T, F> firstValue(Family<T, F> e) { return valueAt("FIRST_VALUE", e, e::retype); }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> firstValue(Expr<T> e) { return valueAt("FIRST_VALUE", e, x -> x); }

    /**
     * 窗口里最后一行的值，再加 .over(...)；结果与 e 同一类型。有 ORDER BY 时默认窗口只到当前行，要取整个分区的最后一行需写
     * {@code w.rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())}。
     */
    public static <T, F extends Expr<T>> WindowFunction<T, F> lastValue(Family<T, F> e) { return valueAt("LAST_VALUE", e, e::retype); }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> lastValue(Expr<T> e) { return valueAt("LAST_VALUE", e, x -> x); }

    /**
     * 窗口里第 n 行（1 起）的值，没有时为 NULL；再加 .over(...)。与 {@link #lastValue} 一样，有 ORDER BY 时默认窗口只到当前行，
     * 要在整个分区里取需写 {@code w.rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())}。
     */
    public static <T, F extends Expr<T>> WindowFunction<T, F> nthValue(Family<T, F> e, int n) { return valueAt("NTH_VALUE", e, e::retype, nth(n)); }

    /** 同上，e 没有专门的类型接口时结果是 {@code Expr<T>}。 */
    public static <T> WindowFunction<T, Expr<T>> nthValue(Expr<T> e, int n) { return valueAt("NTH_VALUE", e, x -> x, nth(n)); }

    /** 结果是行号一类整数的窗口函数。 */
    private static WindowFunction<Long, ExprBigint<Long>> ranking(String name) {
        return new WindowFunction<>(SqlType.of(Long.class), name, List.of(), ExprBigint::of);
    }

    /** 取某一行的值的窗口函数，结果与 e 同一类型；more 是 e 之后的参数。 */
    private static <T, F extends Expr<T>> WindowFunction<T, F> valueAt(String name, Expr<T> e, Function<Expr<T>, F> family, Expr<?>... more) {
        List<Expr<?>> args = new ArrayList<>();
        args.add(e);
        args.addAll(List.of(more));
        return new WindowFunction<>(e.type(), name, args, family);
    }

    /** LAG、LEAD 的行数，写成字面量。 */
    private static Expr<Integer> offset(int offset) { return Nodes.literal(Integer.class, offset); }

    /** NTH_VALUE 的行号，从 1 开始。 */
    private static Expr<Integer> nth(int n) {
        if (n < 1) throw new IllegalArgumentException("nthValue counts rows from 1");
        return Nodes.literal(Integer.class, n);
    }

    // ---------- CASE、原生 SQL、函数、子查询 ----------

    /** 简单 CASE：{@code caseOf(code).when("1", "自有").when("2", "供应商").otherwise("其他")}，适合内联的码表。 */
    public static <V> CaseOf<V> caseOf(Expr<V> subject) { return new CaseOf<>(subject); }

    /** 把 first 和 rest 拼成参数数组，供 GREATEST 这类至少两个参数的方言函数使用。 */
    @SafeVarargs
    protected static <T> Expr<?>[] twoOrMore(String function, Expr<T> first, Expr<? extends T>... rest) {
        if (rest.length == 0) throw new IllegalArgumentException(function + " needs at least two arguments");
        Expr<?>[] all = new Expr<?>[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /**
     * 原生 SQL 写成的表达式，结果类型由 type 声明：{0}、{1} 是参数，表达式原位渲染，其他值绑定（SELECT 列表里写成字面量），
     * 集合展开成多个参数，例如 {@code sql(String.class, "LPAD({0}, {1}, '0')", l.material(), 18)}；
     * 用 {@code .on(方言类, 模板)} 给某个方言另一种写法。要用某种类型的方法时包一层，例如 {@code ExprText.of(sql(...))}。
     */
    public static <T> SqlExpr<T> sql(Class<T> type, String template, Object... args) {
        return new SqlExpr<>(SqlType.of(type), SqlTemplate.of(template, args));
    }

    /** 按方言加引号的标识符（列名、表名、别名），放进原生 SQL 的参数，让同一段模板在三个库上都能用。 */
    public static Renderable identifier(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("identifier needs a name");
        return w -> w.identifier(name);
    }

    /**
     * 原生 SQL 写成的数据源，放进 FROM 或 JOIN：{@code from(sqlTable(StockRow.class, "SELECT ... WHERE WERKS = {0}", plant))}。
     * 每一列用 rows 的属性名作别名并按方言加引号，参数规则同 {@link #sql}。
     */
    public static <E> SqlTable<E> sqlTable(Class<E> rows, String template, Object... args) {
        return new SqlTable<>(rows, SqlTemplate.of(template, args));
    }

    /** 调用一个函数定义；内置函数与自定义函数都这样实现。 */
    public static <T> Expr<T> call(FunctionDef function, Class<T> type, Expr<?>... args) {
        return Nodes.call(SqlType.of(type), function, List.of(args));
    }

    /** 同上，结果类型是 SqlType（例如带转换器的类型）。 */
    public static <T> Expr<T> call(FunctionDef function, SqlType<T> type, Expr<?>... args) {
        return Nodes.call(type, function, List.of(args));
    }

    /** 单列查询作为一个值（标量子查询）；要用某种类型的方法时包一层，例如 {@code ExprDecimal.of(scalar(q))}。 */
    public static <T> Expr<T> scalar(Query<T> subquery) { return new Nodes.Scalar<>(subquery); }

    /** 与子查询的任一行比较：{@code x.eq(anyOf(q))} 写成 {@code x = ANY (子查询)}；子查询没有行时比较为假。 */
    public static <T> Expr<T> anyOf(Query<T> subquery) { return new Nodes.Quantified<>("ANY", subquery); }

    /** 与子查询的每一行比较：{@code x.gt(allOf(q))} 写成 {@code x > ALL (子查询)}；子查询没有行时比较为真。 */
    public static <T> Expr<T> allOf(Query<T> subquery) { return new Nodes.Quantified<>("ALL", subquery); }

    /** 当前日期。 */
    public static ExprDate<LocalDate> currentDate() { return ExprDate.of(Nodes.call(SqlType.of(LocalDate.class), CURRENT_DATE, List.of())); }

    /** 当前时间戳。 */
    public static ExprTimestamp<LocalDateTime> currentTimestamp() {
        return ExprTimestamp.of(Nodes.call(SqlType.of(LocalDateTime.class), CURRENT_TIMESTAMP, List.of()));
    }

    // ---------- 一个 lambda 返回多个键或排序项 ----------

    /** 多个 GROUP BY 键；只有一个键时直接写表达式。 */
    public static KeyList keys(Expr<?>... exprs) { return new KeyList(List.of(exprs)); }

    /** 多个 ORDER BY 项；裸表达式按升序。 */
    public static OrderList orders(OrderItem... items) { return new OrderList(OrderList.of(items)); }
}
