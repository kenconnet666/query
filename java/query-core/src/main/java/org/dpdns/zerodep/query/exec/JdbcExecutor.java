package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Binding;
import org.dpdns.zerodep.query.dialect.Dialect.CursorCleanup;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** 执行已渲染的语句：通过类型注册表绑定参数、读取行、通知监听器。 */
public final class JdbcExecutor {
    /** 把结果集的当前行读成一个值数组。 */
    @FunctionalInterface
    public interface RowReader {
        /** 读取 {@code rs} 的当前行，不要移动游标。 */
        Object[] read(ResultSet rs) throws SQLException;
    }

    /** 接管整个结果集并产出一个结果；方法返回后结果集即被关闭。 */
    @FunctionalInterface
    public interface ResultHandler<T> {
        /** 处理结果集，调用时游标位于第一行之前。 */
        T handle(ResultSet rs) throws SQLException;
    }

    private final String database;
    private final Dialect dialect;
    private final ConnectionProvider connections;
    private final TypeRegistry types;
    private final List<QueryListener> listeners;
    private final int fetchSize;
    private final Duration defaultTimeout;

    /**
     * @param database       数据库实例名，用于异常信息和监听事件
     * @param dialect        方言，提供游标处理、超时识别和参数个数上限
     * @param connections    连接来源
     * @param types          绑定参数与读取列所用的类型注册表
     * @param listeners      语句监听器
     * @param fetchSize      JDBC 每次提取的行数；0 表示使用驱动默认值
     * @param defaultTimeout 默认查询超时；null 表示不限制
     */
    public JdbcExecutor(String database, Dialect dialect, ConnectionProvider connections, TypeRegistry types, List<QueryListener> listeners,
                        int fetchSize, Duration defaultTimeout) {
        this.database = database;
        this.dialect = dialect;
        this.connections = connections;
        this.types = types;
        this.listeners = List.copyOf(listeners);
        this.fetchSize = fetchSize;
        this.defaultTimeout = defaultTimeout;
    }

    /** 本执行器使用的类型注册表。 */
    public TypeRegistry types() { return types; }

    /** 按给定类型读取第 1 到 n 列，并经各类型的转换器解码。 */
    public RowReader columns(List<SqlType<?>> columnTypes) {
        SqlType<?>[] t = columnTypes.toArray(SqlType<?>[]::new);
        return rs -> {
            Object[] row = new Object[t.length];
            for (int i = 0; i < t.length; i++) row[i] = t[i].fromDatabase(types.read(rs, i + 1, t[i].storageType()));
            return row;
        };
    }

