package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.CastType;

/**
 * MySQL 8 with its default SQL mode. Identifiers are always quoted with backticks (window function
 * names such as RANK are reserved words), {@code ||} is not concatenation, and backslashes in string
 * literals are escapes.
 */
public class MysqlDialect extends Dialect {
    public static final MysqlDialect INSTANCE = new MysqlDialect();

    protected MysqlDialect() {
        register(Sql.CONCAT, (w, args) -> FunctionDef.call(w, "CONCAT", args));
    }

    @Override public String name() { return "MySQL"; }

    @Override protected boolean isBare(String identifier) { return false; }

    @Override public String foldCase(String identifier) { return identifier; }

    @Override public String quote(String identifier) { return '`' + identifier.replace("`", "``") + '`'; }

    @Override protected String stringLiteral(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"; }

    /** MySQL needs LIMIT before OFFSET; the documented way to say "no limit" is the largest BIGINT UNSIGNED. */
    @Override public void limit(SqlWriter w, Integer limit, Integer offset) {
        boolean hasOffset = offset != null && offset > 0;
        if (limit == null && !hasOffset) return;
        w.sql("\nLIMIT ").sql(limit == null ? "18446744073709551615" : Integer.toString(limit));
        if (hasOffset) w.sql(" OFFSET ").sql(Integer.toString(offset));
    }

    /** No NULLS FIRST/LAST in MySQL: sort on "is null" first, which places NULLs the same way. */
    @Override public void orderItem(SqlWriter w, Renderable expr, boolean descending, Boolean nullsFirst) {
        if (nullsFirst != null) w.render(expr).sql(nullsFirst ? " IS NULL DESC, " : " IS NULL ASC, ");
        w.render(expr).sql(descending ? " DESC" : " ASC");
    }

    @Override public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case TEXT -> "CHAR(" + type.length() + ")";
            case INTEGER, BIGINT -> "SIGNED";
            case DOUBLE -> "DOUBLE";
            case TIMESTAMP -> "DATETIME(6)";
            case BOOLEAN -> throw new UnsupportedOperationException("MySQL has no CAST to BOOLEAN; compare the value instead");
            default -> super.castType(type);
        };
    }
}
