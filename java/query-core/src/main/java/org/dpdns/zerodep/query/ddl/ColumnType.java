package org.dpdns.zerodep.query.ddl;

/**
 * 与方言无关的列类型，方言的 {@link SchemaSyntax} 把它写成各自的类型名，也把系统目录里的类型读回成它。
 *
 * @param kind   类型族
 * @param length 文本、字节的长度，小数的总位数；不适用时为 0
 * @param scale  小数的小数位；不适用时为 0
 */
public record ColumnType(Kind kind, int length, int scale) {
    /** 类型族。TEXT、BYTES 有长度，DECIMAL 有精度和小数位，CLOB、BLOB 不限长度。 */
    public enum Kind { TEXT, CLOB, DECIMAL, SMALLINT, INTEGER, BIGINT, DOUBLE, BOOLEAN, DATE, TIMESTAMP, BYTES, BLOB }

    /** 长度为 length 的文本。 */
    public static ColumnType text(int length) { return new ColumnType(Kind.TEXT, length, 0); }

    /** DECIMAL(precision, scale)。 */
    public static ColumnType decimal(int precision, int scale) { return new ColumnType(Kind.DECIMAL, precision, scale); }

    /** 没有长度的类型，例如 INTEGER、DATE。 */
    public static ColumnType of(Kind kind) { return new ColumnType(kind, 0, 0); }

    /** 是否整数族。 */
    public boolean integral() { return kind == Kind.SMALLINT || kind == Kind.INTEGER || kind == Kind.BIGINT; }

    /**
     * 把 this 改成 target 是否不会丢数据：同族放宽长度、整数放宽、小数的整数位和小数位都不缩小、文本变成不限长度。
     * 这些变更由计划自动执行，其余类型变化只报告风险。
     */
    public boolean widensTo(ColumnType target) {
        if (equals(target)) return true;
        return switch (kind) {
            case TEXT -> (target.kind == Kind.TEXT && target.length >= length) || target.kind == Kind.CLOB;
            case BYTES -> (target.kind == Kind.BYTES && target.length >= length) || target.kind == Kind.BLOB;
            case SMALLINT -> target.kind == Kind.INTEGER || target.kind == Kind.BIGINT;
            case INTEGER -> target.kind == Kind.BIGINT;
            case DECIMAL -> target.kind == Kind.DECIMAL && target.scale >= scale && target.length - target.scale >= length - scale;
            default -> false;
        };
    }

    /** 便于报告的写法，例如 {@code TEXT(40)}、{@code DECIMAL(13,3)}。 */
    @Override public String toString() {
        return switch (kind) {
            case TEXT, BYTES -> kind + "(" + length + ")";
            case DECIMAL -> kind + "(" + length + "," + scale + ")";
            default -> kind.name();
        };
    }
}
