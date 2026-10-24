package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.FunctionRenderer;
import org.dpdns.zerodep.query.dialect.JoinWrite;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.dialect.Upsert;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SQLite（3.46 起），包括 sqlite-jdbc 和浏览器里的 SQLite WASM 所用的同一套写法。标识符不分大小写，原样书写。
 * 日期和时间戳按文本存储（{@code YYYY-MM-DD}、{@code YYYY-MM-DD HH:MM:SS[.fff]}），布尔存成 0 / 1，
 * 小数按 NUMERIC 亲和存储；与三库的差异和取舍见 {@code docs/decisions/0009-sqlite-dialect.md}。
 */
public class SqliteDialect extends Dialect {
    /** 无状态单例，{@link SqliteDatabase} 使用它。 */
    public static final SqliteDialect INSTANCE = new SqliteDialect();

    /** 不必加引号的标识符：字母、数字、下划线，且不以数字开头。 */
    private static final Pattern BARE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 警告写到这个日志器。 */
    private static final System.Logger LOG = System.getLogger("org.dpdns.zerodep.query.sqlite");

    /** 需要编译选项才有的函数，以及对应的选项；第一次渲染时记一条警告。 */
    private static final Map<String, String> EXTENSIONS = Map.of(
        "SQRT", "SQLITE_ENABLE_MATH_FUNCTIONS", "POWER", "SQLITE_ENABLE_MATH_FUNCTIONS", "CEIL", "SQLITE_ENABLE_MATH_FUNCTIONS",
        "FLOOR", "SQLITE_ENABLE_MATH_FUNCTIONS", "MOD", "SQLITE_ENABLE_MATH_FUNCTIONS",
        "MEDIAN", "SQLITE_ENABLE_PERCENTILE", "PERCENTILE_CONT", "SQLITE_ENABLE_PERCENTILE", "PERCENTILE_DISC", "SQLITE_ENABLE_PERCENTILE");

    /** 用公式代替的统计聚合，不能加 OVER (...)。 */
    private static final Set<String> FORMULAS = Set.of("STDDEV_SAMP", "STDDEV_POP", "VAR_SAMP", "VAR_POP");

    /** 已经警告过的函数，每个函数只警告一次。 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** 时间戳的文本格式：秒之后的小数去掉末尾的零，与写入的参数、字面量一致。 */
    static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().toFormatter();

    /** 读时间戳时接受的格式：空格或 T 分隔，秒可以省略，小数位数不限。 */
    private static final DateTimeFormatter TIMESTAMP_IN = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd").optionalStart().appendLiteral(' ').optionalEnd().optionalStart().appendLiteral('T').optionalEnd()
        .appendPattern("HH:mm").optionalStart().appendPattern(":ss").optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd().optionalEnd().toFormatter();

