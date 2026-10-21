package org.dpdns.zerodep.query.type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * How each storage type is read from a ResultSet and bound to a PreparedStatement. Immutable: a
 * dialect starts from {@link #defaults()} and overrides what its driver gets wrong; a database
 * instance can override again.
 */
public final class TypeRegistry {
    @FunctionalInterface
    public interface Reader<S> {
        S read(ResultSet rs, int index) throws SQLException;
    }

    @FunctionalInterface
    public interface Writer<S> {
        void write(PreparedStatement ps, int index, S value) throws SQLException;
    }

    private record Entry(Reader<?> reader, Writer<?> writer, int nullType) { }

    private static final TypeRegistry DEFAULTS = buildDefaults();

    private static final Map<Class<?>, Object> PLACEHOLDERS = Map.ofEntries(
        Map.entry(String.class, ""), Map.entry(Integer.class, 0), Map.entry(Long.class, 0L),
        Map.entry(Short.class, (short) 0), Map.entry(Byte.class, (byte) 0), Map.entry(Double.class, 0.0),
        Map.entry(Float.class, 0f), Map.entry(BigDecimal.class, BigDecimal.ZERO), Map.entry(BigInteger.class, BigInteger.ZERO),
        Map.entry(Boolean.class, false), Map.entry(Character.class, ' '), Map.entry(LocalDate.class, LocalDate.EPOCH),
        Map.entry(LocalDateTime.class, LocalDateTime.of(LocalDate.EPOCH, LocalTime.MIDNIGHT)),
        Map.entry(LocalTime.class, LocalTime.MIDNIGHT), Map.entry(Instant.class, Instant.EPOCH));

    private final Map<Class<?>, Entry> entries;

    private TypeRegistry(Map<Class<?>, Entry> entries) { this.entries = Map.copyOf(entries); }

    public static TypeRegistry defaults() { return DEFAULTS; }

    /** No entries; a base for overrides that must not reset anything else. */
    public static TypeRegistry empty() { return EMPTY; }

    private static final TypeRegistry EMPTY = new TypeRegistry(Map.of());

    public <S> TypeRegistry with(Class<S> type, Reader<S> reader, Writer<S> writer) {
        Map<Class<?>, Entry> copy = new HashMap<>(entries);
        Entry old = entries.get(type);
        copy.put(type, new Entry(reader, writer, old == null ? Types.OTHER : old.nullType()));
        return new TypeRegistry(copy);
    }

    /** Entries of {@code overrides} replace this registry's entries for the same types. */
    public TypeRegistry with(TypeRegistry overrides) {
        Map<Class<?>, Entry> copy = new HashMap<>(entries);
        copy.putAll(overrides.entries);
        return new TypeRegistry(copy);
    }

    public Object read(ResultSet rs, int index, Class<?> storageType) throws SQLException {
        Entry e = entries.get(storageType);
        if (e != null) return e.reader().read(rs, index);
        return rs.getObject(index, storageType);
    }

    @SuppressWarnings("unchecked")
    public void write(PreparedStatement ps, int index, Class<?> storageType, Object value) throws SQLException {
        Entry e = entries.get(storageType);
        if (value == null) {
            ps.setNull(index, e == null ? Types.OTHER : e.nullType());
            return;
        }
        if (e != null) ((Writer<Object>) e.writer()).write(ps, index, value);
        else ps.setObject(index, value);
    }

    /** Stand-in values per Java type for recording projections; enums use their first constant. */
    public static Object placeholder(Class<?> javaType) {
        Object value = PLACEHOLDERS.get(javaType);
        if (value != null) return value;
        if (javaType.isEnum()) {
            Object[] constants = javaType.getEnumConstants();
            return constants.length == 0 ? null : constants[0];
        }
        return null;
    }

    private static TypeRegistry buildDefaults() {
        Map<Class<?>, Entry> m = new HashMap<>();
        put(m, String.class, ResultSet::getString, PreparedStatement::setString, Types.VARCHAR);
        put(m, Integer.class, (rs, i) -> nullable(rs, rs.getInt(i)), (ps, i, v) -> ps.setInt(i, v), Types.INTEGER);
        put(m, Long.class, (rs, i) -> nullable(rs, rs.getLong(i)), (ps, i, v) -> ps.setLong(i, v), Types.BIGINT);
        put(m, Short.class, (rs, i) -> nullable(rs, rs.getShort(i)), (ps, i, v) -> ps.setShort(i, v), Types.SMALLINT);
        put(m, Byte.class, (rs, i) -> nullable(rs, rs.getByte(i)), (ps, i, v) -> ps.setByte(i, v), Types.TINYINT);
        put(m, Double.class, (rs, i) -> nullable(rs, rs.getDouble(i)), (ps, i, v) -> ps.setDouble(i, v), Types.DOUBLE);
        put(m, Float.class, (rs, i) -> nullable(rs, rs.getFloat(i)), (ps, i, v) -> ps.setFloat(i, v), Types.REAL);
        put(m, Boolean.class, (rs, i) -> nullable(rs, rs.getBoolean(i)), (ps, i, v) -> ps.setBoolean(i, v), Types.BOOLEAN);
        put(m, BigDecimal.class, ResultSet::getBigDecimal, PreparedStatement::setBigDecimal, Types.DECIMAL);
        put(m, BigInteger.class, (rs, i) -> {
            BigDecimal d = rs.getBigDecimal(i);
            return d == null ? null : d.toBigIntegerExact();
        }, (ps, i, v) -> ps.setBigDecimal(i, new BigDecimal(v)), Types.DECIMAL);
        put(m, byte[].class, ResultSet::getBytes, PreparedStatement::setBytes, Types.VARBINARY);
        put(m, LocalDate.class, (rs, i) -> rs.getObject(i, LocalDate.class), PreparedStatement::setObject, Types.DATE);
        put(m, LocalDateTime.class, (rs, i) -> rs.getObject(i, LocalDateTime.class), PreparedStatement::setObject, Types.TIMESTAMP);
        put(m, LocalTime.class, (rs, i) -> rs.getObject(i, LocalTime.class), PreparedStatement::setObject, Types.TIME);
        return new TypeRegistry(m);
    }

    private static <S> void put(Map<Class<?>, Entry> m, Class<S> type, Reader<S> reader, Writer<S> writer, int nullType) {
        m.put(type, new Entry(reader, writer, nullType));
    }

    private static <V> V nullable(ResultSet rs, V value) throws SQLException {
        return rs.wasNull() ? null : value;
    }
}
