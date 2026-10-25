package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.JoinWrite;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.dialect.Upsert;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    /** 用 INSTANCE；子类可以改写个别写法。 */
    protected HanaDialect() { }

    /** 方言名，用于异常信息和监听事件。 */
    @Override public String name() { return "SAP HANA"; }

    /**
     * {@code MERGE INTO 表 AS T USING (SELECT ? AS 列, ... FROM DUMMY) AS S ON (T.键 = S.键 ...)
     * WHEN MATCHED THEN UPDATE SET T.列 = S.列 WHEN NOT MATCHED THEN INSERT (列) VALUES (S.列)}；
     * 冲突了什么也不做时省略 WHEN MATCHED。值只在 USING 的 SELECT 里写一次。
     */
    @Override public void upsert(SqlWriter w, Upsert u) {
        String target = w.aliasFor(new Object(), "target");
        String source = w.aliasFor(new Object(), "source");
        w.sql("MERGE INTO ").render(u.table()).sql(" AS ").alias(target).sql("\nUSING (SELECT ");
        for (int i = 0; i < u.columns().size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(u.values().get(i)).sql(" AS ").identifier(u.columns().get(i));
        }
        w.sql(" FROM DUMMY) AS ").alias(source).sql(" ON (");
        for (int i = 0; i < u.keys().size(); i++) {
            if (i > 0) w.sql(" AND ");
            w.alias(target).sql(".").identifier(u.keys().get(i)).sql(" = ").alias(source).sql(".").identifier(u.keys().get(i));
        }
        w.sql(")");
        if (!u.doNothing()) {
            w.sql("\nWHEN MATCHED THEN UPDATE SET ");
            boolean first = true;
            for (String c : u.updates()) {
                if (!first) w.sql(", ");
                first = false;
                w.alias(target).sql(".").identifier(c).sql(" = ").alias(source).sql(".").identifier(c);
            }
            for (var a : u.assignments().entrySet()) {
                if (!first) w.sql(", ");
                first = false;
                w.alias(target).sql(".").identifier(a.getKey()).sql(" = ").render(a.getValue());
            }
        }
        w.sql("\nWHEN NOT MATCHED THEN INSERT ");
        columnList(w, u.columns());
        w.sql(" VALUES (");
        for (int i = 0; i < u.columns().size(); i++) {
            if (i > 0) w.sql(", ");
            w.alias(source).sql(".").identifier(u.columns().get(i));
        }
        w.sql(")");
    }

    /** HANA 跳过被锁住的行写作 {@code IGNORE LOCKED}。 */
    /** HANA 没有 INTERSECT ALL 和 EXCEPT ALL。 */
    @Override public String setOperator(String operator) {
        if (operator.endsWith(" ALL") && !operator.equals("UNION ALL")) throw new UnsupportedOperationException("HANA has no " + operator);
        return operator;
    }

    @Override public void lock(SqlWriter w, Lock lock) {
        if (lock == Lock.SKIP_LOCKED) w.sql("FOR UPDATE IGNORE LOCKED");
        else super.lock(w, lock);
    }

    /** ngdbc 不支持 JDBC 的生成键，所以按普通语句准备，插入后再查。 */
    @Override public PreparedStatement prepareInsert(Connection connection, String sql, String column) throws SQLException {
        return connection.prepareStatement(sql);
    }

    /**
     * 在同一个连接上查 {@code CURRENT_IDENTITY_VALUE()}：这个连接最近一次插入生成的 IDENTITY 值。
     * 表上有会插入别的 IDENTITY 表的触发器时，取到的是触发器那一次的值。
     */
    @Override public ResultSet generatedKey(Connection connection, PreparedStatement statement) throws SQLException {
        return connection.createStatement().executeQuery("SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY");
    }

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
    @Override public String castType(CastType<?, ?> type) {
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

    /**
     * HANA 的 {@code UPDATE ... FROM} 遇到一行匹配多行时会多次赋值、结果取决于顺序，所以写成按主键对上行的 MERGE：
     * {@code MERGE INTO 表 AS T USING (SELECT 主键 AS "k0", 新值 AS "v0" FROM 表 别名 JOIN 连接表 ... WHERE ...) AS S ON (T.主键 = S."k0")
     * WHEN MATCHED THEN UPDATE SET T.列 = S."v0"}。一行匹配多行时报 689，整条语句不生效。被改表必须有 {@code @Id}。
     */
    @Override public void joinUpdate(SqlWriter w, JoinWrite u) {
        if (u.keys().isEmpty()) {
            throw new IllegalArgumentException("HANA writes a join update as MERGE on the primary key; declare @Id on the updated table");
        }
        String target = w.aliasFor(new Object(), "target");
        String source = w.aliasFor(new Object(), "source");
        w.sql("MERGE INTO ").render(u.table()).sql(" AS ").alias(target).sql("\nUSING (SELECT ");
        for (int i = 0; i < u.keyColumns().size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(u.keyColumns().get(i)).sql(" AS ").identifier("k" + i);
        }
        for (int i = 0; i < u.values().size(); i++) w.sql(", ").render(u.values().get(i)).sql(" AS ").identifier("v" + i);
        w.sql("\nFROM ").render(u.target()).sql("\nJOIN ").render(u.source()).sql(" ON ").render(u.on());
        if (u.where() != null) w.sql("\nWHERE ").render(u.where());
        w.sql(") AS ").alias(source).sql(" ON (");
        for (int i = 0; i < u.keys().size(); i++) {
            if (i > 0) w.sql(" AND ");
            w.alias(target).sql(".").render(u.keys().get(i)).sql(" = ").alias(source).sql(".").identifier("k" + i);
        }
        w.sql(")\nWHEN MATCHED THEN UPDATE SET ");
        for (int i = 0; i < u.columns().size(); i++) {
            if (i > 0) w.sql(", ");
            w.alias(target).sql(".").render(u.columns().get(i)).sql(" = ").alias(source).sql(".").identifier("v" + i);
        }
    }

    /** {@code DELETE FROM 表 别名 WHERE EXISTS (SELECT 1 FROM 连接表 别名 WHERE 连接条件 AND ...)}。 */
    @Override public void joinDelete(SqlWriter w, JoinWrite d) {
        w.sql("DELETE FROM ").render(d.target()).sql("\nWHERE EXISTS (SELECT 1\nFROM ").render(d.source()).sql("\nWHERE ");
        d.renderOnAndWhere(w);
        w.sql(")");
    }

    /** 结构变更的写法，见 {@code db.schema()}。 */
    private final SchemaSyntax schemaSyntax = new HanaSchemaSyntax(this);

    @Override public SchemaSyntax schemaSyntax() { return schemaSyntax; }
}
