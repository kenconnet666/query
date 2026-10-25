package org.dpdns.zerodep.query.it.support;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.testing.Erp.Client;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 一个库的实库测试上下文。每个库在整轮测试里只有一个：第一次用到时建好 QUERY_IT schema 和共享的样例数据（{@link SampleData}）。
 * 测试类标上 {@link LiveTest}，在每个选中的库上各跑一遍，用 {@code @Parameter Live live} 拿到它：
 *
 * <pre>{@code
 * @LiveTest
 * class PagingTest {
 *     @Parameter Live live;
 *
 *     @Test
 *     void 第二页取第三四张交货单() {
 *         assertEquals(List.of("D3", "D4"), live.db().page(..., 2, 2).rows());
 *     }
 * }
 * }</pre>
 *
 * 选库的规则：
 * <ul>
 *   <li>注解的 {@code only}、{@code except} 先划定这个测试类适用的库；</li>
 *   <li>环境变量 {@code QUERY_DATABASES}（或系统属性 {@code query.databases}）是逗号分隔的库名
 *       （hana、postgres、mysql、polardb、sqlite），只跑列出的库；不设时全部都跑；</li>
 *   <li>选中的库没有连接变量时，它的测试记为跳过；设了 {@code QUERY_REQUIRE_LIVE}（CI）时改为失败。</li>
 * </ul>
 */
public final class Live {
    /** 每个库的上下文，整轮测试共用。 */
    private static final Map<Db, Live> CONTEXTS = new EnumMap<>(Db.class);

    /** 这个上下文对应的库。 */
    private final Db target;

    /** 连接设置；没有配置连接变量时为 null。 */
    private final Settings settings;

    /** 每条语句新开一个 JDBC 连接、用完关闭的连接来源。 */
    private final ConnectionProvider provider = new ConnectionProvider() {
        @Override public Connection acquire() throws SQLException { return settings.open(); }

        @Override public void release(Connection connection) throws SQLException { connection.close(); }
    };

    /** 没有配置连接时跳过的原因。 */
    private final String missing;

    /** 共享的实例：带全局过滤器，只看客户端 100 的行；第一次用到时创建。 */
    private Database db;

    /** 不带任何配置的实例，建表和写样例数据用。 */
    private Database plain;

    /** 第一次准备失败的原因；之后的测试直接报同一个错，不再重连。 */
    private RuntimeException broken;

    private Live(Db target, Settings settings, String missing) {
        this.target = target;
        this.settings = settings;
        this.missing = missing;
    }

    // ---------- 选库 ----------

    /** 注解为 test 的测试类要在哪些库上运行，按类注释里的规则。 */
    static Stream<Live> selected(LiveTest test) {
        Set<Db> chosen = chosen();
        return Arrays.stream(Db.values())
            .filter(d -> test.only().length == 0 || List.of(test.only()).contains(d))
            .filter(d -> !List.of(test.except()).contains(d))
            .filter(chosen::contains)
            .map(Live::of);
    }

    /** QUERY_DATABASES 列出的库；没有设置时是全部。 */
    private static Set<Db> chosen() {
        String names = System.getProperty("query.databases", System.getenv("QUERY_DATABASES"));
        if (names == null || names.isBlank()) return Set.of(Db.values());
        return Arrays.stream(names.split(",")).map(String::trim).filter(n -> !n.isEmpty()).map(Live::named).collect(Collectors.toSet());
    }

    /** 库名换成 {@link Db}，不认识时报错并列出认识的名字。 */
    private static Db named(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        if (key.equals("PG")) return Db.POSTGRES;
        try {
            return Db.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("QUERY_DATABASES names an unknown database '" + name + "'; known: "
                + Arrays.stream(Db.values()).map(d -> d.name().toLowerCase(Locale.ROOT)).toList(), e);
        }
    }

    /** 这个库的上下文：读连接变量，但不连接。 */
    private static synchronized Live of(Db target) {
        return CONTEXTS.computeIfAbsent(target, t -> {
            if (t == Db.SQLITE) return new Live(t, sqlite(), null);
            String prefix = "QUERY_" + t.variables();
            String url = System.getenv(prefix + "_URL");
            String user = System.getenv(prefix + "_USER");
            String password = System.getenv(prefix + "_PASSWORD");
            if (url != null && user != null && password != null) return new Live(t, new Settings(url, user, password, List.of()), null);
            String missing = prefix + "_URL/_USER/_PASSWORD not set";
            if (System.getenv("QUERY_REQUIRE_LIVE") != null) throw new IllegalStateException(missing + " but QUERY_REQUIRE_LIVE is");
            return new Live(t, null, missing + "; skipping live " + t + " tests");
        });
    }

