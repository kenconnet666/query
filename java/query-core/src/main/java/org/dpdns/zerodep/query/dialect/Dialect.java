package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An open base class for dialects. It owns only syntax: identifier quoting and case folding,
 * literals, paging, CAST type names, JDBC type handling and what a driver needs to stream rows. Function renderings a dialect knows are
 * registered in its constructor with {@link #register}; nothing switches on a dialect name.
 */
public abstract class Dialect {
    private final Map<FunctionDef, FunctionRenderer> functions = new ConcurrentHashMap<>();

    protected Dialect() { }

    public abstract String name();

    /** True when the identifier needs no quotes, e.g. HANA's upper-case names. */
    protected abstract boolean isBare(String identifier);

    /** The case unquoted identifiers fold to, so generated aliases stay unquoted. */
    public abstract String foldCase(String identifier);

    /**
     * Words that must be quoted even in bare form, such as an entity {@code Order} aliased {@code order}.
     * Reserved words of standard SQL, PostgreSQL and HANA together; quoting one more word is harmless.
     */
    protected boolean isReserved(String identifier) { return RESERVED.contains(identifier.toUpperCase(java.util.Locale.ROOT)); }

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

    /** Aliases come from {@link SqlWriter#aliasFor}, already case-folded. */
    public String alias(String alias) { return quote(alias); }

    public void limit(SqlWriter w, Integer limit, Integer offset) {
        if (limit != null) w.sql("\nLIMIT ").sql(Integer.toString(limit));
        if (offset != null && offset > 0) w.sql(limit == null ? "\nOFFSET " : " OFFSET ").sql(Integer.toString(offset));
    }

    /** The GROUP BY list; with rollup, subtotals for every prefix of the keys and a grand total. */
    public void groupBy(SqlWriter w, List<? extends Renderable> keys, boolean rollup) {
        if (rollup) w.sql("ROLLUP(").join(keys, ", ").sql(")");
        else w.join(keys, ", ");
    }

    /** One ORDER BY item; {@code nullsFirst} is null when the query does not specify NULL placement. */
    public void orderItem(SqlWriter w, Renderable expr, boolean descending, Boolean nullsFirst) {
        w.render(expr).sql(descending ? " DESC" : " ASC");
        if (nullsFirst != null) w.sql(nullsFirst ? " NULLS FIRST" : " NULLS LAST");
    }

    /** What a SELECT without a table needs: nothing on most databases, {@code FROM DUMMY} on HANA. */
    public void fromNothing(SqlWriter w) { }

    /** Whether {@link #literal} can write the value; others stay parameters even when inlining. */
    public boolean hasLiteral(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Number || value instanceof LocalDate
            || value instanceof LocalDateTime || value instanceof Enum<?>;
    }

    public void literal(SqlWriter w, Object value) {
        if (value == null) w.sql("NULL");
        else if (value instanceof String s) w.sql(stringLiteral(s));
        else if (value instanceof Boolean b) w.sql(b ? "TRUE" : "FALSE");
        else if (value instanceof BigDecimal d) w.sql(d.toPlainString());
        else if (value instanceof Number n) w.sql(n.toString());
        else if (value instanceof LocalDate d) w.sql("DATE '" + d + "'");
        else if (value instanceof LocalDateTime t) w.sql("TIMESTAMP '" + timestampText(t) + "'");
        else if (value instanceof Enum<?> e) w.sql(stringLiteral(e.name()));
        else throw new IllegalArgumentException("No literal form for " + value.getClass().getName() + "; bind it as a parameter");
    }

    protected String stringLiteral(String s) { return "'" + s.replace("'", "''") + "'"; }

    /** Always with seconds, the fraction without trailing zeros: the form the TypeScript port writes too. */
    private static String timestampText(LocalDateTime t) {
        String fraction = t.getNano() == 0 ? "" : ("." + String.format("%09d", t.getNano())).replaceAll("0+$", "");
        return String.format("%s %02d:%02d:%02d%s", t.toLocalDate(), t.getHour(), t.getMinute(), t.getSecond(), fraction);
    }

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

    /** Longer IN lists are split into several IN predicates joined by OR. */
    public int maxInListSize() { return 1000; }

    /**
     * Where a quoted part of hand-written SQL that starts at {@code start} ends: the index of its last
     * character, or -1 when none starts there. Native queries look for {@code :name} only outside such
     * parts. The default knows '...' and "..." with doubled quotes inside.
     */
    public int quotedEnd(String sql, int start) {
        char c = sql.charAt(start);
        return c == '\'' || c == '"' ? closingQuote(sql, start, false) : -1;
    }

    /**
     * The index of the quote closing the one at {@code start}, past doubled quotes and, with
     * {@code backslashEscapes}, past escaped characters; the last index when it never closes.
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

    public TypeRegistry types() { return TypeRegistry.defaults(); }

    /** Undoes what {@link #openCursor} changed on a connection; runs when the stream closes. */
    @FunctionalInterface
    public interface CursorCleanup {
        CursorCleanup NONE = () -> { };

        void run() throws SQLException;
    }

    /**
     * Readies a statement for {@code Database.stream} so that rows come from a server cursor instead
     * of a buffer of the whole result. Most drivers need nothing: HANA's fetches in chunks on its own.
     */
    public CursorCleanup openCursor(Connection connection, PreparedStatement statement) throws SQLException {
        return CursorCleanup.NONE;
    }

    protected final void register(FunctionDef function, FunctionRenderer renderer) { functions.put(function, renderer); }

    public final FunctionRenderer renderer(FunctionDef function) { return functions.get(function); }

    @Override public String toString() { return name(); }
}