    /** 执行查询并读出全部行；{@code timeout} 为 null 表示使用数据库实例的默认超时。 */
    public List<Object[]> fetch(Rendered statement, RowReader reader, Duration timeout) {
        return query(statement, rs -> {
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) rows.add(reader.read(rs));
            return rows;
        }, List::size, timeout);
    }

    /** 执行语句并把结果集交给 {@code handler}；{@code rowCount} 把结果的行数报告给监听器。 */
    public <T> T query(Rendered statement, ResultHandler<T> handler, java.util.function.ToLongFunction<T> rowCount, Duration timeout) {
        checkParameterCount(statement);
        long start = System.nanoTime();
        Connection connection = null;
        try {
            connection = connections.acquire();
            try (PreparedStatement ps = prepare(connection, statement, timeout); ResultSet rs = ps.executeQuery()) {
                T result = handler.handle(rs);
                notify(statement, start, rowCount.applyAsLong(result), null, false);
                return result;
            }
        } catch (SQLException e) {
            notify(statement, start, 0, e, false);
            throw new QueryExecutionException(database, statement, e, timedOut(e));
        } catch (RuntimeException e) {
            notify(statement, start, 0, e, false);
            throw e;
        } finally {
            release(connection);
        }
    }

    /** 惰性读取的结果；关闭流会关闭游标并归还连接，所以调用方必须关闭它。 */
    public Stream<Object[]> stream(Rendered statement, RowReader reader, Duration timeout) {
        checkParameterCount(statement);
        long start = System.nanoTime();
        Connection connection = null;
        PreparedStatement ps = null;
        CursorCleanup cleanup = CursorCleanup.NONE;
        ResultSet rs = null;
        try {
            connection = connections.acquire();
            ps = prepare(connection, statement, timeout);
            cleanup = dialect.openCursor(connection, ps);
            rs = ps.executeQuery();
        } catch (SQLException | RuntimeException e) {
            closeQuietly(rs, ps);
            cleanUpQuietly(cleanup);
            release(connection);
            notify(statement, start, 0, e, false);
            throw new QueryExecutionException(database, statement, e, timedOut(e));
        }
        Connection c = connection;
        PreparedStatement p = ps;
        CursorCleanup undo = cleanup;
        ResultSet r = rs;
        long[] count = {0};
        Spliterator<Object[]> rows = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            /** 读下一行交给 action；没有更多行时关闭游标并返回 false。 */
            @Override
            public boolean tryAdvance(Consumer<? super Object[]> action) {
                try {
                    if (!r.next()) return false;
                    count[0]++;
                    action.accept(reader.read(r));
                    return true;
                } catch (SQLException e) {
                    throw new QueryExecutionException(database, statement, e, timedOut(e));
                }
            }
        };
        return StreamSupport.stream(rows, false).onClose(() -> {
            closeQuietly(r, p);
            cleanUpQuietly(undo);
            release(c);
            notify(statement, start, count[0], null, false);
        });
    }

    /** 命中缓存时由数据库实例调用：不执行任何语句，只通知监听器。 */
    public void cacheHit(Rendered statement, long rows) { notify(statement, System.nanoTime(), rows, null, true); }

    /** 是否是数据库因超时取消语句而抛出的异常。 */
    private boolean timedOut(Throwable e) { return e instanceof SQLException s && dialect.isTimeout(s); }

    /** 在访问数据库之前就报错，因为数据库自己对参数过多的报错往往难以看懂。 */
    private void checkParameterCount(Rendered statement) {
        int count = statement.bindings().size();
        if (count > dialect.maxParameters()) {
            throw new IllegalStateException(database + ": the statement binds " + count + " parameters; " + dialect.name() + " accepts at most "
                + dialect.maxParameters() + ". Split the list into several queries, or select the values with a subquery instead of passing them");
        }
    }

    /** 创建 PreparedStatement 并设置提取行数、超时和参数；中途失败会先关闭它再抛出。 */
    private PreparedStatement prepare(Connection connection, Rendered statement, Duration timeout) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(statement.sql());
        try {
            if (fetchSize != 0) ps.setFetchSize(fetchSize); // MySQL 传 Integer.MIN_VALUE 表示逐行流式读取
            Duration limit = timeout != null ? timeout : defaultTimeout;
            if (limit != null) ps.setQueryTimeout(seconds(limit));
            connections.prepared(ps);
            List<Binding> bindings = statement.bindings();
            for (int i = 0; i < bindings.size(); i++) {
                Binding b = bindings.get(i);
                types.write(ps, i + 1, b.type().storageType(), b.databaseValue());
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    /** JDBC 的超时以整秒计，不足一秒的向上取整，即最短一秒后取消。 */
    private static int seconds(Duration timeout) {
        long seconds = (timeout.toMillis() + 999) / 1000;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
    }

    /** 归还连接；归还失败只吞掉，不覆盖语句本身的结果。 */
    private void release(Connection connection) {
        if (connection == null) return;
        try {
            connections.release(connection);
        } catch (SQLException ignored) {
            // 语句本身的结果比归还失败更重要。
        }
    }

    /** 依次关闭结果集和语句，两者失败互不影响。 */
    private static void closeQuietly(ResultSet rs, PreparedStatement ps) {
        try {
            if (rs != null) rs.close();
        } catch (SQLException ignored) {
            // 关闭只尽力而为
        }
        try {
            if (ps != null) ps.close();
        } catch (SQLException ignored) {
            // 关闭只尽力而为
        }
    }

    /** 执行方言的游标收尾动作，失败只吞掉。 */
    private static void cleanUpQuietly(CursorCleanup cleanup) {
        try {
            cleanup.run();
        } catch (SQLException ignored) {
            // 与关闭相同：流本身的结果更重要
        }
    }

    /** 构造事件并通知所有监听器；没有监听器时什么也不做。 */
    private void notify(Rendered statement, long start, long rows, Throwable error, boolean cached) {
        if (listeners.isEmpty()) return;
        var event = new QueryListener.Event(database, statement, Duration.ofNanos(System.nanoTime() - start), rows, error, cached);
        for (QueryListener l : listeners) {
            try {
                l.executed(event);
            } catch (RuntimeException ignored) {
                // 监听器不能破坏它所观察的查询。
            }
        }
    }
}
