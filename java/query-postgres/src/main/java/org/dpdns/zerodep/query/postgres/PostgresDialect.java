package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.type.CastType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PostgreSQL. Unquoted identifiers fold to lower case. */
public class PostgresDialect extends Dialect {
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private static final Pattern BARE = Pattern.compile("[a-z_][a-z0-9_$]*");

    /** Rows per round trip of a stream when the database has no fetch size configured. */
    static final int STREAM_FETCH_SIZE = 1000;

    /** A dollar-quote delimiter: the tag follows the rules of a name, without dollar signs. */
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$(?:[\\p{L}_][\\p{L}\\p{N}_]*)?\\$");

    protected PostgresDialect() { }

    @Override public String name() { return "PostgreSQL"; }

    @Override protected boolean isBare(String identifier) { return BARE.matcher(identifier).matches(); }

    @Override public String foldCase(String identifier) { return identifier.toLowerCase(); }

    @Override public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case DECIMAL -> "NUMERIC(" + type.length() + "," + type.scale() + ")";
            default -> super.castType(type);
        };
    }

    /** Also $$...$$ and $tag$...$tag$, as in function bodies; $1 or a $ inside a name starts none. */
    @Override public int quotedEnd(String sql, int start) {
        if (sql.charAt(start) != '$') return super.quotedEnd(sql, start);
        if (start > 0 && isNamePart(sql.charAt(start - 1))) return -1;
        Matcher tag = DOLLAR_TAG.matcher(sql).region(start, sql.length());
        if (!tag.lookingAt()) return -1;
        int close = sql.indexOf(tag.group(), tag.end());
        return close < 0 ? sql.length() - 1 : close + tag.group().length() - 1;
    }

    private static boolean isNamePart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    /**
     * pgJDBC reads from a cursor only inside a transaction and with a positive fetch size; otherwise it
     * buffers the whole result. An outer transaction is left as it is.
     */
    @Override public CursorCleanup openCursor(Connection connection, PreparedStatement statement) throws SQLException {
        if (statement.getFetchSize() <= 0) statement.setFetchSize(STREAM_FETCH_SIZE);
        if (!connection.getAutoCommit()) return CursorCleanup.NONE;
        connection.setAutoCommit(false);
        return () -> {
            try {
                connection.rollback(); // the stream only read
            } finally {
                connection.setAutoCommit(true);
            }
        };
    }
}
