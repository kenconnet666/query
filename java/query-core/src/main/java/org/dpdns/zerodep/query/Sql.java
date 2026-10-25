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
import org.dpdns.zerodep.query.expr.Case;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.KeyList;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.SqlCond;
import org.dpdns.zerodep.query.expr.SqlExpr;
import org.dpdns.zerodep.query.expr.SqlTemplate;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.expr.OrderList;
import org.dpdns.zerodep.query.expr.WindowFunction;
import org.dpdns.zerodep.query.scope.AbstractFrom;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.Cols;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.Rows;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.Source;
import org.dpdns.zerodep.query.table.SqlTable;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.Delete;
import org.dpdns.zerodep.query.write.Insert;
import org.dpdns.zerodep.query.write.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * 核心入口，以及在各支持的数据库上语义一致的函数。方言门面（{@code Hana}、{@code Postgres}、{@code Mysql}）
 * 继承本类，所以静态导入选定的门面就同时得到核心函数和方言函数。
 *
 * <p>这里的泛型函数只有一个泛型操作数；操作数类型必须相同的运算是 {@link Expr} 的实例方法（原因见它的说明）。
 */
public class Sql extends Rows {
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
    });

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
     * 按列名选列：{@code select(l -> cols(l.material(), sum(l.quantity()).as("total")))}，结果每行是 Map，
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

    /** 绑定参数，类型由值的类决定；在 SELECT 列表等位置会写成字面量。默认用它。 */
    @SuppressWarnings("unchecked")
    public static <T> Expr<T> val(T value) {
        Objects.requireNonNull(value, "value; use nullOf(Type.class) for NULL");
        return new Nodes.Param<>((SqlType<T>) SqlType.of(value.getClass()), value);
    }

    /** 指定类型的绑定参数，例如带转换器的类型。 */
    public static <T> Expr<T> val(T value, SqlType<T> type) { return new Nodes.Param<>(type, Objects.requireNonNull(value, "value")); }

    /** 写进 SQL 文本的字面量；只用于代码里的固定常量，绝不用于用户输入。 */
    @SuppressWarnings("unchecked")
    public static <T> Expr<T> lit(T value) {
        Objects.requireNonNull(value, "value; use nullOf(Type.class) for NULL");
        return new Nodes.Literal<>((SqlType<T>) SqlType.of(value.getClass()), value);
    }

    /** 有类型的 NULL。 */
    public static <T> Expr<T> nullOf(Class<T> type) { return new Nodes.Raw<>(SqlType.of(type), "NULL"); }

    // ---------- 条件 ----------

    /** 全部成立（AND）；空条件被丢弃，全部为空时没有条件。 */
    public static Cond all(Cond... conds) { return Nodes.all(Arrays.asList(conds)); }

    /** 全部成立（AND），条件来自列表。 */
    public static Cond all(List<Cond> conds) { return Nodes.all(conds); }

    /** 任一成立（OR）；空条件被丢弃。 */
    public static Cond any(Cond... conds) { return Nodes.any(Arrays.asList(conds)); }

    /** 任一成立（OR），条件来自列表。 */
    public static Cond any(List<Cond> conds) { return Nodes.any(conds); }

    /** 取反。 */
    public static Cond not(Cond cond) { return Nodes.not(cond); }

    /** test 成立时才加这个条件，否则没有条件：{@code onlyIf(req.onlyOpen, () -> h.status().eq("A"))}。 */
    public static Cond onlyIf(boolean test, Supplier<Cond> cond) { return test ? cond.get() : Cond.EMPTY; }

    /** value 不为 null 时用它构造条件，否则没有条件。 */
    public static <T> Cond ifPresent(T value, Function<? super T, Cond> cond) { return value == null ? Cond.EMPTY : cond.apply(value); }

    /** EXISTS (子查询)。 */
    public static Cond exists(Query<?> subquery) { return Nodes.exists(subquery, false); }

    /** EXISTS，直接接收作用域，不用写 SELECT 列表：{@code exists(from(Vbkd.class).where(v -> ...))}。 */
    public static Cond exists(AbstractFrom<?> scope) { return exists(scope.selectOne()); }

    /** NOT EXISTS (子查询)。 */
    public static Cond notExists(Query<?> subquery) { return Nodes.exists(subquery, true); }

    /** NOT EXISTS，直接接收作用域。 */
    public static Cond notExists(AbstractFrom<?> scope) { return notExists(scope.selectOne()); }

    /** LIKE；模式原样使用，{@code %} 和 {@code _} 是通配符。skipEmpty 下模式为空时没有条件。 */
    public static Cond like(Expr<String> e, String pattern) {
        if (skipped(pattern)) return Cond.EMPTY;
        return Nodes.compare(e, "LIKE", e.param(pattern));
    }

    /** NOT LIKE；skipEmpty 下模式为空时没有条件。 */
    public static Cond notLike(Expr<String> e, String pattern) {
        if (skipped(pattern)) return Cond.EMPTY;
        return Nodes.compare(e, "NOT LIKE", e.param(pattern));
    }

    /** 文本包含 text：{@code LIKE '%text%'}，text 里的 % 和 _ 按普通字符匹配。skipEmpty 下 text 为空时没有条件。 */
    public static Cond contains(Expr<String> e, String text) { return likeLiteral(e, "%", text, "%"); }

    /** 文本以 text 开头，规则同 {@link #contains}。 */
    public static Cond startsWith(Expr<String> e, String text) { return likeLiteral(e, "", text, "%"); }

    /** 文本以 text 结尾，规则同 {@link #contains}。 */
    public static Cond endsWith(Expr<String> e, String text) { return likeLiteral(e, "%", text, ""); }

    /** prefix + 转义后的 text + suffix 作为 LIKE 模式；用 ! 作转义符，三个库的写法相同。 */
    private static Cond likeLiteral(Expr<String> e, String prefix, String text, String suffix) {
        if (skipped(text)) return Cond.EMPTY;
        if (text == null) e.param(null);  // 与其他比较一样报错：与 NULL 比较永远不成立
        String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return Nodes.template("{0} LIKE {1} ESCAPE '!'", List.of(e, e.param(prefix + escaped + suffix)));
    }

    /**
     * 原生 SQL 写成的条件：{0}、{1} 是参数，表达式原位渲染，其他值绑定，集合展开成多个参数，
     * 例如 {@code sqlCond("{0} IN ({1})", l.unit(), units)}；用 {@code .on(方言类, 模板)} 给某个方言另一种写法。
     */
    public static SqlCond sqlCond(String template, Object... args) { return new SqlCond(SqlTemplate.of(template, args)); }

    /** 供方言门面的比较函数使用：在 skipEmpty 模式下，这个值上的条件是否应当省略。 */
    protected static boolean skipped(Object value) { return SkipEmpty.skips(value); }

    // ---------- 文本 ----------

    /** 转大写。 */
    public static Expr<String> upper(Expr<String> e) { return Nodes.call(e.type(), "UPPER", List.of(e)); }

    /** 转小写。 */
    public static Expr<String> lower(Expr<String> e) { return Nodes.call(e.type(), "LOWER", List.of(e)); }

    /** 去掉首尾空格。 */
    public static Expr<String> trim(Expr<String> e) { return Nodes.call(e.type(), "TRIM", List.of(e)); }

    /** SUBSTRING(e, start, length)，位置从 1 开始；位置写成字面量，所以这个调用可以作为分组键。 */
    public static Expr<String> substring(Expr<String> e, int start, int length) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, lit(position(start)), lit(count(length))));
    }

    /** SUBSTRING(e, start)：从 start（从 1 开始）到末尾。 */
    public static Expr<String> substring(Expr<String> e, int start) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, lit(position(start))));
    }

    /** 起点是表达式的 SUBSTRING，例如 {@link #locate} 的结果加 1。 */
    public static Expr<String> substring(Expr<String> e, Expr<Integer> start) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, start));
    }

    /** 起点和长度都是表达式的 SUBSTRING；常量写成 {@code lit(1)}。 */
    public static Expr<String> substring(Expr<String> e, Expr<Integer> start, Expr<Integer> length) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, start, length));
    }

    /** 左边 length 个字符。 */
    public static Expr<String> left(Expr<String> e, int length) {
        return Nodes.call(SqlType.of(String.class), "LEFT", List.of(e, lit(count(length))));
    }

    /** 右边 length 个字符。 */
    public static Expr<String> right(Expr<String> e, int length) {
        return Nodes.call(SqlType.of(String.class), "RIGHT", List.of(e, lit(count(length))));
    }

    /** 左边若干个字符，个数是表达式。 */
    public static Expr<String> left(Expr<String> e, Expr<Integer> length) {
        return Nodes.call(SqlType.of(String.class), "LEFT", List.of(e, length));
    }

    /** 右边若干个字符，个数是表达式。 */
    public static Expr<String> right(Expr<String> e, Expr<Integer> length) {
        return Nodes.call(SqlType.of(String.class), "RIGHT", List.of(e, length));
    }

    /** search 在 text 中第一次出现的位置，从 1 开始，找不到为 0。 */
    public static Expr<Integer> locate(Expr<String> text, String search) {
        return locate(text, text.param(Objects.requireNonNull(search, "search")));
    }

    /** 同上，search 是表达式。 */
    public static Expr<Integer> locate(Expr<String> text, Expr<String> search) {
        return Nodes.call(SqlType.of(Integer.class), LOCATE, List.of(text, search));
    }

    /** 把 search 全部替换为 replacement。 */
    public static Expr<String> replace(Expr<String> e, String search, String replacement) {
        SqlType<String> text = SqlType.of(String.class);
        return Nodes.call(text, "REPLACE", List.of(e, new Nodes.Param<>(text, Objects.requireNonNull(search, "search")),
            new Nodes.Param<>(text, Objects.requireNonNull(replacement, "replacement"))));
    }

    /** 校验文本位置从 1 开始。 */
    private static int position(int start) {
        if (start < 1) throw new IllegalArgumentException("Text positions start at 1; negative positions differ between databases");
        return start;
    }

    /** 校验字符个数不为负。 */
    private static int count(int length) {
        if (length < 0) throw new IllegalArgumentException("A length must not be negative");
        return length;
    }

    /** 拼接字符串；MySQL 渲染为 CONCAT(...)，其余为 ||。 */
    @SafeVarargs
    public static Expr<String> concat(Expr<String> first, Expr<String>... rest) {
        List<Expr<String>> args = new ArrayList<>();
        args.add(first);
        args.addAll(Arrays.asList(rest));
        return Nodes.call(SqlType.of(String.class), CONCAT, args);
    }

    // ---------- 数值 ----------

    /** 相加；结果是第一个操作数的类型。 */
    @SafeVarargs
    public static <N extends Number> Expr<N> plus(Expr<N> first, Expr<? extends Number>... rest) { return arithmetic("+", first, rest); }

    /** 一组项相加，例如十六个期间列。 */
    public static <N extends Number> Expr<N> plus(List<? extends Expr<N>> terms) {
        if (terms.isEmpty()) throw new IllegalArgumentException("plus() needs at least one term");
        return new Nodes.Arithmetic<>(terms.get(0).type(), "+", List.copyOf(terms));
    }

    /** 取负：{@code -(e)}。 */
    public static <N extends Number> Expr<N> negate(Expr<N> e) { return new Nodes.Negated<>(e); }

    /** 相乘；结果是第一个操作数的类型。 */
    @SafeVarargs
    public static <N extends Number> Expr<N> times(Expr<N> first, Expr<? extends Number>... rest) { return arithmetic("*", first, rest); }

    /** 相减。 */
    public static <N extends Number> Expr<N> minus(Expr<N> left, Expr<? extends Number> right) {
        return new Nodes.Arithmetic<>(left.type(), "-", List.of(left, right));
    }

    /** 除法，只接受小数被除数：整数相除在有的库上取整、有的不取整。 */
    public static Expr<BigDecimal> divide(Expr<BigDecimal> left, Expr<? extends Number> right) {
        return new Nodes.Arithmetic<>(left.type(), "/", List.of(left, right));
    }

    /** 绝对值。 */
    public static <N extends Number> Expr<N> abs(Expr<N> e) { return Nodes.call(e.type(), "ABS", List.of(e)); }

    /** 向下取整。 */
    public static <N extends Number> Expr<N> floor(Expr<N> e) { return Nodes.call(e.type(), "FLOOR", List.of(e)); }

    /** 向上取整。 */
    public static <N extends Number> Expr<N> ceil(Expr<N> e) { return Nodes.call(e.type(), "CEIL", List.of(e)); }

    /** 保留 scale 位小数四舍五入。 */
    public static <N extends Number> Expr<N> round(Expr<N> e, int scale) { return Nodes.call(e.type(), "ROUND", List.of(e, lit(scale))); }

    /** 余数，符号与被除数相同：{@code mod(-7, 3)} 是 -1。除数为 0 时 HANA、PostgreSQL 报错，MySQL 得到 NULL，与除法相同。 */
    public static <N extends Number> Expr<N> mod(Expr<N> e, Expr<? extends Number> divisor) { return Nodes.call(e.type(), "MOD", List.of(e, divisor)); }

    /** 同上，除数是常量。 */
    public static <N extends Number> Expr<N> mod(Expr<N> e, int divisor) { return mod(e, lit(divisor)); }

    /** base 的 exponent 次方，结果是浮点数。 */
    public static Expr<Double> power(Expr<? extends Number> base, Expr<? extends Number> exponent) {
        return Nodes.call(SqlType.of(Double.class), "POWER", List.of(base, exponent));
    }

    /** 同上，指数是常量。 */
    public static Expr<Double> power(Expr<? extends Number> base, int exponent) { return power(base, lit(exponent)); }

    /** 平方根，结果是浮点数；负数时报错。 */
    public static Expr<Double> sqrt(Expr<? extends Number> e) { return Nodes.call(SqlType.of(Double.class), "SQRT", List.of(e)); }

    /** 符号：负数为 -1，零为 0，正数为 1。 */
    public static Expr<Integer> sign(Expr<? extends Number> e) { return Nodes.call(SqlType.of(Integer.class), "SIGN", List.of(e)); }

    /** 把 first 与 rest 用同一个运算符连成一个算术表达式，类型取 first 的。 */
    private static <N extends Number> Expr<N> arithmetic(String operator, Expr<N> first, Expr<? extends Number>[] rest) {
        List<Expr<?>> operands = new ArrayList<>();
        operands.add(first);
        operands.addAll(Arrays.asList(rest));
        return new Nodes.Arithmetic<>(first.type(), operator, operands);
    }

    // ---------- 日期 ----------

    /** 年份，参数可以是日期或时间戳：{@code groupBy(l -> keys(year(l.createdOn()), month(l.createdOn())))}。 */
    public static Expr<Integer> year(Expr<? extends Temporal> e) { return Nodes.call(SqlType.of(Integer.class), YEAR, List.of(e)); }

    /** 月份，1 到 12。 */
    public static Expr<Integer> month(Expr<? extends Temporal> e) { return Nodes.call(SqlType.of(Integer.class), MONTH, List.of(e)); }

    /** 月中的第几天，1 到 31。 */
    public static Expr<Integer> day(Expr<? extends Temporal> e) { return Nodes.call(SqlType.of(Integer.class), DAY, List.of(e)); }

    /** 日期加上 days 天，可以为负。 */
    public static Expr<LocalDate> addDays(Expr<LocalDate> date, int days) { return Nodes.call(date.type(), ADD_DAYS, List.of(date, lit(days))); }

    /** 日期加上 months 个月；目标月没有那一天时取月末，例如 1 月 31 日加一个月是 2 月末。 */
    public static Expr<LocalDate> addMonths(Expr<LocalDate> date, int months) {
        return Nodes.call(date.type(), ADD_MONTHS, List.of(date, lit(months)));
    }

    /** 日期加上 years 年；2 月 29 日加一年是 2 月 28 日。 */
    public static Expr<LocalDate> addYears(Expr<LocalDate> date, int years) { return Nodes.call(date.type(), ADD_YEARS, List.of(date, lit(years))); }

    /** 从 from 到 to 相隔的天数，to 较早时为负。 */
    public static Expr<Integer> daysBetween(Expr<LocalDate> from, Expr<LocalDate> to) {
        return Nodes.call(SqlType.of(Integer.class), DAYS_BETWEEN, List.of(from, to));
    }

    /** 小时，0 到 23。 */
    public static Expr<Integer> hour(Expr<LocalDateTime> e) { return Nodes.call(SqlType.of(Integer.class), HOUR, List.of(e)); }

    /** 分钟，0 到 59。 */
    public static Expr<Integer> minute(Expr<LocalDateTime> e) { return Nodes.call(SqlType.of(Integer.class), MINUTE, List.of(e)); }

    /** 秒，0 到 59，不足一秒的部分舍去。 */
    public static Expr<Integer> second(Expr<LocalDateTime> e) { return Nodes.call(SqlType.of(Integer.class), SECOND, List.of(e)); }

    /** 时间戳加上 hours 小时，可以为负。 */
    public static Expr<LocalDateTime> addHours(Expr<LocalDateTime> time, int hours) { return addSeconds(time, hours * 3600L); }

    /** 时间戳加上 minutes 分钟，可以为负。 */
    public static Expr<LocalDateTime> addMinutes(Expr<LocalDateTime> time, int minutes) { return addSeconds(time, minutes * 60L); }

    /** 时间戳加上 seconds 秒，可以为负。 */
    public static Expr<LocalDateTime> addSeconds(Expr<LocalDateTime> time, long seconds) {
        return Nodes.call(time.type(), ADD_SECONDS, List.of(time, lit(seconds)));
    }

    /** 当月第一天，用于按月汇总时得到一个日期：{@code groupBy(l -> monthStart(l.createdOn()))}。 */
    public static Expr<LocalDate> monthStart(Expr<LocalDate> date) { return Nodes.call(date.type(), MONTH_START, List.of(date)); }

    /** 从 from 到 to 相隔的整秒数（不足一秒的部分舍去），to 较早时为负；任一端为 NULL 时为 NULL。 */
    public static Expr<Long> secondsBetween(Expr<LocalDateTime> from, Expr<LocalDateTime> to) {
        return Nodes.call(SqlType.of(Long.class), SECONDS_BETWEEN, List.of(from, to));
    }

    /** 左边用 pad 补足到 length 个字符，例如物料号补零 {@code lpad(m.material(), 18, "0")}；比 length 长时截断成前 length 个字符。 */
    public static Expr<String> lpad(Expr<String> e, int length, String pad) { return Nodes.call(e.type(), "LPAD", List.of(e, lit(length), lit(pad))); }

    /** 右边用 pad 补足到 length 个字符；比 length 长时截断成前 length 个字符。 */
    public static Expr<String> rpad(Expr<String> e, int length, String pad) { return Nodes.call(e.type(), "RPAD", List.of(e, lit(length), lit(pad))); }

    // ---------- 聚合 ----------

    /** COUNT(*)。 */
    public static Aggregate<Long> count() { return new Aggregate<>(SqlType.of(Long.class), "COUNT", false, List.of()); }

    /** COUNT(e)：不计 NULL。 */
    public static Aggregate<Long> count(Expr<?> e) { return new Aggregate<>(SqlType.of(Long.class), "COUNT", false, List.of(e)); }

    /** COUNT(DISTINCT e)。 */
    public static Aggregate<Long> countDistinct(Expr<?> e) { return new Aggregate<>(SqlType.of(Long.class), "COUNT", true, List.of(e)); }

    /** 求和。 */
    public static <N extends Number> Aggregate<N> sum(Expr<N> e) { return new Aggregate<>(e.type(), "SUM", false, List.of(e)); }

    /** SUM(DISTINCT e)：相同的值只加一次。 */
    public static <N extends Number> Aggregate<N> sumDistinct(Expr<N> e) { return new Aggregate<>(e.type(), "SUM", true, List.of(e)); }

    /** AVG(DISTINCT e)，结果是小数。 */
    public static Aggregate<BigDecimal> avgDistinct(Expr<? extends Number> e) {
        return new Aggregate<>(SqlType.of(BigDecimal.class), "AVG", true, List.of(e));
    }

    /**
     * 样本标准差 STDDEV_SAMP，只有一行时为 NULL。参数先转成 DOUBLE：HANA 对整数按定点小数计算，
     * 只保留 6 位小数，转换后三库结果一致。
     */
    public static Aggregate<Double> stddevSamp(Expr<? extends Number> e) { return statistic("STDDEV_SAMP", e); }

    /** 总体标准差 STDDEV_POP；参数先转成 DOUBLE，见 {@link #stddevSamp}。 */
    public static Aggregate<Double> stddevPop(Expr<? extends Number> e) { return statistic("STDDEV_POP", e); }

    /** 样本方差 VAR_SAMP，只有一行时为 NULL；参数先转成 DOUBLE，见 {@link #stddevSamp}。 */
    public static Aggregate<Double> varSamp(Expr<? extends Number> e) { return statistic("VAR_SAMP", e); }

    /** 总体方差 VAR_POP；参数先转成 DOUBLE，见 {@link #stddevSamp}。 */
    public static Aggregate<Double> varPop(Expr<? extends Number> e) { return statistic("VAR_POP", e); }

    /** 浮点结果的统计聚合。MySQL 的 STDDEV 是总体标准差，与另两库不同，所以只提供带 _SAMP / _POP 的名字。 */
    private static Aggregate<Double> statistic(String name, Expr<? extends Number> e) {
        return new Aggregate<>(SqlType.of(Double.class), name, false, List.of(cast(e, CastType.doublePrecision())));
    }

    /** 平均值，结果是小数。 */
    public static Aggregate<BigDecimal> avg(Expr<? extends Number> e) {
        return new Aggregate<>(SqlType.of(BigDecimal.class), "AVG", false, List.of(e));
    }

    /** 最小值。 */
    public static <T> Aggregate<T> min(Expr<T> e) { return new Aggregate<>(e.type(), "MIN", false, List.of(e)); }

    /** 最大值。 */
    public static <T> Aggregate<T> max(Expr<T> e) { return new Aggregate<>(e.type(), "MAX", false, List.of(e)); }

    /**
     * 把组内的文本用 separator 拼接，可以指定拼接顺序：{@code stringAgg(l.delivery(), ",", l.delivery().asc())}。
     * HANA、PostgreSQL 写成 STRING_AGG，MySQL 写成 GROUP_CONCAT（结果长度受 group_concat_max_len 限制，默认 1024 字节）。
     */
    public static Expr<String> stringAgg(Expr<String> value, String separator, OrderItem... order) {
        List<Renderable> args = new ArrayList<>();
        args.add(value);
        args.add(lit(Objects.requireNonNull(separator, "separator")));
        args.addAll(orders(order));
        return new Expr<>(value.type()) {
            @Override public void render(SqlWriter w) { STRING_AGG.render(w, args); }

            @Override public String nameHint() { return value.nameHint(); }
        };
    }

    /** groupByRollup 的汇总行上，被汇总掉的键为 1，其余为 0。 */
    public static Expr<Integer> grouping(Expr<?> key) { return Nodes.call(SqlType.of(Integer.class), "GROUPING", List.of(key)); }

    // ---------- 窗口函数 ----------

    /** ROW_NUMBER()，再加 .over(...)。 */
    public static WindowFunction<Long> rowNumber() { return new WindowFunction<>(SqlType.of(Long.class), "ROW_NUMBER", List.of()); }

    /** RANK()，再加 .over(...)。 */
    public static WindowFunction<Long> rank() { return new WindowFunction<>(SqlType.of(Long.class), "RANK", List.of()); }

    /** DENSE_RANK()，再加 .over(...)。 */
    public static WindowFunction<Long> denseRank() { return new WindowFunction<>(SqlType.of(Long.class), "DENSE_RANK", List.of()); }

    /** 前 offset 行的值，再加 .over(...)。 */
    public static <T> WindowFunction<T> lag(Expr<T> e, int offset) { return new WindowFunction<>(e.type(), "LAG", List.of(e, lit(offset))); }

    /** 后 offset 行的值，再加 .over(...)。 */
    public static <T> WindowFunction<T> lead(Expr<T> e, int offset) { return new WindowFunction<>(e.type(), "LEAD", List.of(e, lit(offset))); }

    /** 前 offset 行的值，越过分区开头时取 fallback，再加 .over(...)。 */
    public static <T> WindowFunction<T> lag(Expr<T> e, int offset, T fallback) {
        return new WindowFunction<>(e.type(), "LAG", List.of(e, lit(offset), e.param(fallback)));
    }

    /** 后 offset 行的值，越过分区末尾时取 fallback，再加 .over(...)。 */
    public static <T> WindowFunction<T> lead(Expr<T> e, int offset, T fallback) {
        return new WindowFunction<>(e.type(), "LEAD", List.of(e, lit(offset), e.param(fallback)));
    }

    /** 窗口里第一行的值，再加 .over(...)。 */
    public static <T> WindowFunction<T> firstValue(Expr<T> e) { return new WindowFunction<>(e.type(), "FIRST_VALUE", List.of(e)); }

    /**
     * 窗口里最后一行的值，再加 .over(...)。有 ORDER BY 时默认窗口只到当前行，要取整个分区的最后一行需写
     * {@code w.rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())}。
     */
    public static <T> WindowFunction<T> lastValue(Expr<T> e) { return new WindowFunction<>(e.type(), "LAST_VALUE", List.of(e)); }

    /** 相对排名 {@code (rank - 1) / (行数 - 1)}，取值 0 到 1；再加 .over(...)。 */
    public static WindowFunction<Double> percentRank() { return new WindowFunction<>(SqlType.of(Double.class), "PERCENT_RANK", List.of()); }

    /** 累计分布：不大于当前行的行数占分区行数的比例，取值大于 0、不超过 1；再加 .over(...)。 */
    public static WindowFunction<Double> cumeDist() { return new WindowFunction<>(SqlType.of(Double.class), "CUME_DIST", List.of()); }

    /**
     * 窗口里第 n 行（1 起）的值，没有时为 NULL；再加 .over(...)。与 {@link #lastValue} 一样，有 ORDER BY 时默认窗口只到当前行，
     * 要在整个分区里取需写 {@code w.rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())}。
     */
    public static <T> WindowFunction<T> nthValue(Expr<T> e, int n) {
        if (n < 1) throw new IllegalArgumentException("nthValue counts rows from 1");
        return new WindowFunction<>(e.type(), "NTH_VALUE", List.of(e, lit(n)));
    }

    /** 按窗口的顺序把分区分成 buckets 组，给出组号（1 起），例如四分位；再加 .over(...)。 */
    public static WindowFunction<Long> ntile(int buckets) {
        if (buckets < 1) throw new IllegalArgumentException("ntile needs at least one bucket");
        return new WindowFunction<>(SqlType.of(Long.class), "NTILE", List.of(lit(buckets)));
    }

    // ---------- CASE、CAST、原生 SQL、函数、子查询 ----------

    /** CASE WHEN condition THEN result ...，再接 .when(...)，以 .otherwise(...) 或 .end() 结束。 */
    public static <T> Case<T> caseWhen(Cond condition, Expr<T> result) {
        return new Case<T>(result.type(), List.of(), List.of()).when(condition, result);
    }

    /** 同上，结果是常量值。 */
    @SuppressWarnings("unchecked")
    public static <T> Case<T> caseWhen(Cond condition, T result) {
        return caseWhen(condition, new Nodes.Param<>((SqlType<T>) SqlType.of(result.getClass()), result));
    }

    /** 简单 CASE：{@code caseOf(code).when("1", "自有").when("2", "供应商").otherwise("其他")}，适合内联的码表。 */
    public static <V> Case.Simple.Start<V> caseOf(Expr<V> subject) { return new Case.Simple.Start<>(subject); }

    /** CAST(e AS type)。 */
    public static <U> Expr<U> cast(Expr<?> e, CastType<U> type) { return new Nodes.Cast<>(e, type); }

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
     * 用 {@code .on(方言类, 模板)} 给某个方言另一种写法。
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

    /** 单列查询作为一个值（标量子查询）。 */
    public static <T> Expr<T> scalar(Query<T> subquery) { return new Nodes.Scalar<>(subquery); }

    /** 与子查询的任一行比较：{@code x.eq(anyOf(q))} 写成 {@code x = ANY (子查询)}；子查询没有行时比较为假。 */
    public static <T> Expr<T> anyOf(Query<T> subquery) { return new Nodes.Quantified<>("ANY", subquery); }

    /** 与子查询的每一行比较：{@code x.gt(allOf(q))} 写成 {@code x > ALL (子查询)}；子查询没有行时比较为真。 */
    public static <T> Expr<T> allOf(Query<T> subquery) { return new Nodes.Quantified<>("ALL", subquery); }

    /** 条件作为布尔列，例如 {@code row(bool(l.quantity().gt(BigDecimal.ZERO)).as("positive"))}；条件未知时为 NULL。 */
    public static Expr<Boolean> bool(Cond condition) { return new Nodes.Truth(condition); }

    /** 当前日期。 */
    public static Expr<LocalDate> currentDate() { return Nodes.call(SqlType.of(LocalDate.class), CURRENT_DATE, List.of()); }

    /** 当前时间戳。 */
    public static Expr<LocalDateTime> currentTimestamp() { return Nodes.call(SqlType.of(LocalDateTime.class), CURRENT_TIMESTAMP, List.of()); }

    // ---------- 一个 lambda 返回多个键或排序项 ----------

    /** 多个 GROUP BY 键；只有一个键时直接写表达式。 */
    public static KeyList keys(Expr<?>... exprs) { return new KeyList(List.of(exprs)); }

    /** 多个 ORDER BY 项；裸表达式按升序。 */
    public static OrderList orders(OrderItem... items) { return new OrderList(OrderList.of(items)); }
}
