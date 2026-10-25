package org.dpdns.zerodep.query.it.support;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.hana.Hana;
import org.dpdns.zerodep.query.mysql.Mysql;
import org.dpdns.zerodep.query.polardb.Polardb;
import org.dpdns.zerodep.query.postgres.Postgres;
import org.dpdns.zerodep.query.sqlite.Sqlite;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 实库测试覆盖的数据库。一个库怎么连、用哪个方言门面建实例、手写 SQL 有哪些不同，都只写在这里，测试里不再出现。
 *
 * <p>连接来自环境变量 {@code QUERY_{变量前缀}_URL / _USER / _PASSWORD}；SQLite 不需要变量，库是 target 下的临时文件。
 */
public enum Db {
    /** SAP HANA（租户库 HXE）。 */
    HANA("HANA", "HANA", Hana::database),

    /** PostgreSQL。 */
    POSTGRES("PostgreSQL", "PG", Postgres::database),

    /** MySQL 8。 */
    MYSQL("MySQL", "MYSQL", Mysql::database),

    /** PolarDB for MySQL（本机和 CI 用开源的 PolarDB-X 引擎）。它也跑 MySQL 的专属场景。 */
    POLARDB("PolarDB", "POLARDB", Polardb::database),

    /** SQLite：每个连接把 QUERY_IT 库 ATTACH 进来，随单元测试一起跑。 */
    SQLITE("SQLite", null, Sqlite::database);

    /** 测试报告里显示的名字。 */
    private final String title;

    /** 连接变量的前缀；SQLite 为 null。 */
    private final String variables;

    /** 方言门面的 {@code database(connections, configure)}。 */
    private final BiFunction<ConnectionProvider, Consumer<Database.Config>, Database> facade;

    Db(String title, String variables, BiFunction<ConnectionProvider, Consumer<Database.Config>, Database> facade) {
        this.title = title;
        this.variables = variables;
        this.facade = facade;
    }

    /** 连接变量的前缀，例如 PG 对应 {@code QUERY_PG_URL}；SQLite 为 null。 */
    String variables() { return variables; }

    /** 用这个库的方言门面创建数据库实例；{@code configure} 可为 null。 */
    Database database(ConnectionProvider connections, Consumer<Database.Config> configure) { return facade.apply(connections, configure); }

    /** 建测试 schema 的手写语句；"-" 开头的允许失败（已经存在）。SQLite 的 QUERY_IT 由连接 ATTACH，不用建。 */
    List<String> createSchema() {
        return switch (this) {
            case HANA, POSTGRES -> List.of("-CREATE SCHEMA \"QUERY_IT\"");
            case MYSQL, POLARDB -> List.of("CREATE SCHEMA IF NOT EXISTS `QUERY_IT`");
            case SQLITE -> List.of();
        };
    }

    /**
     * 一条要运行好几秒才返回的手写语句，超时测试用；HANA 的 SQL 没有 sleep，由 {@link #setup()} 建一个调用
     * SQLSCRIPT_SYNC 的存储过程。MySQL 的 SLEEP() 被 max_execution_time 打断时返回 1 而不报错，所以改成数十亿行的计数。
     */
    String slowStatement() {
        return switch (this) {
            case HANA -> "CALL QUERY_IT.SLOW_ROW(3)";
            case POSTGRES -> "SELECT pg_sleep(3)";
            case MYSQL, POLARDB -> {
                String thousand = "(SELECT 1 FROM information_schema.columns LIMIT 1000)";
                yield "SELECT COUNT(*) FROM " + thousand + " a, " + thousand + " b, " + thousand + " c";
            }
            case SQLITE -> "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 20000000) SELECT COUNT(*) FROM r";
        };
    }

    /** 建好 schema 之后、样例数据之前执行的手写语句。 */
    List<String> setup() {
        if (this != HANA) return List.of();
        return List.of("-DROP PROCEDURE QUERY_IT.SLOW_ROW", "CREATE PROCEDURE QUERY_IT.SLOW_ROW(IN SECONDS INTEGER) READS SQL DATA AS BEGIN "
            + "USING SQLSCRIPT_SYNC AS SYNCLIB; CALL SYNCLIB:SLEEP_SECONDS(:SECONDS); SELECT 1 AS X FROM DUMMY; END");
    }

    /** 测试报告里的名字，例如 {@code [MySQL] 分页}。 */
    @Override public String toString() { return title; }
}