    /**
     * 登记与三库不同的写法：日期部分用 strftime，加日期用 date 的修饰符（月份加 floor，月末对齐），
     * 相隔天数用 julianday，相隔秒数用带 subsec 的 unixepoch（差值再舍去小数），当前时间取本地时区；
     * 补齐用 zeroblob 拼出填充串；统计聚合用公式；ROLLUP 的 GROUPING 报错。
     */
    protected SqliteDialect() {
        register(Sql.LOCATE, (w, args) -> FunctionDef.call(w, "instr", args));
        register(Sql.YEAR, (w, args) -> part(w, args, "%Y"));
        register(Sql.MONTH, (w, args) -> part(w, args, "%m"));
        register(Sql.DAY, (w, args) -> part(w, args, "%d"));
        register(Sql.HOUR, (w, args) -> part(w, args, "%H"));
        register(Sql.MINUTE, (w, args) -> part(w, args, "%M"));
        register(Sql.SECOND, (w, args) -> part(w, args, "%S"));
        register(Sql.ADD_DAYS, (w, args) -> shiftDate(w, args, " days", false));
        register(Sql.ADD_MONTHS, (w, args) -> shiftDate(w, args, " months", true));
        register(Sql.ADD_YEARS, (w, args) -> shiftDate(w, args, " years", true));
        register(Sql.DAYS_BETWEEN, (w, args) -> w.sql("CAST(julianday(").render(args.get(1)).sql(") - julianday(").render(args.get(0)).sql(") AS INTEGER)"));
        register(Sql.SECONDS_BETWEEN, (w, args) ->
            w.sql("CAST(unixepoch(").render(args.get(1)).sql(", 'subsec') - unixepoch(").render(args.get(0)).sql(", 'subsec') AS INTEGER)"));
        register(Sql.ADD_SECONDS, (w, args) -> timestamp(w, x -> x.render(args.get(0)).sql(", ").render(args.get(1)).sql(" || ' seconds'")));
        register(Sql.MONTH_START, (w, args) -> w.sql("date(").render(args.get(0)).sql(", 'start of month')"));
        register(Sql.CURRENT_DATE, (w, args) -> w.sql("date('now', 'localtime')"));
        register(Sql.CURRENT_TIMESTAMP, (w, args) -> timestamp(w, x -> x.sql("'now', 'localtime'")));
        registerCall("LEFT", (w, args) -> w.sql("substr(").render(args.get(0)).sql(", 1, ").render(args.get(1)).sql(")"));
        registerCall("RIGHT", (w, args) ->
            w.sql("CASE WHEN ").render(args.get(1)).sql(" > 0 THEN substr(").render(args.get(0)).sql(", -").render(args.get(1)).sql(") ELSE '' END"));
        registerCall("LPAD", (w, args) -> pad(w, args, true));
        registerCall("RPAD", (w, args) -> pad(w, args, false));
        registerCall("GROUPING", (w, args) -> {
            throw new UnsupportedOperationException("SQLite has no GROUPING() or ROLLUP; group in a CTE per level and UNION ALL them");
        });
        for (String name : List.of("SQRT", "POWER", "CEIL", "FLOOR", "MOD")) registerCall(name, extension(name));
        registerCall("VAR_SAMP", (w, args) -> variance(w, args.get(0), true));
        registerCall("VAR_POP", (w, args) -> variance(w, args.get(0), false));
        registerCall("STDDEV_SAMP", (w, args) -> deviation(w, args.get(0), true));
        registerCall("STDDEV_POP", (w, args) -> deviation(w, args.get(0), false));
    }

    /** 写出 {@code CAST(strftime('格式', e) AS INTEGER)}。 */
    private static void part(SqlWriter w, List<? extends Renderable> args, String format) {
        w.sql("CAST(strftime('" + format + "', ").render(args.get(0)).sql(") AS INTEGER)");
    }

    /** 写出 {@code date(d, n || ' unit'[, 'floor'])}；floor 让月末对齐（1 月 31 日加一个月是 2 月 28 日）。 */
    private static void shiftDate(SqlWriter w, List<? extends Renderable> args, String unit, boolean floor) {
        w.sql("date(").render(args.get(0)).sql(", ").render(args.get(1)).sql(" || '" + unit + "'").sql(floor ? ", 'floor')" : ")");
    }

    /** 写出规整的时间戳文本：{@code strftime('%Y-%m-%d %H:%M:%f', ...)} 再去掉小数末尾的零和多余的小数点。 */
    private static void timestamp(SqlWriter w, Renderable arguments) {
        w.sql("rtrim(rtrim(strftime('%Y-%m-%d %H:%M:%f', ").render(arguments).sql("), '0'), '.')");
    }

    /**
     * 补齐到 length：已经不短于 length 时截断（与三库相同），否则用填充串补在左边（lpad）或右边。
     * 填充串由 {@code replace(hex(zeroblob(n)), '00', pad)} 拼出，再截到需要的长度。
     */
    private static void pad(SqlWriter w, List<? extends Renderable> args, boolean left) {
        Renderable e = args.get(0);
        Renderable n = args.get(1);
        Renderable p = args.get(2);
        Renderable fill = x -> x.sql("substr(replace(hex(zeroblob(").render(n).sql(")), '00', ").render(p).sql("), 1, ").render(n).sql(" - length(").render(e).sql("))");
        w.sql("CASE WHEN length(").render(e).sql(") >= ").render(n).sql(" THEN substr(").render(e).sql(", 1, ").render(n).sql(") ELSE ");
        if (left) w.render(fill).sql(" || ").render(e);
        else w.render(e).sql(" || ").render(fill);
        w.sql(" END");
    }

