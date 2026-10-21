package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An open base class for dialects. It owns only syntax: identifier quoting and case folding,
 * literals, paging, CAST type names and JDBC type handling. Function renderings a dialect knows are
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

    public String quote(String identifier) {
        return isBare(identifier) ? identifier : '"' + identifier.replace("\"", "\"\"") + '"';
    }

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

    public TypeRegistry types() { return TypeRegistry.defaults(); }

    protected final void register(FunctionDef function, FunctionRenderer renderer) { functions.put(function, renderer); }

    public final FunctionRenderer renderer(FunctionDef function) { return functions.get(function); }

    @Override public String toString() { return name(); }
}
