package org.dpdns.zerodep.query.ddl;

import java.util.function.Consumer;

/**
 * 一个版本化的迁移：{@link #up} 往 {@link MigrationSteps} 里登记要执行的 DDL 和数据变更，由 {@link Migrator} 按版本顺序执行一次。
 * 一份迁移在三个方言上各自渲染；需要方言原生写法时用 {@code steps.sql(HanaDialect.class, "...")}。
 * 迁移里不要用表接口描述表结构（表接口以后会改，旧迁移的含义不能跟着变），用 {@link TableBuilder} 手写。
 */
public interface Migration {
    /** 版本号，在迁移列表里唯一，例如 {@code "2026.10.24.1"}；按列表的顺序执行，不按字符串排序。 */
    String version();

    /** 说明，记进历史表。 */
    String description();

    /** 登记这一版要执行的步骤；不要在这里直接访问数据库。 */
    void up(MigrationSteps steps);

    /** 由版本号、说明和登记步骤的函数组成的迁移。 */
    static Migration of(String version, String description, Consumer<MigrationSteps> up) {
        return new Migration() {
            @Override public String version() { return version; }

            @Override public String description() { return description; }

            @Override public void up(MigrationSteps steps) { up.accept(steps); }
        };
    }
}