    /** 需要编译选项的函数：照常写成 {@code NAME(args)}，第一次渲染时记一条警告。 */
    private FunctionRenderer extension(String name) {
        return (w, args) -> {
            warnOnce(name);
            FunctionDef.call(w, name.toLowerCase(java.util.Locale.ROOT), args);
        };
    }

    /** 函数 name 需要编译选项时，第一次渲染记一条警告；供门面里的方言专属函数使用。 */
    void warnOnce(String name) {
        String option = EXTENSIONS.get(name);
        if (option != null) warn(name, () -> name + "() needs SQLite built with " + option + "; sqlite-jdbc, node:sqlite and the official WASM build have it");
    }

    /** 同一个 key 只记一次的警告。 */
    void warn(String key, java.util.function.Supplier<String> message) {
        if (warned.add(key)) LOG.log(System.Logger.Level.WARNING, message);
    }

    /**
     * 方差的公式 {@code (SUM(x*x) - SUM(x)*SUM(x)/COUNT(x)) / (COUNT(x) - 1)}（总体方差除以 COUNT(x)），
     * 不小于 0；样本只有一行时除以 0 得到 NULL，与三库相同。数值稳定性不如原生的方差函数。
     */
    private static void variance(SqlWriter w, Renderable x, boolean sample) {
        w.sql("MAX((SUM(").render(x).sql(" * ").render(x).sql(") - SUM(").render(x).sql(") * SUM(").render(x).sql(") / COUNT(").render(x).sql(")) / ");
        if (sample) w.sql("(COUNT(").render(x).sql(") - 1)");
        else w.sql("COUNT(").render(x).sql(")");
        w.sql(", 0)");
    }

    /** 标准差：方差开平方（{@code sqrt} 需要数学函数）。 */
    private void deviation(SqlWriter w, Renderable x, boolean sample) {
        warnOnce("SQRT");
        w.sql("sqrt(");
        variance(w, x, sample);
        w.sql(")");
    }

    /** 方言名，用于异常信息和监听事件。 */
    @Override public String name() { return "SQLite"; }

    /** 字母、数字、下划线组成的名字无需加引号。 */
    @Override protected boolean isBare(String identifier) { return BARE.matcher(identifier).matches(); }

    /** SQLite 的标识符不分大小写，原样书写。 */
    @Override public String foldCase(String identifier) { return identifier; }

    /** UPDATE 和 DELETE 的表别名必须写 AS，查询里也照样写。 */
    @Override public void sourceAlias(SqlWriter w, String alias) { w.sql(" AS ").alias(alias); }

    /** 只有 offset 时写成 {@code LIMIT -1 OFFSET m}：SQLite 的 OFFSET 必须跟在 LIMIT 后面。 */
    @Override public void limit(SqlWriter w, Integer limit, Integer offset) {
        if (limit == null && offset != null && offset > 0) w.sql("\nLIMIT -1 OFFSET ").sql(Integer.toString(offset));
        else super.limit(w, limit, offset);
    }

    /** SQLite 没有 ROLLUP。 */
    @Override public void groupBy(SqlWriter w, List<? extends Renderable> keys, boolean rollup) {
        if (rollup) throw new UnsupportedOperationException("SQLite has no ROLLUP; group in a CTE per level and UNION ALL them");
        super.groupBy(w, keys, false);
    }

    /** SQLite 只有一个写者，没有行锁；用写事务（BEGIN IMMEDIATE）代替加锁读。 */
    @Override public void lock(SqlWriter w, Lock lock) {
        throw new UnsupportedOperationException("SQLite has no FOR UPDATE: it has a single writer, so read and write inside one transaction instead");
    }

    /** NULL 也相等的比较写成 {@code a IS b} / {@code a IS NOT b}。 */
    @Override public void notDistinct(SqlWriter w, Renderable left, Renderable right, boolean negate) {
        w.render(left).sql(negate ? " IS NOT " : " IS ").render(right);
    }

