package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.JoinWrite;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.dialect.Upsert;
import org.dpdns.zerodep.query.type.CastType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PostgreSQL。不加引号的标识符折叠为小写。 */
public class PostgresDialect extends Dialect {
    /** 无状态单例，{@link PostgresDatabase} 使用它。 */
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    /** 不必加引号的标识符：小写字母、数字、下划线和 {@code $}，且不以数字开头。 */
    private static final Pattern BARE = Pattern.compile("[a-z_][a-z0-9_$]*");

    /** 数据库没有配置提取行数时，流式读取每次往返取的行数。 */
    static final int STREAM_FETCH_SIZE = 1000;

    /** 美元引用的分隔符：标签遵循名字的规则，但不含美元符号。 */
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$(?:[\\p{L}_][\\p{L}\\p{N}_]*)?\\$");

    /**
     * PostgreSQL 没有 LOCATE，改写为 STRPOS；日期部分写成 EXTRACT 再转成整数（EXTRACT 返回 numeric），
     * 加天数是 {@code date + n}，加月和年乘以 INTERVAL 后转回 DATE，相隔天数是两个日期相减，
     * 相隔秒数取两个时间戳之差的 EPOCH 再舍去小数（直接转 BIGINT 会四舍五入）；秒取 EXTRACT 的整数部分，
     * 加秒数乘以 INTERVAL，月初用 DATE_TRUNC。
     */
    protected PostgresDialect() {
        register(Sql.LOCATE, (w, args) -> FunctionDef.call(w, "STRPOS", args));
        register(Sql.YEAR, (w, args) -> extract(w, args, "YEAR"));
        register(Sql.MONTH, (w, args) -> extract(w, args, "MONTH"));
        register(Sql.DAY, (w, args) -> extract(w, args, "DAY"));
        register(Sql.ADD_DAYS, (w, args) -> w.sql("(").render(args.get(0)).sql(" + ").render(args.get(1)).sql(")"));
        register(Sql.ADD_MONTHS, (w, args) -> addInterval(w, args, "month"));
        register(Sql.ADD_YEARS, (w, args) -> addInterval(w, args, "year"));
        register(Sql.DAYS_BETWEEN, (w, args) -> w.sql("(").render(args.get(1)).sql(" - ").render(args.get(0)).sql(")"));
        register(Sql.HOUR, (w, args) -> extract(w, args, "HOUR"));
        register(Sql.MINUTE, (w, args) -> extract(w, args, "MINUTE"));
        register(Sql.SECOND, (w, args) -> w.sql("CAST(FLOOR(EXTRACT(SECOND FROM ").render(args.get(0)).sql(")) AS INTEGER)"));
        register(Sql.ADD_SECONDS, (w, args) -> w.sql("(").render(args.get(0)).sql(" + ").render(args.get(1)).sql(" * INTERVAL '1 second')"));
        register(Sql.MONTH_START, (w, args) -> w.sql("CAST(DATE_TRUNC('month', ").render(args.get(0)).sql(") AS DATE)"));
        register(Sql.SECONDS_BETWEEN, (w, args) ->
            w.sql("CAST(TRUNC(EXTRACT(EPOCH FROM (").render(args.get(1)).sql(" - ").render(args.get(0)).sql("))) AS BIGINT)"));
    }

    /** 写出 {@code CAST(EXTRACT(FIELD FROM e) AS INTEGER)}。 */
    private static void extract(SqlWriter w, List<? extends Renderable> args, String field) {
        w.sql("CAST(EXTRACT(" + field + " FROM ").render(args.get(0)).sql(") AS INTEGER)");
    }

    /** 写出 {@code CAST(date + n * INTERVAL '1 unit' AS DATE)}。 */
    private static void addInterval(SqlWriter w, List<? extends Renderable> args, String unit) {
        w.sql("CAST(").render(args.get(0)).sql(" + ").render(args.get(1)).sql(" * INTERVAL '1 " + unit + "' AS DATE)");
    }

    /** 按列名请求生成键：驱动在语句后加上 {@code RETURNING "列"}。 */
    @Override public PreparedStatement prepareInsert(Connection connection, String sql, String column) throws SQLException {
        return connection.prepareStatement(sql, new String[] {column});
    }

