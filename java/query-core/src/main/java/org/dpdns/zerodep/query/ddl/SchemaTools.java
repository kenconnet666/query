package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.ddl.SchemaPlan.Change;
import org.dpdns.zerodep.query.ddl.SchemaPlan.Risk;
import org.dpdns.zerodep.query.meta.NamingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * 结构变更的入口，由 {@code db.schema()} 得到：比较表接口的声明与实库（{@link #check}、{@link #plan}），
 * 渲染建表语句（{@link #createTable}），以及读出实库的结构（{@link #read}）。
 */
public final class SchemaTools {
    private final SchemaSyntax syntax;
    private final NamingStrategy naming;
    private final UnaryOperator<String> schemas;
    private final SchemaSyntax.Catalog catalog;
    private final Consumer<List<String>> executor;

    /** 由数据库实例创建：catalog 执行系统目录查询，executor 执行 DDL。 */
    public SchemaTools(SchemaSyntax syntax, NamingStrategy naming, UnaryOperator<String> schemas, SchemaSyntax.Catalog catalog, Consumer<List<String>> executor) {
        this.syntax = syntax;
        this.naming = naming;
        this.schemas = schemas;
        this.catalog = catalog;
        this.executor = executor;
    }

    /** 本方言的结构变更写法。 */
    public SchemaSyntax syntax() { return syntax; }

    /** 表接口声明的结构，schema 已按数据库实例的映射换名。 */
    public TableDef declared(Class<?> table) { return Declarations.of(table, naming, schemas); }

    /** 实库里表的结构；表不存在时为 null。 */
    public TableDef read(String schema, String table) { return syntax.read(catalog, schema, table); }

    /** 按声明建表的语句（不连库），外键在最后；外部表报错。 */
    public List<String> createTable(Class<?> table) {
        TableDef t = declared(table);
        if (t.external()) throw new IllegalArgumentException(table.getSimpleName() + " is @Table(external = true); its DDL is not ours to write");
        List<String> out = new ArrayList<>(syntax.createTable(t));
        for (TableDef.ForeignKey f : t.foreignKeys()) out.add(syntax.addForeignKey(t, f));
        return out;
    }

    /**
     * 只检查不改：声明与实库的差异，外部表只核对已声明的列存在、类型相容。适合在启动时或 CI 里调用：
     * {@code if (!db.schema().check(...).isEmpty()) throw ...}。
     */
    public SchemaPlan check(Class<?>... tables) { return plan(tables); }

    /** 声明与实库的差异和消除差异的语句，见 {@link SchemaPlan}。 */
    public SchemaPlan plan(Class<?>... tables) {
        List<Change> changes = new ArrayList<>();
        for (Class<?> table : tables) {
            TableDef declared = declared(table);
            TableDef actual = read(declared.schema(), declared.name());
            if (declared.external()) external(declared, actual, changes);
            else managed(declared, actual, changes);
        }
        return new SchemaPlan(changes, false, executor);
    }

    /** 外部表：缺表、缺列、类型不相容只报告。 */
    private void external(TableDef declared, TableDef actual, List<Change> changes) {
        String name = declared.qualifiedName();
        if (actual == null) {
            changes.add(new Change(name, Risk.REPORT, "external table is missing", List.of()));
            return;
        }
        for (TableDef.Column c : declared.columns()) {
            TableDef.Column live = actual.column(c.name()).orElse(null);
            if (live == null) changes.add(new Change(name, Risk.REPORT, "column " + c.name() + " is missing", List.of()));
            else if (!compatible(c.type(), live.type())) {
                changes.add(new Change(name, Risk.REPORT, "column " + c.name() + " is " + live.type() + ", which cannot hold the declared " + c.type(), List.of()));
            }
        }
    }

    /** 自己管的表：建表，或逐列、逐索引、逐外键比较。 */
    private void managed(TableDef declared, TableDef actual, List<Change> changes) {
        String name = declared.qualifiedName();
        if (actual == null) {
            changes.add(new Change(name, Risk.SAFE, "table is missing", syntax.createTable(declared)));
            for (TableDef.ForeignKey f : declared.foreignKeys()) changes.add(new Change(name, Risk.SAFE, "foreign key " + f.name() + " is missing", List.of(syntax.addForeignKey(declared, f))));
            return;
        }
        for (TableDef.Column c : declared.columns()) column(declared, actual, c, changes);
        Set<String> declaredColumns = declared.columns().stream().map(c -> TableDef.key(c.name())).collect(Collectors.toSet());
        // 按旧名改名的列，旧名也算声明过的。
        for (TableDef.Column c : declared.columns()) {
            if (!c.previousName().isEmpty() && actual.column(c.name()).isEmpty()) declaredColumns.add(TableDef.key(c.previousName()));
        }
        for (TableDef.Column live : actual.columns()) {
            if (!declaredColumns.contains(TableDef.key(live.name()))) changes.add(new Change(name, Risk.REPORT, "column " + live.name() + " is not declared", List.of()));
        }
        if (!sameNames(declared.primaryKey(), actual.primaryKey())) {
            changes.add(new Change(name, Risk.REPORT, "primary key is " + actual.primaryKey() + " but declared " + declared.primaryKey(), List.of()));
        }
        for (TableDef.Index i : declared.indexes()) {
            TableDef.Index live = actual.indexes().stream().filter(x -> x.name().equalsIgnoreCase(i.name())).findFirst().orElse(null);
            if (live == null) changes.add(new Change(name, Risk.SAFE, "index " + i.name() + " is missing", List.of(syntax.createIndex(declared, i))));
            else if (live.unique() != i.unique() || !sameNames(live.columns(), i.columns())) {
                changes.add(new Change(name, Risk.REPORT, "index " + i.name() + " is " + describe(live) + " but declared " + describe(i), List.of()));
            }
        }
        for (TableDef.ForeignKey f : declared.foreignKeys()) {
            boolean present = actual.foreignKeys().stream().anyMatch(x -> x.name().equalsIgnoreCase(f.name()));
            if (!present) changes.add(new Change(name, Risk.SAFE, "foreign key " + f.name() + " is missing", List.of(syntax.addForeignKey(declared, f))));
        }
    }

    /** 一列：缺列（按旧名改名或加列）、类型、可空、默认值。 */
    private void column(TableDef declared, TableDef actual, TableDef.Column c, List<Change> changes) {
        String name = declared.qualifiedName();
        TableDef.Column live = actual.column(c.name()).orElse(null);
        if (live == null && !c.previousName().isEmpty() && actual.column(c.previousName()).isPresent()) {
            changes.add(new Change(name, Risk.SAFE, "column " + c.previousName() + " is renamed to " + c.name(), List.of(syntax.renameColumn(declared, c.previousName(), c.name()))));
            live = actual.column(c.previousName()).get();
            live = new TableDef.Column(c.name(), live.type(), live.nullable(), live.defaultValue(), live.identity(), live.comment(), "");
        }
        if (live == null) {
            boolean needsDefault = !c.nullable() && c.defaultValue().isEmpty() && !c.identity();
            changes.add(new Change(name, needsDefault ? Risk.DESTRUCTIVE : Risk.SAFE,
                "column " + c.name() + " is missing" + (needsDefault ? "; a NOT NULL column without a default fails on a table with rows: add it nullable, backfill, then make it NOT NULL" : ""),
                List.of(syntax.addColumn(declared, c))));
            return;
        }
        ColumnType wanted = syntax.stored(c.type());
        if (!wanted.equals(live.type())) {
            boolean widens = live.type().widensTo(wanted);
            String change = "column " + c.name() + " is " + live.type() + " but declared " + wanted;
            if (widens) changes.add(new Change(name, Risk.SAFE, change, List.of(syntax.alterType(declared, c))));
            else if (syntax.changesTypesInPlace()) changes.add(new Change(name, Risk.DESTRUCTIVE, change + "; narrowing or changing the type may fail or lose data", List.of(syntax.alterType(declared, c))));
            else changes.add(new Change(name, Risk.REPORT, change + "; this database cannot change it in place: add a new column, copy the data, drop the old one", List.of()));
        }
        if (c.nullable() && !live.nullable()) changes.add(new Change(name, Risk.SAFE, "column " + c.name() + " is NOT NULL but declared nullable", List.of(syntax.dropNotNull(declared, c))));
        if (!c.nullable() && live.nullable()) {
            changes.add(new Change(name, Risk.DESTRUCTIVE, "column " + c.name() + " is nullable but declared NOT NULL; this fails while the column holds NULLs",
                List.of(syntax.setNotNull(declared, c))));
        }
        if (!c.defaultValue().isEmpty() && !syntax.normalizeDefault(c.defaultValue()).equals(syntax.normalizeDefault(live.defaultValue()))) {
            changes.add(new Change(name, Risk.SAFE, "column " + c.name() + " defaults to " + (live.defaultValue().isEmpty() ? "nothing" : live.defaultValue()) + " but declared " + c.defaultValue(),
                List.of(syntax.setDefault(declared, c))));
        }
        if (c.identity() != live.identity()) {
            changes.add(new Change(name, Risk.REPORT, "column " + c.name() + (live.identity() ? " is" : " is not") + " generated by the database", List.of()));
        }
    }

    /** 外部表的列能否装下声明的类型：同族即可，整数可以读进更宽的整数或小数。 */
    private static boolean compatible(ColumnType declared, ColumnType live) {
        ColumnType.Kind d = declared.kind();
        ColumnType.Kind l = live.kind();
        if (d == l) return true;
        return switch (d) {
            case TEXT, CLOB -> l == ColumnType.Kind.TEXT || l == ColumnType.Kind.CLOB;
            case BYTES, BLOB -> l == ColumnType.Kind.BYTES || l == ColumnType.Kind.BLOB;
            case BIGINT -> live.integral();
            case INTEGER -> l == ColumnType.Kind.SMALLINT;
            case DECIMAL -> live.integral();
            case DOUBLE -> l == ColumnType.Kind.DECIMAL || live.integral();
            case TIMESTAMP -> l == ColumnType.Kind.DATE;
            default -> false;
        };
    }

    /** 列名列表相同（不分大小写）。 */
    private static boolean sameNames(List<String> a, List<String> b) {
        return a.stream().map(TableDef::key).toList().equals(b.stream().map(TableDef::key).toList());
    }

    /** 报告里的索引写法。 */
    private static String describe(TableDef.Index i) { return (i.unique() ? "UNIQUE " : "") + i.columns(); }
}
