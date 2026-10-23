package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.type.CastType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    /** PostgreSQL 没有 LOCATE，改写为 STRPOS。 */
    protected PostgresDialect() {
        register(Sql.LOCATE, (w, args) -> FunctionDef.call(w, "STRPOS", args));
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
}
