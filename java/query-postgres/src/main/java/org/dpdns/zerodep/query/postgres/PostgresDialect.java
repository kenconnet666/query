package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.type.CastType;

import java.util.regex.Pattern;

/** PostgreSQL. Unquoted identifiers fold to lower case. */
public class PostgresDialect extends Dialect {
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private static final Pattern BARE = Pattern.compile("[a-z_][a-z0-9_$]*");

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
}
