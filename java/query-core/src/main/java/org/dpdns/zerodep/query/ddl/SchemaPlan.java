package org.dpdns.zerodep.query.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 声明与实库的差异，以及消除差异的语句：{@code db.schema().plan(Note.class, Doc.class)}。不可变；{@link #apply()} 执行。
 * 每项差异分三类：安全的（建表、加列、放宽类型、加索引和外键、允许 NULL、改默认值、按旧名改名）自动执行；
 * 有风险的（缩窄或改类型、改成 NOT NULL、加没有默认值的 NOT NULL 列）只有 {@link #allowDestructive()} 之后才执行；
 * 只报告的（库里多出的列和索引、主键或自增不同、外部表的问题）从不生成语句。
 */
public final class SchemaPlan {
    /** 差异的处理方式。 */
    public enum Risk {
        /** 不会丢数据，自动执行。 */
        SAFE,
        /** 可能失败或丢数据，{@link #allowDestructive()} 之后才执行。 */
        DESTRUCTIVE,
        /** 只报告，从不生成语句，例如库里多出来的列。 */
        REPORT
    }

    /**
     * 一项差异。
     *
     * @param table      表，{@code schema.name}
     * @param risk       处理方式
     * @param message    说明，例如 {@code column TITLE is TEXT(40) but declared TEXT(80)}
     * @param statements 消除差异的语句；只报告的为空
     */
    public record Change(String table, Risk risk, String message, List<String> statements) {
        public Change {
            statements = List.copyOf(statements);
        }

        /** 便于打印：{@code [SAFE] QUERY_IT.W_NOTE: column ...}。 */
        @Override public String toString() { return "[" + risk + "] " + table + ": " + message; }
    }

    private final List<Change> changes;
    private final boolean destructive;
    private final Consumer<List<String>> executor;

    SchemaPlan(List<Change> changes, boolean destructive, Consumer<List<String>> executor) {
        this.changes = List.copyOf(changes);
        this.destructive = destructive;
        this.executor = executor;
    }

    /** 全部差异，按表的顺序；同一张表里建表在前，外键在最后。 */
    public List<Change> changes() { return changes; }

    /** 声明与实库是否一致。 */
    public boolean isEmpty() { return changes.isEmpty(); }

    /** 允许执行有风险的语句（缩窄类型、改成 NOT NULL 等）；只报告的差异仍然不执行。 */
    public SchemaPlan allowDestructive() { return new SchemaPlan(changes, true, executor); }

    /** {@link #apply()} 会执行的语句，按顺序；外键排在所有其他语句之后。 */
    public List<String> statements() {
        List<String> head = new ArrayList<>();
        List<String> foreignKeys = new ArrayList<>();
        for (Change c : changes) {
            if (c.risk == Risk.REPORT || (c.risk == Risk.DESTRUCTIVE && !destructive)) continue;
            // 只有给已有的表加外键的 ALTER TABLE 放到最后；建表语句里内联的外键（SQLite）随建表执行。
            for (String s : c.statements) (s.startsWith("ALTER TABLE") && s.contains(" FOREIGN KEY (") ? foreignKeys : head).add(s);
        }
        head.addAll(foreignKeys);
        return head;
    }

    /** 不会执行的差异：只报告的，以及没有 {@link #allowDestructive()} 时有风险的。 */
    public List<Change> pending() {
        return changes.stream().filter(c -> c.risk == Risk.REPORT || (c.risk == Risk.DESTRUCTIVE && !destructive)).toList();
    }

    /** 执行 {@link #statements()}，一条一次；PostgreSQL 在一个事务里执行，MySQL 和 HANA 的 DDL 逐条提交。 */
    public void apply() {
        List<String> statements = statements();
        if (!statements.isEmpty()) executor.accept(statements);
    }

    /** 要执行的语句，每条一行，以分号结尾；适合交给 DBA 审阅或放进 Flyway、Liquibase 的脚本。 */
    public String toSql() { return statements().stream().map(s -> s + ";").collect(Collectors.joining("\n")); }

    /** 差异的列表，每项一行。 */
    @Override public String toString() { return changes.stream().map(Change::toString).collect(Collectors.joining("\n")); }
}