    /** 没有 INTERSECT ALL、EXCEPT ALL。 */
    @Override public String setOperator(String operator) {
        if (operator.endsWith(" ALL") && !operator.equals("UNION ALL")) throw new UnsupportedOperationException("SQLite has no " + operator);
        return operator;
    }

    /**
     * SQLite 没有 ANY / ALL 子查询：{@code x op ANY (q)} 写成 {@code EXISTS (SELECT 1 FROM (q) AS 别名 WHERE x op 别名.列)}，
     * {@code x op ALL (q)} 写成 {@code NOT EXISTS (... WHERE (x op 别名.列) IS NOT TRUE)}。用在 WHERE 里与标准写法等价；
     * 当作值使用时，标准写法得到 NULL 的情况这里得到假。
     */
    @Override public void quantified(SqlWriter w, Renderable left, String operator, String quantifier, Renderable subquery, Renderable named, String column) {
        String alias = w.aliasFor(new Object(), "q");
        boolean all = quantifier.equals("ALL");
        w.sql(all ? "NOT EXISTS (SELECT 1 FROM (" : "EXISTS (SELECT 1 FROM (").render(named).sql(")");
        sourceAlias(w, alias);
        w.sql(" WHERE ");
        if (all) w.sql("(");
        w.render(left).sql(" " + operator + " ").alias(alias).sql(".").identifier(column);
        if (all) w.sql(") IS NOT TRUE");
        w.sql(")");
    }

    /** 复合查询的一段不能加括号，一组写成 {@code SELECT * FROM (...)}。 */
    @Override public String groupOpen() { return "SELECT * FROM ("; }

    /** sqlite-jdbc 不按 setQueryTimeout 取消语句（实测），由执行器到时调用 cancel()。 */
    @Override public boolean honoursQueryTimeout() { return false; }

    /** 公式代替的统计聚合不能当窗口函数用。 */
    @Override public boolean windowable(String name) { return !FORMULAS.contains(name); }

    /** 日期和时间戳写成规整的文本（存的就是文本），布尔写成 1 / 0。 */
    @Override public void literal(SqlWriter w, Object value) {
        if (value instanceof LocalDate d) w.sql("'" + d + "'");
        else if (value instanceof LocalDateTime t) w.sql("'" + TIMESTAMP.format(t) + "'");
        else if (value instanceof Boolean b) w.sql(b ? "1" : "0");
        else super.literal(w, value);
    }

