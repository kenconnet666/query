package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.dialect.Upsert;
import org.dpdns.zerodep.query.type.CastType;

import java.util.List;

/**
 * 默认 SQL 模式下的 MySQL 8。标识符一律用反引号引用（窗口函数名如 RANK 是保留字），
 * {@code ||} 不是字符串连接，字符串字面量里的反斜杠是转义符。
 */
public class MysqlDialect extends Dialect {
    /** 无状态单例，{@link MysqlDatabase} 使用它。 */
    public static final MysqlDialect INSTANCE = new MysqlDialect();

    /**
     * 因为 {@code ||} 不是连接，CONCAT 改写为函数；LOCATE 的参数顺序与核心定义相反，需要对调；
     * 日期加减写成 DATE_ADD，相隔天数写成 DATEDIFF(to, from)，相隔秒数写成 TIMESTAMPDIFF，STRING_AGG 写成 GROUP_CONCAT。
     */
    protected MysqlDialect() {
        register(Sql.CONCAT, (w, args) -> FunctionDef.call(w, "CONCAT", args));
        register(Sql.LOCATE, (w, args) -> FunctionDef.call(w, "LOCATE", List.of(args.get(1), args.get(0))));
        register(Sql.ADD_DAYS, (w, args) -> dateAdd(w, args, "DAY"));
        register(Sql.ADD_MONTHS, (w, args) -> dateAdd(w, args, "MONTH"));
        register(Sql.ADD_YEARS, (w, args) -> dateAdd(w, args, "YEAR"));
        register(Sql.DAYS_BETWEEN, (w, args) -> FunctionDef.call(w, "DATEDIFF", List.of(args.get(1), args.get(0))));
        register(Sql.SECONDS_BETWEEN, (w, args) -> w.sql("TIMESTAMPDIFF(SECOND, ").render(args.get(0)).sql(", ").render(args.get(1)).sql(")"));
        register(Sql.STRING_AGG, (w, args) -> {
            w.sql("GROUP_CONCAT(").render(args.get(0));
            if (args.size() > 2) w.sql(" ORDER BY ").join(args.subList(2, args.size()), ", ");
            w.sql(" SEPARATOR ").render(args.get(1)).sql(")");
        });
    }

    /**
     * {@code INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE 列 = new.列}（8.0.19 起的行别名写法）；
     * 冲突了什么也不做时把第一个键设为它自己，而不用会把别的错误也降为警告的 INSERT IGNORE。冲突由表的主键和唯一索引决定。
     */
    @Override public void upsert(SqlWriter w, Upsert u) {
        w.sql("INSERT INTO ").render(u.table()).sql(" ");
        columnList(w, u.columns());
        w.sql(" VALUES (").join(u.values(), ", ").sql(")");
        if (u.doNothing()) {
            w.sql("\nON DUPLICATE KEY UPDATE ").identifier(u.keys().get(0)).sql(" = ").identifier(u.keys().get(0));
            return;
        }
        w.sql(" AS ").identifier("new").sql("\nON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (String c : u.updates()) {
            if (!first) w.sql(", ");
            first = false;
            w.identifier(c).sql(" = ").identifier("new").sql(".").identifier(c);
        }
        for (var a : u.assignments().entrySet()) {
            if (!first) w.sql(", ");
            first = false;
            w.identifier(a.getKey()).sql(" = ").render(a.getValue());
        }
    }

    /** 写出 {@code DATE_ADD(date, INTERVAL n UNIT)}。 */
    private static void dateAdd(SqlWriter w, List<? extends Renderable> args, String unit) {
        w.sql("DATE_ADD(").render(args.get(0)).sql(", INTERVAL ").render(args.get(1)).sql(" " + unit + ")");
    }

    /** 方言名，用于异常信息和监听事件。 */
    @Override public String name() { return "MySQL"; }

    /** 标识符一律加引号，所以没有任何名字算作无需引用。 */
    @Override protected boolean isBare(String identifier) { return false; }

    /** MySQL 不折叠标识符的大小写，保持原样。 */
    @Override public String foldCase(String identifier) { return identifier; }

    /** 用反引号引用标识符，名字里的反引号写成两个。 */
    @Override public String quote(String identifier) { return '`' + identifier.replace("`", "``") + '`'; }

    /** 名字也用反引号引用，字符串内部的反斜杠会转义下一个字符。 */
    @Override public int quotedEnd(String sql, int start) {
        return switch (sql.charAt(start)) {
            case '\'', '"' -> closingQuote(sql, start, true);
            case '`' -> closingQuote(sql, start, false);
            default -> -1;
        };
    }

    /** 字符串字面量：反斜杠和单引号都要转义。 */
    @Override protected String stringLiteral(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"; }

    /** Connector/J 在 useServerPrepStmts 关闭时由客户端把参数写进语句文本，所以没有参数个数上限。 */
    @Override public int maxParameters() { return Integer.MAX_VALUE; }

    /** MySQL 的 OFFSET 必须跟在 LIMIT 之后；只有偏移时，按文档写法用最大的 BIGINT UNSIGNED 表示不限行数。 */
    @Override public void limit(SqlWriter w, Integer limit, Integer offset) {
        boolean hasOffset = offset != null && offset > 0;
        if (limit == null && !hasOffset) return;
        w.sql("\nLIMIT ").sql(limit == null ? "18446744073709551615" : Integer.toString(limit));
        if (hasOffset) w.sql(" OFFSET ").sql(Integer.toString(offset));
    }

    /** MySQL 把 ROLLUP 写成整个 GROUP BY 列表的后缀。 */
    @Override public void groupBy(SqlWriter w, List<? extends Renderable> keys, boolean rollup) {
        w.join(keys, ", ");
        if (rollup) w.sql(" WITH ROLLUP");
    }

    /** MySQL 没有 NULLS FIRST/LAST：先按 "IS NULL" 排序，效果等同。 */
    @Override public void orderItem(SqlWriter w, Renderable expr, boolean descending, Boolean nullsFirst) {
        if (nullsFirst != null) w.render(expr).sql(nullsFirst ? " IS NULL DESC, " : " IS NULL ASC, ");
        w.render(expr).sql(descending ? " DESC" : " ASC");
    }

    /** CAST 的目标类型：文本用 CHAR，整数用 SIGNED，时间戳用 DATETIME(6)；MySQL 不能 CAST 成 BOOLEAN。 */
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
