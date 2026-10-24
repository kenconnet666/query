package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.dialect.Dialect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 在迁移里手写表结构，不依赖表接口（表接口以后会改，旧迁移的含义不能跟着变）：
 * <pre>{@code
 * TableDef item = TableBuilder.table("APP", "S_ITEM")
 *     .column("ID", ColumnType.of(Kind.BIGINT)).identity()
 *     .column("CODE", ColumnType.text(20)).notNull()
 *     .column("STATUS", ColumnType.text(10)).notNull().defaultValue("'open'")
 *     .primaryKey("ID")
 *     .index("IX_S_ITEM_CODE", true, "CODE")
 *     .build();
 * }</pre>
 * {@code notNull}、{@code defaultValue}、{@code identity}、{@code comment} 作用于最近加的一列。
 */
public final class TableBuilder {
    private final String schema;
    private final String name;
    private final List<TableDef.Column> columns = new ArrayList<>();
    private final List<String> primaryKey = new ArrayList<>();
    private final List<TableDef.Index> indexes = new ArrayList<>();
    private final List<TableDef.ForeignKey> foreignKeys = new ArrayList<>();
    private String comment = "";
    private final List<TableDef.Option> options = new ArrayList<>();

    private TableBuilder(String schema, String name) {
        this.schema = schema == null ? "" : schema;
        this.name = name;
    }

    /** 开始描述 schema 里的表 name；schema 为空表示连接当前的 schema。 */
    public static TableBuilder table(String schema, String name) { return new TableBuilder(schema, name); }

    /** 只有名字的表，用于给已有的表加列、建索引等。 */
    public static TableDef named(String schema, String name) { return table(schema, name).build(); }

    /** 加一列，默认可以为 NULL。 */
    public TableBuilder column(String column, ColumnType type) {
        columns.add(new TableDef.Column(column, type, true, "", false, "", ""));
        return this;
    }

    /** 最近加的一列不允许 NULL。 */
    public TableBuilder notNull() { return last(c -> new TableDef.Column(c.name(), c.type(), false, c.defaultValue(), c.identity(), c.comment(), "")); }

    /** 最近加的一列的默认值（SQL 文本）。 */
    public TableBuilder defaultValue(String sql) { return last(c -> new TableDef.Column(c.name(), c.type(), c.nullable(), sql, c.identity(), c.comment(), "")); }

    /** 最近加的一列由数据库生成（IDENTITY、AUTO_INCREMENT），同时不允许 NULL。 */
    public TableBuilder identity() { return last(c -> new TableDef.Column(c.name(), c.type(), false, c.defaultValue(), true, c.comment(), "")); }

    /** 最近加的一列的注释。 */
    public TableBuilder comment(String text) { return last(c -> new TableDef.Column(c.name(), c.type(), c.nullable(), c.defaultValue(), c.identity(), text, "")); }

    /** 表的注释。 */
    public TableBuilder tableComment(String text) {
        this.comment = text;
        return this;
    }

    /** 主键列；这些列同时改成不允许 NULL。 */
    public TableBuilder primaryKey(String... names) {
        primaryKey.addAll(Arrays.asList(names));
        columns.replaceAll(c -> primaryKey.contains(c.name()) ? new TableDef.Column(c.name(), c.type(), false, c.defaultValue(), c.identity(), c.comment(), "") : c);
        return this;
    }

    /** 索引。 */
    public TableBuilder index(String index, boolean unique, String... names) {
        indexes.add(new TableDef.Index(index, List.of(names), unique));
        return this;
    }

    /** 外键：columns 引用 referencedSchema.referencedTable 的 referencedColumns。 */
    public TableBuilder foreignKey(String constraint, List<String> names, String referencedSchema, String referencedTable, List<String> referencedColumns) {
        foreignKeys.add(new TableDef.ForeignKey(constraint, names, referencedSchema == null ? "" : referencedSchema, referencedTable, referencedColumns));
        return this;
    }

    /** 只在 dialect 上加在建表语句末尾的选项，例如分区：{@code option(PostgresDialect.class, "PARTITION BY RANGE (\"DAY\")")}。 */
    public TableBuilder option(Class<? extends Dialect> dialect, String sql) {
        options.add(new TableDef.Option(dialect, sql));
        return this;
    }

    /** 完成。 */
    public TableDef build() { return new TableDef(schema, name, columns, primaryKey, indexes, foreignKeys, comment, false, options); }

    /** 替换最近加的一列。 */
    private TableBuilder last(java.util.function.UnaryOperator<TableDef.Column> change) {
        if (columns.isEmpty()) throw new IllegalStateException("Add a column first");
        columns.set(columns.size() - 1, change.apply(columns.get(columns.size() - 1)));
        return this;
    }
}
