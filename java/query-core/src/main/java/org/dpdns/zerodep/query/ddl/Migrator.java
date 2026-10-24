package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.ddl.ColumnType.Kind;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.write.Write;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 版本化迁移的执行器，由 {@code db.migrator(migrations)} 得到：按列表顺序执行还没执行过的迁移，每个一次，记进历史表 QUERY_MIGRATION
 * （版本、说明、校验和、状态、开始与结束时间）。多个实例同时启动时用锁表 QUERY_MIGRATION_LOCK 的一行互斥。
 * 两张表的名字可以用 {@link #historyTable(String)} 换掉。
 * <ul>
 *   <li>PostgreSQL 每个迁移在一个事务里执行，失败时整体回滚；MySQL、HANA 的 DDL 逐条提交，失败的迁移记为 failed，
 *       修好数据库后调用 {@link #repair} 再重新执行。</li>
 *   <li>已执行的迁移改了内容（校验和不同）、历史里有列表中没有的版本、列表中间有没执行的版本时，{@link #verify()} 和
 *       {@link #migrateToLatest()} 都报错。</li>
 *   <li>只向前，没有回滚迁移：要撤销就再写一个新的迁移。</li>
 * </ul>
 */
public final class Migrator {
    /** 历史表名。 */
    public static final String HISTORY = "QUERY_MIGRATION";

    /** 锁表名。 */
    public static final String LOCK = "QUERY_MIGRATION_LOCK";

    /** 数据库实例提供给迁移器的能力。 */
    public interface Backend {
        /** 带 {@code ?} 参数的 SELECT，每行是小写列标签到值的映射。 */
        List<Map<String, Object>> query(String sql, Object... parameters);

        /** 带 {@code ?} 参数的写语句，返回影响的行数。 */
        long update(String sql, Object... parameters);

        /** 执行 DDL，一条一次，不开事务。 */
        void run(List<String> statements);

        /** 渲染写语句。 */
        List<Rendered> render(Write write);

        /** 执行写语句。 */
        void execute(Write write);

        /** 在一个事务里执行。 */
        <T> T transaction(Supplier<T> work);
    }

    /** 历史表里的一行。 */
    public record Applied(String version, String description, String checksum, String status, LocalDateTime startedAt, LocalDateTime finishedAt) { }

    private final List<Migration> migrations;
    private final Dialect dialect;
    private final SchemaSyntax syntax;
    private final Backend backend;
    private final String schema;
    private final String historyTable;
    private final String lockTable;
    private final Duration lockTimeout;
    private final String owner = UUID.randomUUID().toString();

    /** 由数据库实例创建。 */
    public Migrator(List<Migration> migrations, Dialect dialect, SchemaSyntax syntax, Backend backend) {
        this(migrations, dialect, syntax, backend, "", HISTORY, LOCK, Duration.ofMinutes(1));
    }

    private Migrator(List<Migration> migrations, Dialect dialect, SchemaSyntax syntax, Backend backend, String schema,
                     String historyTable, String lockTable, Duration lockTimeout) {
        Set<String> seen = new HashSet<>();
        for (Migration m : migrations) {
            if (!seen.add(m.version())) throw new IllegalArgumentException("Migration version " + m.version() + " appears twice");
        }
        this.migrations = List.copyOf(migrations);
        this.dialect = dialect;
        this.syntax = syntax;
        this.backend = backend;
        this.schema = schema;
        this.historyTable = historyTable;
        this.lockTable = lockTable;
        this.lockTimeout = lockTimeout;
    }

    /** 历史表和锁表放在 schema 里；默认是连接当前的 schema。 */
    public Migrator historySchema(String historySchema) {
        return new Migrator(migrations, dialect, syntax, backend, historySchema, historyTable, lockTable, lockTimeout);
    }

    /**
     * 换历史表的名字，锁表跟着叫"历史表名加 _LOCK"（名字全是小写时加 _lock）。同一个库里有几套各自迁移的表时用它分开，
     * 例如建在本库上的库自带迁移：{@code db.migrator(flowMigrations).historyTable("flow_migration")}，锁表是 flow_migration_lock。
     */
    public Migrator historyTable(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The history table needs a name");
        String lock = name + (name.equals(name.toLowerCase(java.util.Locale.ROOT)) ? "_lock" : "_LOCK");
        return new Migrator(migrations, dialect, syntax, backend, schema, name, lock, lockTimeout);
    }

    /** 等待别的实例释放迁移锁的最长时间，默认一分钟。 */
    public Migrator lockTimeout(Duration timeout) {
        return new Migrator(migrations, dialect, syntax, backend, schema, historyTable, lockTable, timeout);
    }

    /** 历史表里的各行，按开始时间；历史表还不存在时为空。 */
    public List<Applied> history() {
        if (syntax.read(backend::query, schema, historyTable) == null) return List.of();
        List<Applied> out = new ArrayList<>();
        for (Map<String, Object> r : backend.query(cols("SELECT {VERSION}, {DESCRIPTION}, {CHECKSUM}, {STATUS}, {STARTED_AT}, {FINISHED_AT} FROM ") + table(historyTable) + cols(" ORDER BY {STARTED_AT}"))) {
            out.add(new Applied(text(r.get("version")), text(r.get("description")), text(r.get("checksum")), text(r.get("status")),
                time(r.get("started_at")), time(r.get("finished_at"))));
        }
        return out;
    }

    /** 还没执行的迁移，按列表顺序。 */
    public List<Migration> pending() {
        Map<String, Applied> applied = byVersion(history());
        return migrations.stream().filter(m -> !applied.containsKey(m.version())).toList();
    }

    /** 还没执行的迁移各自会执行的语句，按版本；不连库执行任何东西。 */
    public Map<String, List<String>> plan() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Migration m : pending()) out.put(m.version(), record(m).statements());
        return out;
    }

    /** 检查历史与迁移列表是否一致；不一致时抛出异常，列出全部问题。 */
    public void verify() {
        List<String> problems = problems(byVersion(history()));
        if (!problems.isEmpty()) throw new IllegalStateException("Migration history does not match the migrations:\n" + String.join("\n", problems));
    }

    /** 执行全部还没执行的迁移，返回执行了的版本。 */
    public List<String> migrateToLatest() { return migrateTo(null); }

    /** 执行到 version（含）为止还没执行的迁移，返回执行了的版本。 */
    public List<String> migrateTo(String version) {
        if (version != null && migrations.stream().noneMatch(m -> m.version().equals(version))) throw new IllegalArgumentException("No migration " + version);
        ensureTables();
        lock();
        try {
            Map<String, Applied> applied = byVersion(history());
            List<String> problems = problems(applied);
            if (!problems.isEmpty()) throw new IllegalStateException("Migration history does not match the migrations:\n" + String.join("\n", problems));
            List<String> done = new ArrayList<>();
            for (Migration m : migrations) {
                if (!applied.containsKey(m.version())) {
                    run(m);
                    done.add(m.version());
                }
                if (m.version().equals(version)) break;
            }
            return done;
        } finally {
            unlock();
        }
    }

    /**
     * 接管已有的库：把 version（含）之前的迁移记为已执行（状态 baseline），不执行它们。
     * 例如库是手工建的，第一个迁移描述的就是现状。
     */
    public void baseline(String version) {
        if (migrations.stream().noneMatch(m -> m.version().equals(version))) throw new IllegalArgumentException("No migration " + version);
        ensureTables();
        lock();
        try {
            Map<String, Applied> applied = byVersion(history());
            for (Migration m : migrations) {
                if (!applied.containsKey(m.version())) {
                    LocalDateTime now = now();
                    backend.update("INSERT INTO " + table(historyTable) + cols(" ({VERSION}, {DESCRIPTION}, {CHECKSUM}, {STATUS}, {STARTED_AT}, {FINISHED_AT}) VALUES (?, ?, ?, ?, ?, ?)"),
                        m.version(), m.description(), checksum(record(m)), "baseline", now, now);
                }
                if (m.version().equals(version)) break;
            }
        } finally {
            unlock();
        }
    }

    /** 删掉 version 失败的那一行，修好数据库后再执行 {@link #migrateToLatest()} 会重新执行它。 */
    public void repair(String version) {
        long rows = backend.update("DELETE FROM " + table(historyTable) + cols(" WHERE {VERSION} = ? AND {STATUS} = ?"), version, "failed");
        if (rows == 0) throw new IllegalStateException("Migration " + version + " has not failed");
    }

    // ---------- 执行 ----------

    /** 执行一个迁移：先记 started，执行步骤，成功记 success，失败记 failed。PostgreSQL 的步骤和 success 在一个事务里。 */
    private void run(Migration m) {
        MigrationSteps steps = record(m);
        String checksum = checksum(steps);
        backend.update("INSERT INTO " + table(historyTable) + cols(" ({VERSION}, {DESCRIPTION}, {CHECKSUM}, {STATUS}, {STARTED_AT}) VALUES (?, ?, ?, ?, ?)"),
            m.version(), m.description(), checksum, "started", now());
        Runnable work = () -> {
            for (MigrationSteps.Step s : steps.steps()) {
                if (s instanceof MigrationSteps.Ddl d) backend.run(List.of(d.sql()));
                else backend.execute(((MigrationSteps.Data) s).write());
            }
            mark(m.version(), "success");
        };
        try {
            if (syntax.transactionalDdl()) backend.transaction(() -> {
                work.run();
                return null;
            });
            else work.run();
        } catch (RuntimeException e) {
            mark(m.version(), "failed");
            throw new IllegalStateException("Migration " + m.version() + " (" + m.description() + ") failed: " + e.getMessage(), e);
        }
    }

    /** 改历史表里一个版本的状态和结束时间。 */
    private void mark(String version, String status) {
        backend.update("UPDATE " + table(historyTable) + cols(" SET {STATUS} = ?, {FINISHED_AT} = ? WHERE {VERSION} = ?"), status, now(), version);
    }

    /** 让迁移登记它的步骤。 */
    private MigrationSteps record(Migration m) {
        MigrationSteps steps = new MigrationSteps(syntax, dialect, backend::render);
        m.up(steps);
        return steps;
    }

    /** 步骤语句的 SHA-256。 */
    static String checksum(MigrationSteps steps) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\n", steps.statements()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 历史与列表的不一致：失败或未完成的版本、未知的版本、校验和不同、中间有空缺。 */
    private List<String> problems(Map<String, Applied> applied) {
        List<String> out = new ArrayList<>();
        Set<String> known = new HashSet<>();
        boolean gap = false;
        for (Migration m : migrations) {
            known.add(m.version());
            Applied a = applied.get(m.version());
            if (a == null) {
                gap = true;
                continue;
            }
            if (gap) out.add(m.version() + " was applied although an earlier migration was not");
            if (a.status().equals("failed") || a.status().equals("started")) {
                out.add(m.version() + " is " + a.status() + "; fix the database by hand, then call repair(\"" + m.version() + "\")");
            } else if (!a.checksum().equals(checksum(record(m)))) {
                out.add(m.version() + " was changed after it was applied (checksum differs); write a new migration instead");
            }
        }
        for (String v : applied.keySet()) if (!known.contains(v)) out.add(v + " is in the history but not among the migrations");
        return out;
    }

    // ---------- 历史表与锁 ----------

    /** 历史表和锁表不存在时建上，锁表放入唯一的一行。 */
    private void ensureTables() {
        if (syntax.read(backend::query, schema, historyTable) == null) {
            backend.run(syntax.createTable(TableBuilder.table(schema, historyTable)
                .column("VERSION", ColumnType.text(100)).column("DESCRIPTION", ColumnType.text(200)).column("CHECKSUM", ColumnType.text(64))
                .column("STATUS", ColumnType.text(10)).notNull().column("STARTED_AT", ColumnType.of(Kind.TIMESTAMP)).column("FINISHED_AT", ColumnType.of(Kind.TIMESTAMP))
                .primaryKey("VERSION").build()));
        }
        if (syntax.read(backend::query, schema, lockTable) == null) {
            backend.run(syntax.createTable(TableBuilder.table(schema, lockTable)
                .column("ID", ColumnType.of(Kind.INTEGER)).column("OWNER", ColumnType.text(64)).column("LOCKED_AT", ColumnType.of(Kind.TIMESTAMP))
                .primaryKey("ID").build()));
        }
        if (backend.query(cols("SELECT {ID} FROM ") + table(lockTable) + cols(" WHERE {ID} = ?"), 1).isEmpty()) {
            try {
                backend.update("INSERT INTO " + table(lockTable) + cols(" ({ID}) VALUES (?)"), 1);
            } catch (RuntimeException e) {
                // 另一个实例同时插入了这一行
            }
        }
    }

    /** 占用锁行；别的实例占着时每半秒重试一次，超过 lockTimeout 报错。 */
    private void lock() {
        long deadline = System.nanoTime() + lockTimeout.toNanos();
        while (backend.update("UPDATE " + table(lockTable) + cols(" SET {OWNER} = ?, {LOCKED_AT} = ? WHERE {ID} = ? AND {OWNER} IS NULL"), owner, now(), 1) == 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Another process holds the migration lock in " + table(lockTable) + "; if it crashed, clear OWNER by hand");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the migration lock", e);
            }
        }
    }

    /** 释放锁行。 */
    private void unlock() {
        backend.update("UPDATE " + table(lockTable) + cols(" SET {OWNER} = NULL, {LOCKED_AT} = NULL WHERE {ID} = ? AND {OWNER} = ?"), 1, owner);
    }

    /** 把 SQL 片段里的 {列名} 换成按方言加引号的列名。 */
    private String cols(String sql) {
        return java.util.regex.Pattern.compile("\\{([A-Z_]+)}").matcher(sql).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(dialect.quote(m.group(1))));
    }

    /** 写出 {@code schema.表名}。 */
    private String table(String name) { return schema.isEmpty() ? dialect.quote(name) : dialect.quote(schema) + "." + dialect.quote(name); }

    /** 按版本号索引历史。 */
    private static Map<String, Applied> byVersion(List<Applied> history) {
        Map<String, Applied> out = new HashMap<>();
        for (Applied a : history) out.put(a.version(), a);
        return out;
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }

    /** 当前时间，截到毫秒：Linux 上 now() 带纳秒，HANA 的 TIMESTAMP 只到 100 纳秒，绑定时会拒绝截断。与 TS 的毫秒精度一致。 */
    private static LocalDateTime now() { return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS); }

    /** 目录里的时间戳（java.sql.Timestamp 或 LocalDateTime）。 */
    private static LocalDateTime time(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
