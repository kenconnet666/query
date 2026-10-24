package org.dpdns.zerodep.query.polardb;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.mysql.Mysql;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;

import java.time.LocalDateTime;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * PolarDB 门面：包含 {@link Mysql} 的全部能力（核心加 MySQL 专属的函数和子句），外加 PolarDB 专属的闪回查询。
 * 一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.polardb.Polardb.*;}</pre>
 * PolarDB 专属的写法在其他方言上渲染时会报错。
 */
public final class Polardb extends Mysql {
    /** 只放静态方法，不能实例化。 */
    private Polardb() { }

    // ---------- 数据库 ----------

    /** 用默认配置创建 PolarDB 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static PolardbDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 PolarDB 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static PolardbDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 创建 PolarDB 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。 */
    public static PolardbDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new PolardbDatabase(connections, configure);
    }

    // ---------- 闪回查询 ----------

    /**
     * 读表在 {@code at} 那一刻的样子（闪回查询）：{@code fromAsOf(Stock.class, before).where(...).select(...)} 写成
     * {@code FROM `S`.`STOCK` AS OF TIMESTAMP CAST(? AS DATETIME(6)) stock}。时刻是数据库所在时区的本地时间（同 {@code NOW()}），
     * 要在 undo 的保留时间之内：社区版引擎由 {@code innodb_undo_retention}（秒）决定，阿里云 PolarDB 要先打开闪回查询；
     * 超出范围时数据库报错。表结构在这一刻之后变过（加了索引、重建了表）时也会报错。
     */
    public static <T extends Entity> From1<T> fromAsOf(Class<T> table, LocalDateTime at) {
        Ref<T> ref = Ref.of(new PolardbSources.AsOf<>(table, at));
        return From1.start(ref, ref.param());
    }
}
