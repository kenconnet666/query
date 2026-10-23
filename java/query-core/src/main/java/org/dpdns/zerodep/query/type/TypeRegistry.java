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
 * 每种存储类型如何从 ResultSet 读出、如何绑定到 PreparedStatement。不可变：
 * 方言从 {@link #defaults()} 出发，覆盖驱动处理不对的类型；数据库实例还可以再覆盖一次。
 */
public final class TypeRegistry {
    /**
     * 从结果集的某一列读出存储类型的值。
     *
     * @param <S> 存储类型
     */
    @FunctionalInterface
    public interface Reader<S> {
        /** 读取第 {@code index} 列（从 1 开始）；SQL NULL 返回 null。 */
        S read(ResultSet rs, int index) throws SQLException;
    }

    /**
     * 把存储类型的值绑定到语句的某个参数位置。
     *
     * @param <S> 存储类型
     */
    @FunctionalInterface
    public interface Writer<S> {
        /** 把非 null 的 {@code value} 绑定到第 {@code index} 个参数（从 1 开始）。 */
        void write(PreparedStatement ps, int index, S value) throws SQLException;
    }

    /** 一种存储类型的读写方式，以及绑定 NULL 时使用的 {@link Types} 类型码。 */
    private record Entry(Reader<?> reader, Writer<?> writer, int nullType) { }

    private static final TypeRegistry DEFAULTS = buildDefaults();

    /** 各 Java 类型的占位值，见 {@link #placeholder}。 */
    private static final Map<Class<?>, Object> PLACEHOLDERS = Map.ofEntries(
        Map.entry(String.class, ""), Map.entry(Integer.class, 0), Map.entry(Long.class, 0L),
        Map.entry(Short.class, (short) 0), Map.entry(Byte.class, (byte) 0), Map.entry(Double.class, 0.0),
        Map.entry(Float.class, 0f), Map.entry(BigDecimal.class, BigDecimal.ZERO), Map.entry(BigInteger.class, BigInteger.ZERO),
        Map.entry(Boolean.class, false), Map.entry(Character.class, ' '), Map.entry(LocalDate.class, LocalDate.EPOCH),
        Map.entry(LocalDateTime.class, LocalDateTime.of(LocalDate.EPOCH, LocalTime.MIDNIGHT)),
        Map.entry(LocalTime.class, LocalTime.MIDNIGHT), Map.entry(Instant.class, Instant.EPOCH));

    private final Map<Class<?>, Entry> entries;

    /** 由本类的工厂方法创建。 */
    private TypeRegistry(Map<Class<?>, Entry> entries) { this.entries = Map.copyOf(entries); }

    /** 通用 JDBC 类型的默认注册表。 */
    public static TypeRegistry defaults() { return DEFAULTS; }

    /** 没有任何条目；用作只覆盖少数类型、不应重置其他内容的覆盖层的基础。 */
    public static TypeRegistry empty() { return EMPTY; }

    private static final TypeRegistry EMPTY = new TypeRegistry(Map.of());

    /**
     * 返回覆盖了 {@code type} 的读写方式的新注册表。该类型已有的 NULL 类型码会保留，
     * 没有时用 {@link Types#OTHER}。
     */
    public <S> TypeRegistry with(Class<S> type, Reader<S> reader, Writer<S> writer) {
        Map<Class<?>, Entry> copy = new HashMap<>(entries);
        Entry old = entries.get(type);
        copy.put(type, new Entry(reader, writer, old == null ? Types.OTHER : old.nullType()));
        return new TypeRegistry(copy);
    }

    /** 返回新注册表：{@code overrides} 中的条目替换本注册表里同一类型的条目。 */
    public TypeRegistry with(TypeRegistry overrides) {
        Map<Class<?>, Entry> copy = new HashMap<>(entries);
        copy.putAll(overrides.entries);
        return new TypeRegistry(copy);
    }

    /** 按存储类型读取结果集的一列；未登记的类型退回 {@code rs.getObject(index, storageType)}。 */
    public Object read(ResultSet rs, int index, Class<?> storageType) throws SQLException {
        Entry e = entries.get(storageType);
        if (e != null) return e.reader().read(rs, index);
        return rs.getObject(index, storageType);
    }

    /** 按存储类型绑定参数；值为 null 时绑定对应类型码的 NULL，未登记的类型退回 {@code ps.setObject}。 */
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

    /** 录制投影时各 Java 类型使用的占位值；枚举取第一个常量，不认识的类型返回 null。 */
    public static Object placeholder(Class<?> javaType) {
        Object value = PLACEHOLDERS.get(javaType);
        if (value != null) return value;
        if (javaType.isEnum()) {
            Object[] constants = javaType.getEnumConstants();
            return constants.length == 0 ? null : constants[0];
        }
        return null;
    }

    /** 构建默认条目。基本类型用 {@code wasNull} 区分 SQL NULL 与 0；BigInteger 经 DECIMAL 读写。 */
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

    /** 登记一个默认条目。 */
    private static <S> void put(Map<Class<?>, Entry> m, Class<S> type, Reader<S> reader, Writer<S> writer, int nullType) {
        m.put(type, new Entry(reader, writer, nullType));
    }

    /** JDBC 的基本类型 getter 读到 SQL NULL 会返回 0，这里据 {@code wasNull} 还原成 null。 */
    private static <V> V nullable(ResultSet rs, V value) throws SQLException {
        return rs.wasNull() ? null : value;
    }
}
