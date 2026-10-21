package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * SAP HANA 2.0. Unquoted identifiers fold to upper case.
 *
 * <p>Dates and timestamps are bound and read through a UTC calendar: ngdbc otherwise shifts some
 * values through the JVM's default time zone (verified by the predecessor project on ngdbc 2.30.11).
 */
public class HanaDialect extends Dialect {
    public static final HanaDialect INSTANCE = new HanaDialect();

    private static final Pattern BARE = Pattern.compile("[A-Z_][A-Z0-9_#$]*");

    private static final TypeRegistry TYPES = TypeRegistry.defaults()
        .with(LocalDate.class, (rs, i) -> {
            java.sql.Date d = rs.getDate(i, utc());
            return d == null ? null : Instant.ofEpochMilli(d.getTime()).atOffset(ZoneOffset.UTC).toLocalDate();
        }, (ps, i, v) -> ps.setDate(i, new java.sql.Date(v.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()), utc()))
        .with(LocalDateTime.class, (rs, i) -> {
            Timestamp t = rs.getTimestamp(i, utc());
            return t == null ? null : LocalDateTime.ofEpochSecond(Math.floorDiv(t.getTime(), 1000L), t.getNanos(), ZoneOffset.UTC);
        }, (ps, i, v) -> {
            if (v.getNano() % 100 != 0) throw new IllegalArgumentException("HANA TIMESTAMP keeps 100 ns; " + v + " would be truncated");
            Timestamp t = new Timestamp(v.toEpochSecond(ZoneOffset.UTC) * 1000L);
            t.setNanos(v.getNano());
            ps.setTimestamp(i, t, utc());
        });

    protected HanaDialect() { }

    @Override public String name() { return "SAP HANA"; }

    @Override protected boolean isBare(String identifier) { return BARE.matcher(identifier).matches(); }

    @Override public String foldCase(String identifier) { return identifier.toUpperCase(); }

    /** HANA has no SELECT without FROM; DUMMY is its one-row table. */
    @Override public void fromNothing(SqlWriter w) { w.sql("\nFROM DUMMY"); }

    /** N'...' keeps non-ASCII text intact. */
    @Override protected String stringLiteral(String s) {
        String quoted = "'" + s.replace("'", "''") + "'";
        return s.chars().allMatch(c -> c < 128) ? quoted : "N" + quoted;
    }

    @Override public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case TEXT -> "NVARCHAR(" + type.length() + ")";
            case DOUBLE -> "DOUBLE";
            default -> super.castType(type);
        };
    }

    @Override public TypeRegistry types() { return TYPES; }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)); }
}
