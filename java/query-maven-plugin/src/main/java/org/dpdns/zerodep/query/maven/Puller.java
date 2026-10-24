package org.dpdns.zerodep.query.maven;

import org.dpdns.zerodep.query.ddl.SourceGenerator;
import org.dpdns.zerodep.query.ddl.TableDef;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.hana.Hana;
import org.dpdns.zerodep.query.mysql.Mysql;
import org.dpdns.zerodep.query.postgres.Postgres;
import org.dpdns.zerodep.query.sqlite.Sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code query:pull} 的实际工作，与 Maven 无关以便测试：连库读出各表的结构，在 outputDirectory 里按包写出列接口。
 * 内容没变的文件不重写，所以 IDE 和增量编译不会因为重新生成而全部重建。
 */
public final class Puller {
    /**
     * 一张要生成的表。
     *
     * @param name     表名，原样使用（HANA、PostgreSQL 区分大小写时按目录里的写法）
     * @param typeName 生成的接口名；为空时由表名推导，例如 {@code S_ITEM} 写成 {@code SItemColumns}
     * @param columns  只生成这些列；为空时生成全部列（SAP 宽表通常只列用到的几列）
     */
    public record Table(String name, String typeName, List<String> columns) { }

    /**
     * 拉取的设置。
     *
     * @param dialect         hana、postgres、mysql 或 sqlite
     * @param url             JDBC URL
     * @param user            用户
     * @param password        密码；在 pom 里用 {@code ${env.X}} 引用环境变量，不要写明文
     * @param schema          表所在的 schema；为空表示连接当前的 schema
     * @param packageName     生成的接口所在的包
     * @param outputDirectory 源码根目录，默认 {@code src/generated/java}
     * @param tables          要生成的表
     * @param only            只重新生成这些表名（不分大小写）；为空时生成全部
     */
    public record Config(String dialect, String url, String user, String password, String schema, String packageName, Path outputDirectory,
                         List<Table> tables, Set<String> only) { }

    private Puller() { }

    /** 连库生成，返回写过的文件（内容没变的不算）。 */
    public static List<Path> pull(Config config) throws IOException {
        Database db = database(config);
        String schema = config.schema() == null ? "" : config.schema();
        return write(config, table -> db.schema().read(schema, table));
    }

    /** 用 reader 按表名读结构（表不存在时返回 null），按配置写出源码；与连库分开以便测试。 */
    static List<Path> write(Config config, Function<String, TableDef> reader) throws IOException {
        Path folder = config.outputDirectory().resolve(config.packageName().replace('.', '/'));
        Files.createDirectories(folder);
        List<Path> written = new ArrayList<>();
        for (Table t : config.tables()) {
            if (!config.only().isEmpty() && config.only().stream().noneMatch(o -> o.equalsIgnoreCase(t.name()))) continue;
            TableDef table = reader.apply(t.name());
            if (table == null) throw new IllegalArgumentException("Table " + t.name() + " does not exist in schema " + (config.schema() == null ? "(current)" : config.schema()));
            String typeName = t.typeName() == null || t.typeName().isBlank() ? SourceGenerator.typeName(t.name()) : t.typeName();
            String source = SourceGenerator.java(table, config.packageName(), typeName, t.columns() == null ? List.of() : t.columns());
            Path file = folder.resolve(typeName + ".java");
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(source)) continue;
            Files.writeString(file, source, StandardCharsets.UTF_8);
            written.add(file);
        }
        return written;
    }

    /** 按方言创建只用于读目录的数据库实例；每条语句开一个连接。 */
    private static Database database(Config config) {
        ConnectionProvider connections = new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException { return DriverManager.getConnection(config.url(), config.user(), config.password()); }

            @Override public void release(Connection connection) throws SQLException { connection.close(); }
        };
        return switch (config.dialect()) {
            case "hana" -> Hana.database(connections, null);
            case "postgres" -> Postgres.database(connections, null);
            case "mysql" -> Mysql.database(connections, null);
            case "sqlite" -> Sqlite.database(connections, null);
            default -> throw new IllegalArgumentException("dialect must be hana, postgres, mysql or sqlite, got " + config.dialect());
        };
    }
}
