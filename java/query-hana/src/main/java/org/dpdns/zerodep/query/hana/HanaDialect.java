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
 * SAP HANA 2.0。不加引号的标识符折叠为大写。
 *
 * <p>日期和时间戳通过 UTC 日历绑定和读取：否则 ngdbc 会按 JVM 默认时区平移部分取值
 * （前身项目已在 ngdbc 2.30.11 上验证）。
 */
public class HanaDialect extends Dialect {
    /** 无状态单例，{@link HanaDatabase} 使用它。 */
    public static final HanaDialect INSTANCE = new HanaDialect();

    /** 不必加引号的标识符：大写字母、数字、下划线、{@code #} 和 {@code $}，且不以数字开头。 */
    private static final Pattern BARE = Pattern.compile("[A-Z_][A-Z0-9_#$]*");

    /** 在默认类型注册表上覆盖日期和时间戳的读写，一律按 UTC 日历。 */
    private static final TypeRegistry TYPES = TypeRegistry.defaults()
        .with(LocalDate.class, (rs, i) -> {
            java.sql.Date d = rs.getDate(i, utc());
            return d == null ? null : Instant.ofEpochMilli(d.getTime()).atOffset(ZoneOffset.UTC).toLocalDate();
        }, (ps, i, v) -> ps.setDate(i, new java.sql.Date(v.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()), utc()))
        .with(LocalDateTime.class, (rs, i) -> {
            Timestamp t = rs.getTimestamp(i, utc());
            return t == null ? null : LocalDateTime.ofEpochSecond(Math.floorDiv(t.getTime(), 1000L), t.getNanos(), ZoneOffset.UTC);
        }, (ps, i, v) -> {
            // HANA 的 TIMESTAMP 精度为 100 纳秒，更细的部分不静默截断，直接报错
            if (v.getNano() % 100 != 0) throw new IllegalArgumentException("HANA TIMESTAMP keeps 100 ns; " + v + " would be truncated");
            Timestamp t = new Timestamp(v.toEpochSecond(ZoneOffset.UTC) * 1000L);
            t.setNanos(v.getNano());
            ps.setTimestamp(i, t, utc());
        });

    protected HanaDialect() { }

    /** 方言名，用于异常信息和监听事件。 */
    @Override public String name() { return "SAP HANA"; }

    /** 只含大写字母、数字、下划线、{@code #} 和 {@code $} 的名字无需加引号。 */
    @Override protected boolean isBare(String identifier) { return BARE.matcher(identifier).matches(); }

    /** HANA 把不加引号的标识符折叠为大写。 */
    @Override public String foldCase(String identifier) { return identifier.toUpperCase(); }

    /** HANA 不允许没有 FROM 的 SELECT；DUMMY 是它的单行表。 */
    @Override public void fromNothing(SqlWriter w) { w.sql("\nFROM DUMMY"); }

    /** 含非 ASCII 字符时写成 N'...'，以保证文本不被破坏。 */
    @Override protected String stringLiteral(String s) {
        String quoted = "'" + s.replace("'", "''") + "'";
        return s.chars().allMatch(c -> c < 128) ? quoted : "N" + quoted;
    }

    /** CAST 的目标类型：文本用 NVARCHAR，浮点用 DOUBLE，其余沿用默认。 */
    @Override public String castType(CastType<?> type) {
        return switch (type.kind()) {
            case TEXT -> "NVARCHAR(" + type.length() + ")";
            case DOUBLE -> "DOUBLE";
            default -> super.castType(type);
        };
    }

    /** 绑定与读取参数所用的类型注册表。 */
    @Override public TypeRegistry types() { return TYPES; }

    /** 每次新建一个 UTC 日历，因为 {@link Calendar} 可变且不是线程安全的。 */
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)); }
}