    /** 转换的类型名按 SQLite 的亲和：文本 TEXT、小数 NUMERIC、整数 INTEGER、浮点 REAL。 */
    @Override public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case TEXT -> "TEXT";
            case DECIMAL -> "NUMERIC";
            case INTEGER, BIGINT, BOOLEAN -> "INTEGER";
            case DOUBLE -> "REAL";
            case DATE, TIMESTAMP -> throw new IllegalStateException("dates are converted by cast(...)");
        };
    }

    /** 转成日期写成 {@code date(x)}、转成时间戳写成规整的时间戳文本：{@code CAST(x AS DATE)} 在 SQLite 上得到数字。 */
    @Override public void cast(SqlWriter w, Renderable inner, CastType<?> target) {
        switch (target.kind()) {
            case DATE -> w.sql("date(").render(inner).sql(")");
            case TIMESTAMP -> timestamp(w, inner);
            default -> super.cast(w, inner, target);
        }
    }

    /** 除法先把被除数转成 REAL：小数列里的整数值存成 INTEGER，{@code 7 / 2} 会得到 3。 */
    @Override public void arithmetic(SqlWriter w, String operator, List<? extends Renderable> operands) {
        if (!operator.equals("/")) {
            super.arithmetic(w, operator, operands);
            return;
        }
        w.sql("(CAST(").render(operands.get(0)).sql(" AS REAL)");
        for (Renderable r : operands.subList(1, operands.size())) w.sql(" / ").render(r);
        w.sql(")");
    }

    /** {@code INSERT ... ON CONFLICT (键) DO UPDATE SET 列 = excluded.列} 或 {@code DO NOTHING}。 */
    @Override public void upsert(SqlWriter w, Upsert u) {
        w.sql("INSERT INTO ").render(u.table()).sql(" ");
        columnList(w, u.columns());
        w.sql(" VALUES (").join(u.values(), ", ").sql(")\nON CONFLICT ");
        columnList(w, u.keys());
        if (u.doNothing()) {
            w.sql(" DO NOTHING");
            return;
        }
        w.sql(" DO UPDATE SET ");
        boolean first = true;
        for (String c : u.updates()) {
            if (!first) w.sql(", ");
            first = false;
            w.identifier(c).sql(" = excluded.").identifier(c);
        }
        for (var a : u.assignments().entrySet()) {
            if (!first) w.sql(", ");
            first = false;
            w.identifier(a.getKey()).sql(" = ").render(a.getValue());
        }
    }

    /** {@code UPDATE 表 AS 别名 SET 列 = 值 FROM 连接表 AS 别名 WHERE 连接条件 AND ...}；SET 左边的列不带别名。 */
    @Override public void joinUpdate(SqlWriter w, JoinWrite u) {
        w.sql("UPDATE ").render(u.target()).sql("\nSET ");
        for (int i = 0; i < u.columns().size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(u.columns().get(i)).sql(" = ").render(u.values().get(i));
        }
        w.sql("\nFROM ").render(u.source()).sql("\nWHERE ");
        u.renderOnAndWhere(w);
    }

    /** SQLite 的 DELETE 不能连接别的表，写成 {@code DELETE FROM 表 AS 别名 WHERE EXISTS (SELECT 1 FROM 连接表 WHERE ...)}。 */
    @Override public void joinDelete(SqlWriter w, JoinWrite d) {
        w.sql("DELETE FROM ").render(d.target()).sql("\nWHERE EXISTS (SELECT 1\nFROM ").render(d.source()).sql("\nWHERE ");
        d.renderOnAndWhere(w);
        w.sql(")");
    }

    /** 绑定参数的上限（3.32 起的 SQLITE_MAX_VARIABLE_NUMBER）。 */
    @Override public int maxParameters() { return 32766; }

    /** sqlite-jdbc 用 sqlite3_interrupt 取消超时的语句，报 SQLITE_INTERRUPT（9）。 */
    @Override public boolean isTimeout(SQLException e) {
        return super.isTimeout(e) || e.getErrorCode() == 9 || String.valueOf(e.getMessage()).contains("SQLITE_INTERRUPT");
    }

    /** 日期、时间戳按文本读写，布尔按 0 / 1，小数绑定成文本（{@code setBigDecimal} 会先转成浮点）。 */
    private static final TypeRegistry TYPES = TypeRegistry.defaults()
        .with(LocalDate.class, (rs, i) -> {
            String s = rs.getString(i);
            return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        }, (ps, i, v) -> ps.setString(i, v.toString()))
        .with(LocalDateTime.class, (rs, i) -> {
            String s = rs.getString(i);
            return s == null ? null : LocalDateTime.parse(s.length() == 10 ? s + " 00:00" : s, TIMESTAMP_IN);
        }, (ps, i, v) -> ps.setString(i, TIMESTAMP.format(v)))
        .with(Boolean.class, (rs, i) -> {
            int v = rs.getInt(i);
            return rs.wasNull() ? null : v != 0;
        }, (ps, i, v) -> ps.setInt(i, v ? 1 : 0))
        .with(BigDecimal.class, (rs, i) -> {
            String s = rs.getString(i);
            return s == null ? null : new BigDecimal(s);
        }, (ps, i, v) -> ps.setString(i, v.toPlainString()));

    /** 本方言的类型注册表。 */
    @Override public TypeRegistry types() { return TYPES; }

    /** 结构变更的写法，见 {@code db.schema()}。 */
    private final SchemaSyntax schemaSyntax = new SqliteSchemaSyntax(this);

    @Override public SchemaSyntax schemaSyntax() { return schemaSyntax; }
}
