package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.dialect.Dialect;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 一张表的结构：来自表接口的声明（{@link Declarations}），或来自系统目录（{@link SchemaSyntax#read}）。
 * 名字都是数据库里的列名、索引名，不是 Java 的属性名。
 *
 * @param schema      schema，为空表示连接当前的 schema
 * @param name        表名
 * @param columns     各列，按声明或目录里的顺序
 * @param primaryKey  主键列，没有主键时为空
 * @param indexes     主键以外的索引
 * @param foreignKeys 外键
 * @param comment     表的注释，没有时为空串
 * @param external    是否不归本库管，见 {@code @Table(external = true)}
 * @param options     各方言加在建表语句末尾的选项（{@code @TableOption}），只在声明里有
 */
public record TableDef(String schema, String name, List<Column> columns, List<String> primaryKey, List<Index> indexes,
                       List<ForeignKey> foreignKeys, String comment, boolean external, List<Option> options) {
    /** 没有建表选项的表，例如从系统目录读回的。 */
    public TableDef(String schema, String name, List<Column> columns, List<String> primaryKey, List<Index> indexes, List<ForeignKey> foreignKeys,
                    String comment, boolean external) {
        this(schema, name, columns, primaryKey, indexes, foreignKeys, comment, external, List.of());
    }

    /** 只在 dialect（含子类）上加在建表语句末尾的 sql。 */
    public record Option(Class<? extends Dialect> dialect, String sql) { }

    /**
     * 一列。
     *
     * @param name         列名
     * @param type         类型
     * @param nullable     是否可以为 NULL
     * @param defaultValue 默认值的 SQL 文本，没有时为空串
     * @param identity     是否由数据库生成（IDENTITY、AUTO_INCREMENT）
     * @param comment      注释，没有时为空串
     * @param previousName 改名前的列名，没有时为空串（只在声明里有）
     */
    public record Column(String name, ColumnType type, boolean nullable, String defaultValue, boolean identity, String comment, String previousName) { }

    /** 主键以外的索引；columns 是列名。 */
    public record Index(String name, List<String> columns, boolean unique) { }

    /** 外键；columns 与 referencedColumns 是列名。 */
    public record ForeignKey(String name, List<String> columns, String referencedSchema, String referencedTable, List<String> referencedColumns) { }

    public TableDef {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        indexes = List.copyOf(indexes);
        foreignKeys = List.copyOf(foreignKeys);
        options = List.copyOf(options);
    }

    /** 按列名（不分大小写）找列。 */
    public Optional<Column> column(String columnName) {
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
    }

    /** 报告里用的表名：{@code schema.name}。 */
    public String qualifiedName() { return schema.isEmpty() ? name : schema + "." + name; }

    /** 索引名、约束名比较时不分大小写。 */
    static String key(String name) { return name.toUpperCase(Locale.ROOT); }
}
