package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方言的开放基类，只负责语法：标识符的引号和大小写折叠、字面量、分页、CAST 类型名、
 * JDBC 类型处理，以及驱动流式读取行所需的设置。方言已知的函数写法在构造函数里用
 * {@link #register} 登记；任何地方都不按方言名称分支。
 */
public abstract class Dialect {
    private final Map<FunctionDef, FunctionRenderer> functions = new ConcurrentHashMap<>();

    /** 供各方言继承。 */
    protected Dialect() { }

    /** 方言名称，用于错误信息和调试。 */
    public abstract String name();

    /** 标识符无需加引号时返回 true，例如 HANA 的大写名字。 */
    protected abstract boolean isBare(String identifier);

    /** 未加引号的标识符会被折叠成的大小写，使生成的别名可以不加引号。 */
    public abstract String foldCase(String identifier);

    /**
     * 即使本身可以不加引号，也必须加引号的保留字，例如实体 {@code Order} 的别名 {@code order}。
     * 取标准 SQL、PostgreSQL 和 HANA 保留字的并集；多给一个词加引号没有坏处。
     */
    protected boolean isReserved(String identifier) { return RESERVED.contains(identifier.toUpperCase(java.util.Locale.ROOT)); }

    /** 按需给标识符加双引号，内部的双引号写成两个。 */
    public String quote(String identifier) {
        return isBare(identifier) && !isReserved(identifier) ? identifier : '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of(
        "ALL", "ALTER", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC", "ASYMMETRIC", "AUTHORIZATION", "BEFORE", "BEGIN", "BETWEEN",
        "BINARY", "BOTH", "BY", "CASE", "CAST", "CHAR", "CHECK", "COLLATE", "COLLATION", "COLUMN", "CONCURRENTLY", "CONDITION", "CONNECT",
        "CONSTRAINT", "CREATE", "CROSS", "CUBE", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME",
        "CURRENT_TIMESTAMP", "CURRENT_USER", "CURSOR", "DECLARE", "DEFAULT", "DEFERRABLE", "DELETE", "DESC", "DISTINCT", "DO", "DROP", "ELSE",
        "ELSEIF", "END", "EXCEPT", "EXCEPTION", "EXEC", "EXISTS", "FALSE", "FETCH", "FOR", "FOREIGN", "FREEZE", "FROM", "FULL", "GRANT", "GROUP",
        "HAVING", "IF", "ILIKE", "IN", "INITIALLY", "INNER", "INOUT", "INSERT", "INTERSECT", "INTO", "IS", "ISNULL", "JOIN", "LATERAL", "LEADING",
        "LEFT", "LIKE", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "LOOP", "MINUS", "NATURAL", "NCHAR", "NOT", "NOTNULL", "NULL", "OFFSET", "ON",
        "ONLY", "OR", "ORDER", "OUT", "OUTER", "OVERLAPS", "PLACING", "PRIMARY", "PRIOR", "REFERENCES", "RETURN", "RETURNING", "RETURNS",
        "RIGHT", "ROLLUP", "ROWID", "SELECT", "SESSION_USER", "SET", "SIMILAR", "SOME", "SQL", "START", "SYMMETRIC", "SYSTEM_USER", "TABLE",
        "TABLESAMPLE", "THEN", "TO", "TOP", "TRAILING", "TRUE", "UNION", "UNIQUE", "UNKNOWN", "UPDATE", "USER", "USING", "VALUES", "VARIADIC",
        "VERBOSE", "WHEN", "WHERE", "WHILE", "WINDOW", "WITH");

    /** 返回别名在 SQL 中的写法。别名来自 {@link SqlWriter#aliasFor}，已经折叠过大小写。 */
    public String alias(String alias) { return quote(alias); }

    /** 写出分页：{@code limit} 或 {@code offset} 为 null 表示不限；offset 不大于 0 时省略。 */
    public void limit(SqlWriter w, Integer limit, Integer offset) {
        if (limit != null) w.sql("\nLIMIT ").sql(Integer.toString(limit));
        if (offset != null && offset > 0) w.sql(limit == null ? "\nOFFSET " : " OFFSET ").sql(Integer.toString(offset));
    }

    /** 写出 GROUP BY 列表；rollup 为 true 时，会为分组键的每个前缀生成小计，并附加总计。 */
    public void groupBy(SqlWriter w, List<? extends Renderable> keys, boolean rollup) {
        if (rollup) w.sql("ROLLUP(").join(keys, ", ").sql(")");
        else w.join(keys, ", ");
    }

    /** 写出一个 ORDER BY 项；查询没有指定 NULL 的位置时，{@code nullsFirst} 为 null。 */
    public void orderItem(SqlWriter w, Renderable expr, boolean descending, Boolean nullsFirst) {
        w.render(expr).sql(descending ? " DESC" : " ASC");
        if (nullsFirst != null) w.sql(nullsFirst ? " NULLS FIRST" : " NULLS LAST");
    }

    /**
     * NULL 也相等的比较（标准 SQL 的 {@code IS [NOT] DISTINCT FROM}）。默认写成三个库都支持的
     * {@code CASE WHEN a = b OR (a IS NULL AND b IS NULL) THEN 1 ELSE 0 END = 1}，negate 时比较 0；
     * PostgreSQL 与 MySQL 换成各自的原生写法。
     */
    public void notDistinct(SqlWriter w, Renderable left, Renderable right, boolean negate) {
        w.sql("CASE WHEN ").render(left).sql(" = ").render(right).sql(" OR (").render(left).sql(" IS NULL AND ").render(right)
            .sql(" IS NULL) THEN 1 ELSE 0 END = ").sql(negate ? "0" : "1");
    }

    /** 没有数据表的 SELECT 需要补的内容：多数数据库什么都不用写，HANA 要写 {@code FROM DUMMY}。 */
    public void fromNothing(SqlWriter w) { }

    /** {@link #literal} 能否写出该值；不能的（例如 NaN）即使在内联时也仍作为参数。 */
    public boolean hasLiteral(Object value) {
        if (value instanceof Double d) return Double.isFinite(d);
        if (value instanceof Float f) return Float.isFinite(f);
        return value instanceof String || value instanceof Boolean || value instanceof Number || value instanceof LocalDate
            || value instanceof LocalDateTime || value instanceof Enum<?>;
    }

    /** 把值写成字面量；没有字面量形式的值抛出异常，应改为绑定参数。 */
    public void literal(SqlWriter w, Object value) {
        if (value == null) w.sql("NULL");
        else if (!hasLiteral(value)) throw new IllegalArgumentException("No literal form for " + value + " (" + value.getClass().getName() + "); bind it as a parameter");
        else if (value instanceof String s) w.sql(stringLiteral(s));
        else if (value instanceof Boolean b) w.sql(b ? "TRUE" : "FALSE");
        else if (value instanceof BigDecimal d) w.sql(d.toPlainString());
        else if (value instanceof Number n) w.sql(n.toString());
        else if (value instanceof LocalDate d) w.sql("DATE '" + d + "'");
        else if (value instanceof LocalDateTime t) w.sql("TIMESTAMP '" + timestampText(t) + "'");
        else if (value instanceof Enum<?> e) w.sql(stringLiteral(e.name()));
        else throw new IllegalArgumentException("No literal form for " + value.getClass().getName() + "; bind it as a parameter");
    }

    /** 字符串字面量：单引号包裹，内部的单引号写成两个。 */
    protected String stringLiteral(String s) { return "'" + s.replace("'", "''") + "'"; }

    /** 总是带秒，小数部分去掉末尾的零；TypeScript 端写出的也是这种形式。 */
    private static String timestampText(LocalDateTime t) {
        String fraction = t.getNano() == 0 ? "" : ("." + String.format("%09d", t.getNano())).replaceAll("0+$", "");
        return String.format("%s %02d:%02d:%02d%s", t.toLocalDate(), t.getHour(), t.getMinute(), t.getSecond(), fraction);
    }

    /** CAST 目标类型在本方言中的类型名；方言可覆盖。 */
    public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case TEXT -> "VARCHAR(" + type.length() + ")";
            case DECIMAL -> "DECIMAL(" + type.length() + "," + type.scale() + ")";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case DOUBLE -> "DOUBLE PRECISION";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case BOOLEAN -> "BOOLEAN";
        };
    }

    /** IN 列表的最大长度，更长的会拆成用 OR 连接的多个 IN 谓词。 */
    public int maxInListSize() { return 1000; }

    /** 一条语句最多能带的绑定参数个数；HANA 和 PostgreSQL 的通信协议用 16 位来计数。 */
    public int maxParameters() { return 65535; }

    /** 数据库是否因为语句超出查询超时而将其取消。 */
    public boolean isTimeout(SQLException e) { return e instanceof java.sql.SQLTimeoutException; }

    /**
     * 手写 SQL 里从 {@code start} 开始的带引号部分在哪里结束：返回其最后一个字符的下标，
     * 该处没有带引号部分时返回 -1。原生查询只在这类部分之外查找 {@code :name}。
     * 默认识别 '...' 和 "..."，内部的引号写成两个。
     */
    public int quotedEnd(String sql, int start) {
        char c = sql.charAt(start);
        return c == '\'' || c == '"' ? closingQuote(sql, start, false) : -1;
    }

    /**
     * 与 {@code start} 处的引号配对的结束引号的下标，会跳过成对的引号；
     * {@code backslashEscapes} 为 true 时还会跳过被反斜杠转义的字符；始终没有闭合时返回最后一个下标。
     */
    protected static int closingQuote(String sql, int start, boolean backslashEscapes) {
        char quote = sql.charAt(start);
        for (int i = start + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') i++;
            else if (c == quote && i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
            else if (c == quote) return i;
        }
        return sql.length() - 1;
    }

    /** 本方言使用的 JDBC 类型注册表；默认是通用的 {@link TypeRegistry#defaults()}。 */
    public TypeRegistry types() { return TypeRegistry.defaults(); }

    /** 撤销 {@link #openCursor} 对连接做的改动；流关闭时执行。 */
    @FunctionalInterface
    public interface CursorCleanup {
        /** 什么都不用做的清理。 */
        CursorCleanup NONE = () -> { };

        /** 执行清理。 */
        void run() throws SQLException;
    }

    /**
     * 为 {@code Database.stream} 准备语句，让行从服务端游标读取，而不是把整个结果缓冲到内存。
     * 多数驱动无需处理：HANA 会自行分块获取。
     */
    public CursorCleanup openCursor(Connection connection, PreparedStatement statement) throws SQLException {
        return CursorCleanup.NONE;
    }

    /** 写出加锁读的子句：{@code FOR UPDATE}，加上 {@code NOWAIT} 或 {@code SKIP LOCKED}。 */
    public void lock(SqlWriter w, Lock lock) {
        w.sql("FOR UPDATE");
        if (lock == Lock.NOWAIT) w.sql(" NOWAIT");
        else if (lock == Lock.SKIP_LOCKED) w.sql(" SKIP LOCKED");
    }

    /** 写出插入或更新一行的语句；各方言的写法不同，没有实现的方言报错。 */
    public void upsert(SqlWriter w, Upsert upsert) {
        throw new UnsupportedOperationException(name() + " has no insert-or-update statement; register one in its dialect");
    }

    /** 写出联表的 UPDATE；只有方言包实现，见 {@link JoinWrite}。 */
    public void joinUpdate(SqlWriter w, JoinWrite update) {
        throw new UnsupportedOperationException(name() + " has no join update; use a subquery in where(...) instead");
    }

    /** 写出联表的 DELETE；只有方言包实现，见 {@link JoinWrite}。 */
    public void joinDelete(SqlWriter w, JoinWrite delete) {
        throw new UnsupportedOperationException(name() + " has no join delete; use a subquery in where(...) instead");
    }

    /** 写出列名清单 {@code ("A", "B")}。 */
    protected static void columnList(SqlWriter w, List<String> columns) {
        w.sql("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) w.sql(", ");
            w.identifier(columns.get(i));
        }
        w.sql(")");
    }

    /**
     * 为插入一行并取回生成值准备语句：默认请求 JDBC 的生成键（MySQL 取 AUTO_INCREMENT）。
     * column 是生成值的列名（未加引号）。
     */
    public PreparedStatement prepareInsert(Connection connection, String sql, String column) throws SQLException {
        return connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
    }

    /** 执行完 {@link #prepareInsert} 的语句后，取回这一行生成的值所在的结果集，光标停在那一行之前。 */
    public ResultSet generatedKey(Connection connection, PreparedStatement statement) throws SQLException {
        return statement.getGeneratedKeys();
    }

    /** 登记本方言对某个函数的渲染，供子类在构造函数中调用。 */
    protected final void register(FunctionDef function, FunctionRenderer renderer) { functions.put(function, renderer); }

    /** 本方言登记的函数渲染器；没有登记则为 null，由 {@link FunctionDef} 退回标准写法。 */
    public final FunctionRenderer renderer(FunctionDef function) { return functions.get(function); }

    @Override public String toString() { return name(); }
}
