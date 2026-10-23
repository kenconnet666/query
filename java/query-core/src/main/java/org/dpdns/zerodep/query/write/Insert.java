package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * INSERT 语句，由 {@code insertInto(Lips.class)} 开始：
 * <pre>{@code
 * record NewLine(String delivery, String material, BigDecimal quantity) { }
 * db.execute(insertInto(Lips.class).values(new NewLine("D9", "M1", BigDecimal.ONE)));
 * db.execute(insertInto(Lips.class).from(from(Staging.class).select(s -> cols(s.delivery(), s.material()))));
 * }</pre>
 * 行是 record 或普通类，属性按名字对应表的列：行里没有的列不写，null 写成 NULL，{@code @Generated} 的列总是不写；
 * 属性在表里找不到同名列、或类型对不上时报错。多行在一个事务里按批执行（SQL 相同，每批 500 行）。
 * {@code db.insertKeys(insert)} 插入并取回 {@code @Generated} 列生成的值。不可变，每个方法返回新的语句。
 *
 * @param <T> 被插入的表接口
 */
public final class Insert<T extends Entity> implements Write {
    /** 被插入的表接口的元数据。 */
    private final EntityMeta<T> table;

    /** 要插入的行，类型相同；用 {@link #from} 时为空。 */
    private final List<Object> rows;

    /** 要写的列：行类的属性对应的列，去掉 {@code @Generated} 的列；还没有行时为空。 */
    private final List<RowColumns.Column> columns;

    /** INSERT ... SELECT 的查询；用 {@link #values} 时为 null。 */
    private final Query<?> source;

    /** 由 {@link #into} 和各修改方法调用。 */
    private Insert(EntityMeta<T> table, List<Object> rows, List<RowColumns.Column> columns, Query<?> source) {
        this.table = table;
        this.rows = rows;
        this.columns = columns;
        this.source = source;
    }

    /** 插入 table；通常写作 {@code Sql.insertInto(Lips.class)}。 */
    public static <T extends Entity> Insert<T> into(Class<T> table) {
        Ref.table(table);   // 校验它是带 @Table 的表接口
        return new Insert<>(Metadata.entity(table), List.of(), List.of(), null);
    }

    /** 追加要插入的行。 */
    public Insert<T> values(Object... rows) { return values(Arrays.asList(rows)); }

    /** 追加要插入的行；所有行必须是同一个类。 */
    public Insert<T> values(Collection<?> rows) {
        if (source != null) throw new IllegalStateException("insertInto(...) already takes its rows from(query)");
        List<Object> all = new ArrayList<>(this.rows);
        all.addAll(rows);
        if (all.isEmpty()) return this;
        Class<?> type = RowColumns.sameClass(all, "insertInto(...).values(...)");
        List<RowColumns.Column> written = columns.isEmpty()
            ? RowColumns.of(table, type).stream().filter(c -> !c.name().equals(table.writes().generated())).toList() : columns;
        if (written.isEmpty()) throw new IllegalArgumentException(type.getSimpleName() + " has no property to insert into " + table.type().getSimpleName());
        return new Insert<>(table, List.copyOf(all), written, null);
    }

    /** INSERT ... SELECT：查询的每一列按列名对应表的同名列，例如 {@code cols(s.delivery(), s.material())}。 */
    public Insert<T> from(Query<?> query) {
        if (!rows.isEmpty()) throw new IllegalStateException("insertInto(...) already has values(...)");
        for (String label : query.labels()) RowColumns.column(table, label, "the query's column");
        return new Insert<>(table, List.of(), List.of(), query);
    }

    /** 插入不检查影响的行数。 */
    @Override public Long expected() { return null; }

    /** {@code @Generated} 的列，{@code db.insertKeys} 取回它的值；表上没有、或用的是 {@link #from} 时报错。 */
    public PropertyMeta generated() {
        if (table.writes().generated() == null) {
            throw new IllegalStateException(table.type().getSimpleName() + " has no @Generated column; use db.execute(insert) instead");
        }
        if (source != null) throw new IllegalStateException("insertKeys(...) needs values(...), not from(query)");
        return table.property(table.writes().generated());
    }

    /** 每行一条 {@code INSERT INTO 表 (列, ...) VALUES (?, ...)}；INSERT ... SELECT 是一条。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        if (source != null) {
            SqlWriter w = writers.get();
            head(w, source.labels().stream().map(l -> RowColumns.column(table, l, "the query's column")).toList());
            w.sql("\n");
            QueryRenderer.named(w, source);
            return List.of(w.statement());
        }
        List<Rendered> out = new ArrayList<>();
        List<PropertyMeta> written = columns.stream().map(RowColumns.Column::column).toList();
        for (Object row : rows) {
            SqlWriter w = writers.get();
            head(w, written);
            w.sql(" VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) w.sql(", ");
                w.render(value(columns.get(i), row));
            }
            w.sql(")");
            out.add(w.statement());
        }
        return out;
    }

    /** 写出 {@code INSERT INTO 表 (列, ...)}。 */
    private void head(SqlWriter w, List<PropertyMeta> written) {
        w.sql("INSERT INTO ");
        if (table.schema() != null) w.identifier(table.schema()).sql(".");
        w.identifier(table.table()).sql(" (");
        Object naming = w.attribute(NamingStrategy.class);
        for (int i = 0; i < written.size(); i++) {
            if (i > 0) w.sql(", ");
            w.identifier(written.get(i).columnName(naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming));
        }
        w.sql(")");
    }

    /** 行里一个属性的值，按表的列的类型绑定（经过列的转换器）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Nodes.Param<?> value(RowColumns.Column c, Object row) {
        return new Nodes.Param<>((SqlType) c.column().type(), c.value(row));
    }
}