    /** {@code INSERT ... ON CONFLICT (键) DO UPDATE SET 列 = EXCLUDED.列} 或 {@code DO NOTHING}。 */
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
            w.identifier(c).sql(" = EXCLUDED.").identifier(c);
        }
        for (var a : u.assignments().entrySet()) {
            if (!first) w.sql(", ");
            first = false;
            w.identifier(a.getKey()).sql(" = ").render(a.getValue());
        }
    }

    /** 方言名，用于异常信息和监听事件。 */
    @Override public String name() { return "PostgreSQL"; }

    /** 只含小写字母、数字、下划线和 {@code $} 的名字无需加引号。 */
    @Override protected boolean isBare(String identifier) { return BARE.matcher(identifier).matches(); }

    /** PostgreSQL 把不加引号的标识符折叠为小写。 */
    @Override public String foldCase(String identifier) { return identifier.toLowerCase(); }

    /**
     * standard_conforming_strings 关闭时，'...' 里的反斜杠会转义下一个字符，取值就可能提前结束字面量。
     * E'...' 在两种设置下读反斜杠的方式一致，所以含反斜杠的值写成 E'...'。
     */
    @Override protected String stringLiteral(String s) {
        return s.indexOf('\\') < 0 ? super.stringLiteral(s) : "E'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** pgjdbc 把超时报告为 query_canceled（57014），因为它通过另一个请求来取消语句。 */
    @Override public boolean isTimeout(SQLException e) { return super.isTimeout(e) || "57014".equals(e.getSQLState()); }

    /** NULL 也相等的比较写成原生的 {@code IS [NOT] DISTINCT FROM}。 */
    @Override public void notDistinct(SqlWriter w, Renderable left, Renderable right, boolean negate) {
        w.render(left).sql(negate ? " IS DISTINCT FROM " : " IS NOT DISTINCT FROM ").render(right);
    }

    /** CAST 的目标类型：小数写成 NUMERIC(精度,小数位)，其余沿用默认。 */
    @Override public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case DECIMAL -> "NUMERIC(" + type.length() + "," + type.scale() + ")";
            default -> super.castType(type);
        };
    }

    /** 除默认的引号外，还识别 $$...$$ 和 $tag$...$tag$（如函数体）；$1 或名字内部的 $ 不算开头。 */
    @Override public int quotedEnd(String sql, int start) {
        if (sql.charAt(start) != '$') return super.quotedEnd(sql, start);
        if (start > 0 && isNamePart(sql.charAt(start - 1))) return -1;
        Matcher tag = DOLLAR_TAG.matcher(sql).region(start, sql.length());
        if (!tag.lookingAt()) return -1;
        int close = sql.indexOf(tag.group(), tag.end());
        return close < 0 ? sql.length() - 1 : close + tag.group().length() - 1;
    }

    /** 字符是否可以出现在名字里（字母、数字、下划线、美元符号）。 */
    private static boolean isNamePart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    /**
     * pgJDBC 只有在事务内且提取行数为正时才通过游标读取，否则会把整个结果缓冲起来。
     * 外层已有事务时保持原样，不去改动它。
     */
    @Override public CursorCleanup openCursor(Connection connection, PreparedStatement statement) throws SQLException {
        if (statement.getFetchSize() <= 0) statement.setFetchSize(STREAM_FETCH_SIZE);
        if (!connection.getAutoCommit()) return CursorCleanup.NONE;
        connection.setAutoCommit(false);
        return () -> {
            try {
                connection.rollback(); // 流只做了读取，回滚即可
            } finally {
                connection.setAutoCommit(true);
            }
        };
    }

    /** {@code UPDATE 表 别名 SET 列 = 值 FROM 连接表 别名 WHERE 连接条件 AND ...}；SET 左边的列不带别名（PostgreSQL 不允许）。 */
    @Override public void joinUpdate(SqlWriter w, JoinWrite u) {
        w.sql("UPDATE ").render(u.target()).sql("\nSET ");
        for (int i = 0; i < u.columns().size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(u.columns().get(i)).sql(" = ").render(u.values().get(i));
        }
        w.sql("\nFROM ").render(u.source()).sql("\nWHERE ");
        u.renderOnAndWhere(w);
    }

    /** {@code DELETE FROM 表 别名 USING 连接表 别名 WHERE 连接条件 AND ...}。 */
    @Override public void joinDelete(SqlWriter w, JoinWrite d) {
        w.sql("DELETE FROM ").render(d.target()).sql("\nUSING ").render(d.source()).sql("\nWHERE ");
        d.renderOnAndWhere(w);
    }
}