    /** SQLite 的库是 target 下的两个新文件；每个连接把 QUERY_IT 库 ATTACH 进来，并打开外键检查。 */
    private static Settings sqlite() {
        Path dir = Path.of("target", "sqlite-it");
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(dir.resolve("main.db"));
            Files.deleteIfExists(dir.resolve("query_it.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String attached = dir.resolve("query_it.db").toAbsolutePath().toString().replace("'", "''");
        return new Settings("jdbc:sqlite:" + dir.resolve("main.db").toAbsolutePath(), null, null,
            List.of("ATTACH DATABASE '" + attached + "' AS QUERY_IT", "PRAGMA foreign_keys = ON"));
    }

    // ---------- 测试用的入口 ----------

    /** 这个库是不是 dbs 之一；各库结果不同的地方写成 {@code if (live.is(SQLITE)) { ... }}。 */
    public boolean is(Db... dbs) { return List.of(dbs).contains(target); }

    /** 共享的数据库实例：带全局过滤器，只看客户端 100 的行（{@link SampleData} 里客户端 200 的行被挡掉）。 */
    public Database db() {
        ready();
        return db;
    }

    /** 同一个库上另建的实例，不带共享实例的过滤器，例如带缓存、监听器或分表配置；{@code configure} 可为 null。 */
    public Database database(Consumer<Database.Config> configure) {
        ready();
        return target.database(connections(), configure);
    }

    /** 本库的方言、另给的连接来源，例如数着借出和归还的连接；{@code configure} 可为 null。 */
    public Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        ready();
        return target.database(connections, configure);
    }

    /** 每条语句新开一个 JDBC 连接、用完关闭的连接来源。 */
    public ConnectionProvider connections() {
        ready();
        return provider;
    }

    /** 新开一个 JDBC 连接，由调用方关闭。 */
    public Connection open() throws SQLException {
        ready();
        return settings.open();
    }

    /** 一条要运行好几秒才返回的手写语句，超时测试用（各库的写法见 {@link Db#slowStatement()}）。 */
    public String slowStatement() { return target.slowStatement(); }

    /** 手写 SQL 里的表名 {@code QUERY_IT.name}，按本库的方言加引号。 */
    public String table(String name) { return quote("QUERY_IT") + "." + quote(name); }

    /** 手写 SQL 里的标识符，按本库的方言加引号。 */
    public String quote(String identifier) { return db().dialect().quote(identifier); }

    /** 依次执行手写的语句；"-" 开头的允许失败，例如删掉可能不存在的表。 */
    public void execute(String... statements) {
        ready();
        run(List.of(statements));
    }

    /** 删掉这些表再按声明重建（{@code db.schema().plan(...)}）；有外键时先列被引用的表，删除按相反的顺序。 */
    public void recreate(Class<?>... tables) {
        ready();
        List<String> drops = new ArrayList<>();
        for (int i = tables.length - 1; i >= 0; i--) {
            var declared = plain.schema().declared(tables[i]);
            drops.add("-DROP TABLE " + table(declared.name()));
        }
        run(drops);
        plain.schema().plan(tables).apply();
    }

    /** 不带任何配置的实例，只给样例数据用。 */
    Database plain() {
        ready();
        return plain;
    }

    /** 报告里的库名，例如 {@code [MySQL]}。 */
    @Override public String toString() { return target.toString(); }

    // ---------- 准备 ----------

    /** 第一次用到时连接、建 schema 和样例数据；没有连接变量时跳过当前测试。每个测试方法开始前由 {@link LiveTargets} 调用一次。 */
    synchronized void ready() {
        if (settings == null) Assumptions.abort(missing);
        if (broken != null) throw broken;
        if (db != null) return;
        try {
            run(target.createSchema());
            run(target.setup());
            plain = target.database(provider, null);
            db = target.database(provider, c -> c.filter(Client.class, r -> r.client().eq("100")));
            SampleData.create(this);
        } catch (RuntimeException e) {
            db = null;
            broken = new IllegalStateException("Preparing the live " + target + " database failed", e);
            throw broken;
        }
    }

    /** 在一个连接上依次执行语句；"-" 开头的允许失败。 */
    private void run(List<String> statements) {
        if (statements.isEmpty()) return;
        try (Connection c = settings.open(); Statement s = c.createStatement()) {
            for (String sql : statements) {
                boolean optional = sql.startsWith("-");
                try {
                    s.execute(optional ? sql.substring(1) : sql);
                } catch (SQLException e) {
                    if (!optional) throw new IllegalStateException(e.getMessage() + "\n" + sql, e);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot connect to the live " + target + " database", e);
        }
    }

    /** JDBC 连接设置；onOpen 是每个新连接上先执行的语句（SQLite 的 ATTACH）。 */
    private record Settings(String url, String user, String password, List<String> onOpen) {
        /** 新开一个连接并执行 onOpen。 */
        Connection open() throws SQLException {
            Connection c = DriverManager.getConnection(url, user, password);
            if (onOpen.isEmpty()) return c;
            try (Statement s = c.createStatement()) {
                for (String sql : onOpen) s.execute(sql);
            } catch (SQLException e) {
                c.close();
                throw e;
            }
            return c;
        }
    }
}
