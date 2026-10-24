package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.write.Write;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 一个迁移登记的步骤，按登记的顺序执行：DDL 按当前方言渲染成语句，数据变更用写语句（{@code insertInto}、{@code update} 等）。
 * 校验和按渲染出的语句计算，所以已执行的迁移改了内容会被 {@link Migrator#verify()} 发现。
 */
public final class MigrationSteps {
    /** 一步：要执行的 DDL 语句，或一条写语句（每次执行时渲染）。 */
    sealed interface Step permits Ddl, Data { }

    /** 一条手写或渲染出的 DDL。 */
    record Ddl(String sql) implements Step { }

    /** 一条写语句；renderings 是渲染结果，用于校验和与预览。 */
    record Data(Write write, List<Rendered> renderings) implements Step { }

    private final SchemaSyntax syntax;
    private final Dialect dialect;
    private final Function<Write, List<Rendered>> render;
    private final List<Step> steps = new ArrayList<>();

    MigrationSteps(SchemaSyntax syntax, Dialect dialect, Function<Write, List<Rendered>> render) {
        this.syntax = syntax;
        this.dialect = dialect;
        this.render = render;
    }

    /** 本次迁移所在的方言，例如按方言选写法时用。 */
    public Dialect dialect() { return dialect; }

    /** 建表：CREATE TABLE、注释、索引，外键紧跟其后（被引用的表要先建）。 */
    public MigrationSteps createTable(TableDef table) {
        syntax.createTable(table).forEach(this::sql);
        for (TableDef.ForeignKey f : table.foreignKeys()) sql(syntax.addForeignKey(table, f));
        return this;
    }

    /** 给 table 加一列；table 只用名字，见 {@link TableBuilder#named}。 */
    public MigrationSteps addColumn(TableDef table, TableDef.Column column) { return sql(syntax.addColumn(table, column)); }

    /** 把列改成 column 的类型（放宽或改变，后者可能失败）。 */
    public MigrationSteps alterType(TableDef table, TableDef.Column column) { return sql(syntax.alterType(table, column)); }

    /** 列改名。 */
    public MigrationSteps renameColumn(TableDef table, String from, String to) { return sql(syntax.renameColumn(table, from, to)); }

    /** 建索引。 */
    public MigrationSteps createIndex(TableDef table, TableDef.Index index) { return sql(syntax.createIndex(table, index)); }

    /** 加外键。 */
    public MigrationSteps addForeignKey(TableDef table, TableDef.ForeignKey foreignKey) { return sql(syntax.addForeignKey(table, foreignKey)); }

    /** 一条在所有方言上都写得通的语句，例如 {@code DROP INDEX}；一条一次 JDBC 执行，不要用分号拼多条。 */
    public MigrationSteps sql(String statement) {
        steps.add(new Ddl(statement));
        return this;
    }

    /** 只在 only 这个方言（及其子类）上执行的语句；其他方言跳过。 */
    public MigrationSteps sql(Class<? extends Dialect> only, String statement) {
        if (only.isInstance(dialect)) sql(statement);
        return this;
    }

    /** 数据变更：插入种子数据、回填新列等；用插入或更新写成可以重复执行的。 */
    public MigrationSteps execute(Write write) {
        steps.add(new Data(write, render.apply(write)));
        return this;
    }

    /** 登记的步骤。 */
    List<Step> steps() { return List.copyOf(steps); }

    /** 预览用的语句文本：DDL 原样，写语句是渲染出的 SQL。 */
    List<String> statements() {
        List<String> out = new ArrayList<>();
        for (Step s : steps) {
            if (s instanceof Ddl d) out.add(d.sql());
            else for (Rendered r : ((Data) s).renderings()) out.add(r.toString());
        }
        return out;
    }
}
