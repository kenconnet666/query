package org.dpdns.zerodep.wire;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 线上的 typed JSON：Long、BigInteger、BigDecimal、java.time 的值、字节等 JSON 不能原样承载（或 JavaScript 读了会丢精度）的值
 * 写成文字，根对象上的 {@code $types} 记下它们的位置，对方据此还原（种类见 {@link WireType}）。与 SQL 无关，任何接口都可以用；
 * TypeScript 的 {@code @zerodep-dev/wire}、vue-tsx-pro 的请求层用同一套写法，{@code spec/wire} 是三边共用的样例。
 *
 * <pre>{@code
 * @GetMapping("/orders/{id}")
 * Object order(@PathVariable long id) { return Wire.encode(service.find(id)); }   // Jackson、Gson 都能原样写出
 * }</pre>
 *
 * 编码的结果只由 Map、List、String、Number、Boolean 和 null 组成，所以不依赖哪一个 JSON 库，也不受它的配置影响。
 * 读请求不需要这里：Spring 的 Jackson 本来就把 {@code "12.50"}、{@code "9007199254740993"}、ISO 日期读成 BigDecimal、Long、LocalDate，
 * Spring Boot 默认忽略不认识的 {@code $types}。地址栏里的单个值用 {@link #parse}。
 */
public final class Wire {
    /** 根对象上的类型表。 */
    public static final String TYPES = "$types";

    /** 根不是对象（列表、单个值）又有要还原的值时，包成 {@code {"$value": 根, "$types": …}}。 */
    public static final String VALUE = "$value";

    /** 时间写到秒，秒以下的部分去掉末尾的零。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_TIME;

    /** 日期时间写成 'yyyy-MM-ddTHH:mm:ss[.fraction]'。 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 带时差的时刻换算成 UTC 后写成 'yyyy-MM-ddTHH:mm:ss[.fraction]Z'。 */
    private static final DateTimeFormatter TIMESTAMPTZ = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 工具类，不能实例化。 */
    private Wire() { }

    /**
     * 把值写成线上的 JSON 树：要还原的值写成文字，根对象上加 {@code $types}；没有这样的值时就是普通的 JSON 树。
     * record 取组件，其余的类取公开的 getter（{@code getX}、布尔的 {@code isX}）和公开字段，Map 的键写成文字，
     * 集合与数组写成列表，枚举写成名字，Optional 取里面的值。同一路径上的值必须是同一种，循环引用报错。
     *
     * @return 由 Map、List、String、Number、Boolean 和 null 组成的树
     */
    public static Object encode(Object value) {
        if (value instanceof Map<?, ?> m && (m.containsKey(TYPES) || m.containsKey(VALUE))) {
            throw new IllegalArgumentException("Wire.encode: " + TYPES + " and " + VALUE + " are reserved on the root object");
        }
        Encoder encoder = new Encoder();
        Object json = encoder.write(value, "");
        Map<String, Object> types = new LinkedHashMap<>();
        encoder.found.forEach((path, type) -> { if (type != null) types.put(path, type.wireName()); });
        if (types.isEmpty()) return json;
        if (json instanceof Map<?, ?> object) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) object;
            root.put(TYPES, types);
            return root;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        types.forEach((path, type) -> wrapped.put(path.isEmpty() ? VALUE : VALUE + "." + path, type));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(VALUE, json);
        root.put(TYPES, wrapped);
        return root;
    }

    /** 值要还原成哪一种；JSON 能原样承载的值（文字、布尔、Integer、有限的 Double、null）为 null。 */
    public static WireType typeOf(Object value) {
        if (value instanceof Long || value instanceof BigInteger) return WireType.BIGINT;
        if (value instanceof BigDecimal) return WireType.DECIMAL;
        if (value instanceof Double d) return d.isNaN() || d.isInfinite() ? WireType.DOUBLE : null;
        if (value instanceof Float f) return f.isNaN() || f.isInfinite() ? WireType.DOUBLE : null;
        if (value instanceof java.sql.Date || value instanceof LocalDate) return WireType.DATE;
        if (value instanceof java.sql.Time || value instanceof LocalTime) return WireType.TIME;
        if (value instanceof java.sql.Timestamp || value instanceof LocalDateTime) return WireType.TIMESTAMP;
        if (value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant || value instanceof java.util.Date) {
            return WireType.TIMESTAMPTZ;
        }
        if (value instanceof byte[]) return WireType.BYTES;
        return null;
    }

    /**
     * 一个值写成线上的文字：地址栏参数、表单字段都用它。BigDecimal 不用科学计数法，日期与时间是 ISO 写法，
     * 带时差的时刻换算成 UTC（{@code Z}），字节是 base64，枚举是名字；null 是空字符串。集合、对象等不是单个值的报错。
     */
    public static String text(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence || value instanceof Character || value instanceof Boolean || value instanceof UUID) return value.toString();
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() : value.toString();
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return value.toString();
        WireType type = typeOf(value);
        if (type == null) throw new IllegalArgumentException("Wire.text: " + value.getClass().getSimpleName() + " is not a single value");
        return special(type, value);
    }

    /** 要还原的值写成的文字；type 是 {@link #typeOf} 的结果。 */
    private static String special(WireType type, Object value) {
        return switch (type) {
            case BIGINT, DOUBLE -> value.toString();
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case DATE -> (value instanceof java.sql.Date d ? d.toLocalDate() : value).toString();
            case TIME -> TIME.format(value instanceof java.sql.Time t ? t.toLocalTime() : (LocalTime) value);
            case TIMESTAMP -> TIMESTAMP.format(value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value);
            case TIMESTAMPTZ -> TIMESTAMPTZ.format(instant(value).atOffset(ZoneOffset.UTC));
            case BYTES -> Base64.getEncoder().encodeToString((byte[]) value);
        };
    }

    /** 带时差的时刻对应的瞬间。 */
    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof ZonedDateTime z) return z.toInstant();
        if (value instanceof Instant i) return i;
        return ((java.util.Date) value).toInstant();
    }

    /**
     * 按 Java 类读线上的文字（地址栏参数、表头筛选的值），格式与 {@link #text} 写出的一致：整数不能有小数部分，
     * 日期要是真实存在的日历日期，日期时间也接受空格分隔，带时差的时刻要带 {@code Z} 或时差，枚举按名字。读不出时报错，消息里带上原文。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T parse(Class<T> type, String text) {
        if (text == null) throw new IllegalArgumentException("Wire.parse: text is null");
        Class<?> t = box(type);
        try {
            Object value;
            if (t == String.class) value = text;
            else if (t == Integer.class) value = Integer.valueOf(text);
            else if (t == Long.class) value = Long.valueOf(text);
            else if (t == Short.class) value = Short.valueOf(text);
            else if (t == Byte.class) value = Byte.valueOf(text);
            else if (t == BigInteger.class) value = new BigInteger(text);
            else if (t == BigDecimal.class) value = new BigDecimal(text);
            else if (t == Double.class) value = Double.valueOf(text);
            else if (t == Float.class) value = Float.valueOf(text);
            else if (t == Boolean.class) value = bool(text);
            else if (t == LocalDate.class) value = LocalDate.parse(text);
            else if (t == LocalTime.class) value = LocalTime.parse(text);
            else if (t == LocalDateTime.class) value = LocalDateTime.parse(text.replaceFirst(" ", "T"));
            else if (t == OffsetDateTime.class) value = OffsetDateTime.parse(text);
            else if (t == Instant.class) value = OffsetDateTime.parse(text).toInstant();
            else if (t == ZonedDateTime.class) value = ZonedDateTime.parse(text);
            else if (t == UUID.class) value = UUID.fromString(text);
            else if (t == byte[].class) value = Base64.getDecoder().decode(text);
            else if (t.isEnum()) value = Enum.valueOf((Class) t, text);
            else throw new IllegalArgumentException("Wire.parse: cannot read text as " + type.getSimpleName());
            return (T) value;
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a " + type.getSimpleName(), e);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Wire.parse")) throw e;
            throw new IllegalArgumentException("\"" + text + "\" is not a " + type.getSimpleName(), e);
        }
    }

    /** 只认 true 与 false。 */
    private static Boolean bool(String text) {
        if (text.equals("true")) return Boolean.TRUE;
        if (text.equals("false")) return Boolean.FALSE;
        throw new IllegalArgumentException("\"" + text + "\" is not true or false");
    }

    /** 基本类型装箱为对应的包装类。 */
    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        return type;
    }

    /** 一次编码：沿路径写下各个值，记下每个路径上值的种类（null 表示文字）。 */
    private static final class Encoder {
        /** 路径 → 种类；文字记为 null，用来发现同一路径既有文字又有要还原的值。 */
        final Map<String, WireType> found = new LinkedHashMap<>();
        /** 正在写的对象，发现循环引用。 */
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

        /** 写下 value，path 是它的路径（属性名里的 ~ 与 . 写成 ~0、~1；列表不占一段）。 */
        Object write(Object value, String path) {
            if (value == null) return null;
            if (value instanceof Optional<?> o) return write(o.orElse(null), path);
            if (value instanceof CharSequence || value instanceof Character || value instanceof UUID || value instanceof Enum<?>) {
                note(path, null);
                return value instanceof Enum<?> e ? e.name() : value.toString();
            }
            if (value instanceof Boolean) return value;
            WireType type = typeOf(value);
            if (type != null) {
                note(path, type);
                return special(type, value);
            }
            if (value instanceof Number) return value;
            if (!active.add(value)) throw new IllegalArgumentException("Wire.encode: " + (path.isEmpty() ? "the root" : path) + " refers back to itself");
            try {
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    map.forEach((k, v) -> out.put(String.valueOf(k), write(v, join(path, String.valueOf(k)))));
                    return out;
                }
                if (value instanceof Iterable<?> items) {
                    List<Object> out = new ArrayList<>();
                    for (Object item : items) out.add(write(item, path));
                    return out;
                }
                if (value.getClass().isArray()) {
                    List<Object> out = new ArrayList<>();
                    for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) out.add(write(java.lang.reflect.Array.get(value, i), path));
                    return out;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Properties.Property p : Properties.of(value.getClass())) out.put(p.name(), write(p.read(value), join(path, p.name())));
                return out;
            } finally {
                active.remove(value);
            }
        }

        /** 记下 path 上的值的种类；同一路径出现两种时报错。 */
        private void note(String path, WireType type) {
            if (found.containsKey(path) && found.get(path) != type) {
                throw new IllegalArgumentException("Wire.encode: values at " + (path.isEmpty() ? "the root" : path) + " are both "
                    + name(found.get(path)) + " and " + name(type) + "; one path carries one kind of value");
            }
            found.put(path, type);
        }

        /** 报错时种类的名字，文字是 text。 */
        private static String name(WireType type) { return type == null ? "text" : type.wireName(); }

        /** path 下的属性 key 的路径。 */
        private static String join(String path, String key) {
            String step = key.replace("~", "~0").replace(".", "~1");
            return path.isEmpty() ? step : path + "." + step;
        }
    }
}
