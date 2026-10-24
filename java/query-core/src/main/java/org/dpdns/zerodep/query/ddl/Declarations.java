package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ForeignKey;
import org.dpdns.zerodep.query.annotation.Index;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/** 从表接口的注解读出 {@link TableDef}：列来自列方法，类型按存储类型推断，长度、可空等来自 {@code @Column}。 */
public final class Declarations {
    /** 没有声明时文本的长度。 */
    static final int DEFAULT_TEXT = 255;

    /** 没有声明时小数的精度和小数位。 */
    static final int DEFAULT_PRECISION = 18;
    static final int DEFAULT_SCALE = 2;

    private Declarations() { }

    /**
     * 表接口 table 声明的结构。naming 推导没有 {@code @Column} 名字的列名，schemas 把声明里的 schema 换成实际的名字
     * （与查询相同，见 {@code Config.schema}）。
     */
    public static TableDef of(Class<?> table, NamingStrategy naming, UnaryOperator<String> schemas) {
        EntityMeta<?> meta = Metadata.entity(table);
        if (!meta.isTable()) throw new IllegalArgumentException(table.getSimpleName() + " has no @Table");
        Table t = table.getAnnotation(Table.class);
        List<TableDef.Column> columns = new ArrayList<>();
        // 主键列在前，其余按属性名排序（元数据已经按属性名排好），两端的建表语句因此相同。
        List<PropertyMeta> ordered = new ArrayList<>(meta.properties());
        ordered.sort(java.util.Comparator.comparing((PropertyMeta p) -> !meta.writes().ids().contains(p.name())));
        for (PropertyMeta p : ordered) {
            Column c = method(table, p.name()).getAnnotation(Column.class);
            boolean id = meta.writes().ids().contains(p.name());
            boolean identity = p.name().equals(meta.writes().generated());
            columns.add(new TableDef.Column(p.columnName(naming), type(table, p, c), !id && (c == null || c.nullable()),
                c == null ? "" : c.defaultValue(), identity, c == null ? "" : c.comment(), c == null ? "" : c.previousName()));
        }
        List<String> primaryKey = meta.writes().ids().stream().map(id -> meta.property(id).columnName(naming)).toList();
        List<TableDef.Index> indexes = Arrays.stream(table.getAnnotationsByType(Index.class))
            .map(i -> new TableDef.Index(i.name(), columnNames(meta, i.columns(), naming), i.unique())).toList();
        List<TableDef.ForeignKey> foreignKeys = new ArrayList<>();
        for (ForeignKey f : table.getAnnotationsByType(ForeignKey.class)) {
            EntityMeta<?> target = Metadata.entity(f.references());
            if (!target.isTable()) throw new IllegalArgumentException(table.getSimpleName() + ": " + f.name() + " references " + f.references().getSimpleName() + ", which has no @Table");
            String[] referenced = f.referencedColumns().length > 0 ? f.referencedColumns() : target.writes().ids().toArray(String[]::new);
            if (referenced.length != f.columns().length) throw new IllegalArgumentException(table.getSimpleName() + ": " + f.name() + " has " + f.columns().length + " columns but references " + referenced.length);
            foreignKeys.add(new TableDef.ForeignKey(f.name(), columnNames(meta, f.columns(), naming), schema(target.schema(), schemas), target.table(),
                columnNames(target, referenced, naming)));
        }
        return new TableDef(schema(meta.schema(), schemas), meta.table(), columns, primaryKey, indexes, foreignKeys, t.comment(), t.external());
    }

    /** 声明的 schema 换成实际的名字；没有 schema 时为空串。 */
    private static String schema(String declared, UnaryOperator<String> schemas) {
        return declared == null || declared.isEmpty() ? "" : schemas.apply(declared);
    }

    /** 列方法名换成列名；找不到时报错。 */
    private static List<String> columnNames(EntityMeta<?> meta, String[] properties, NamingStrategy naming) {
        List<String> out = new ArrayList<>();
        for (String p : properties) {
            if (!meta.has(p)) throw new IllegalArgumentException(meta.type().getSimpleName() + " has no column method " + p);
            out.add(meta.property(p).columnName(naming));
        }
        return out;
    }

    /** 列方法；表接口的列方法都没有参数。 */
    private static Method method(Class<?> table, String name) {
        try {
            return table.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(table.getSimpleName() + "." + name + "() is not a column method", e);
        }
    }

    /** 按存储类型（经过转换器之后）推断列类型，长度、精度来自 {@code @Column}。 */
    private static ColumnType type(Class<?> table, PropertyMeta p, Column c) {
        Class<?> storage = p.type().storageType();
        int length = c == null ? 0 : c.length();
        boolean lob = c != null && c.lob();
        if (storage == String.class || storage.isEnum() || storage == Character.class) {
            return lob ? ColumnType.of(ColumnType.Kind.CLOB) : ColumnType.text(length > 0 ? length : storage.isEnum() ? 50 : DEFAULT_TEXT);
        }
        if (storage == byte[].class) return lob ? ColumnType.of(ColumnType.Kind.BLOB) : new ColumnType(ColumnType.Kind.BYTES, length > 0 ? length : DEFAULT_TEXT, 0);
        if (storage == BigDecimal.class || storage == BigInteger.class) {
            int precision = c != null && c.precision() > 0 ? c.precision() : DEFAULT_PRECISION;
            int scale = c != null && c.precision() > 0 ? c.scale() : storage == BigInteger.class ? 0 : DEFAULT_SCALE;
            return ColumnType.decimal(precision, scale);
        }
        if (storage == Short.class || storage == Byte.class) return ColumnType.of(ColumnType.Kind.SMALLINT);
        if (storage == Integer.class) return ColumnType.of(ColumnType.Kind.INTEGER);
        if (storage == Long.class) return ColumnType.of(ColumnType.Kind.BIGINT);
        if (storage == Double.class || storage == Float.class) return ColumnType.of(ColumnType.Kind.DOUBLE);
        if (storage == Boolean.class) return ColumnType.of(ColumnType.Kind.BOOLEAN);
        if (storage == LocalDate.class) return ColumnType.of(ColumnType.Kind.DATE);
        if (storage == LocalDateTime.class) return ColumnType.of(ColumnType.Kind.TIMESTAMP);
        throw new IllegalArgumentException(table.getSimpleName() + "." + p.name() + " is stored as " + storage.getSimpleName() + ", which has no column type; "
            + "declare a converter to a supported type");
    }
}
