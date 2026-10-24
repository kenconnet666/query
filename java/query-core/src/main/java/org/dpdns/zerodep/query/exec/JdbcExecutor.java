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
    private final List<StatementInterceptor> interceptors;
    private final int fetchSize;
    private final Duration defaultTimeout;

    /** 当前线程在 {@link #transaction} 里时，所有语句共用的连接。 */
    private final ThreadLocal<Connection> bound = new ThreadLocal<>();

    /** 多行写入时每次 executeBatch 的行数。 */
    static final int BATCH = 500;

    /**
     * @param database       数据库实例名，用于异常信息和监听事件
     * @param dialect        方言，提供游标处理、超时识别和参数个数上限
     * @param connections    连接来源
     * @param types          绑定参数与读取列所用的类型注册表
     * @param listeners      语句监听器
     * @param interceptors   执行前依次改写或拦截语句的钩子
     * @param fetchSize      JDBC 每次提取的行数；0 表示使用驱动默认值
     * @param defaultTimeout 默认查询超时；null 表示不限制
     */
    public JdbcExecutor(String database, Dialect dialect, ConnectionProvider connections, TypeRegistry types, List<QueryListener> listeners,
                        List<StatementInterceptor> interceptors, int fetchSize, Duration defaultTimeout) {
        this.database = database;
        this.dialect = dialect;
        this.connections = connections;
        this.types = types;
        this.listeners = List.copyOf(listeners);
        this.interceptors = List.copyOf(interceptors);
        this.fetchSize = fetchSize;
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * 把语句依次交给各个执行前钩子，返回最终要执行的语句。调用方在执行前调用一次
     * （查询缓存要用改写后的语句作键，所以不在执行方法里自动调用）。
     */
    public Rendered intercept(Rendered statement) {
        Rendered current = statement;
        for (StatementInterceptor i : interceptors) {
            current = i.intercept(current);
            if (current == null) throw new IllegalStateException("A StatementInterceptor returned null for " + statement.sql());
        }
        return current;
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
            connection = acquire();
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
            connection = acquire();
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

    /**
     * 执行写语句，返回影响的总行数；驱动没有报告行数（批量时的 SUCCESS_NO_INFO）时返回 -1。
     * SQL 相同的多条语句按批执行，每批 {@link #BATCH} 条；每批通知一次监听器，带上这一批的第一条语句。
     */
    public long update(List<Rendered> statements, Duration timeout) {
        statements.forEach(this::checkParameterCount);
        boolean batched = statements.size() > 1 && statements.stream().allMatch(s -> s.sql().equals(statements.get(0).sql()));
        long total = 0;
        Connection connection = null;
        try {
            connection = acquire();
            if (!batched) {
                for (Rendered s : statements) total = add(total, executeOne(connection, s, timeout));
                return total;
            }
            for (int from = 0; from < statements.size(); from += BATCH) {
                total = add(total, executeBatch(connection, statements.subList(from, Math.min(statements.size(), from + BATCH)), timeout));
            }
            return total;
        } catch (SQLException e) {
            throw new QueryExecutionException(database, statements.get(0), e, timedOut(e));
        } finally {
            release(connection);
        }
    }

    /** 执行一条写语句并通知监听器。 */
    private long executeOne(Connection connection, Rendered statement, Duration timeout) throws SQLException {
        long start = System.nanoTime();
        try (PreparedStatement ps = prepare(connection, statement, timeout)) {
            long rows = ps.executeUpdate();
            notify(statement, start, rows, null, false);
            return rows;
        } catch (SQLException | RuntimeException e) {
            notify(statement, start, 0, e, false);
            throw e;
        }
    }

    /** 把 SQL 相同的一批语句加进同一个 PreparedStatement 执行；任一行没有报告行数时返回 -1。 */
    private long executeBatch(Connection connection, List<Rendered> batch, Duration timeout) throws SQLException {
        long start = System.nanoTime();
        try (PreparedStatement ps = prepare(connection, batch.get(0), timeout)) {
            ps.addBatch();
            for (int i = 1; i < batch.size(); i++) {
                bind(ps, batch.get(i));
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            long rows = 0;
            for (int c : counts == null ? new int[batch.size()] : counts) rows = add(rows, c);
            notify(batch.get(0), start, Math.max(rows, 0), null, false);
            return rows;
        } catch (SQLException | RuntimeException e) {
            notify(batch.get(0), start, 0, e, false);
            throw e;
        }
    }

    /**
     * 逐行执行插入并取回每行生成的值，全部用同一个连接（HANA 在这个连接上查 IDENTITY）；column 是生成值的列名，
     * type 是它的类型。每行通知一次监听器。
     */
    public List<Object> insertKeys(List<Rendered> statements, String column, SqlType<?> type) {
        statements.forEach(this::checkParameterCount);
        Connection connection = null;
        try {
            connection = acquire();
            List<Object> keys = new ArrayList<>();
            for (Rendered s : statements) keys.add(insertKey(connection, s, column, type));
            return keys;
        } catch (SQLException e) {
            throw new QueryExecutionException(database, statements.get(0), e, timedOut(e));
        } finally {
            release(connection);
        }
    }

    /** 插入一行并读出生成的值；驱动没有给出时为 null。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object insertKey(Connection connection, Rendered statement, String column, SqlType<?> type) throws SQLException {
        long start = System.nanoTime();
        try (PreparedStatement ps = configure(dialect.prepareInsert(connection, statement.sql(), column), statement, null)) {
            long rows = ps.executeUpdate();
            ResultSet rs = dialect.generatedKey(connection, ps);
            try {
                Object key = rs != null && rs.next() ? ((SqlType) type).fromDatabase(types.read(rs, 1, type.storageType())) : null;
                notify(statement, start, rows, null, false);
                return key;
            } finally {
                closeWithStatement(rs, ps);
            }
        } catch (SQLException | RuntimeException e) {
            notify(statement, start, 0, e, false);
            throw e;
        }
    }

    /** 关闭取生成值的结果集；它来自另一条语句（HANA 的查询）时一并关闭那条语句。 */
    private static void closeWithStatement(ResultSet rs, PreparedStatement insert) throws SQLException {
        if (rs == null) return;
        java.sql.Statement owner = rs.getStatement();
        rs.close();
        if (owner != null && owner != insert) owner.close();
    }

    /** 当前线程是否在 {@link #transaction} 里。 */
    public boolean inTransaction() { return bound.get() != null; }

    /** 能否开启 {@link #transaction}：还不在本库的事务里，连接来源也不自己管理事务，或者它能按需开启事务。 */
    public boolean canStartTransaction() {
        return bound.get() == null && (!connections.managesTransactions() || connections.startsTransactions());
    }

    /** 累加行数；任一方未知（负数）时结果未知。 */
    private static long add(long total, long rows) { return total < 0 || rows < 0 ? -1 : total + rows; }

    /**
     * 在一个事务里执行 work：线程上绑定一个连接，work 里执行的语句都用它；正常结束时提交，抛出异常时回滚。
     * 已经在事务里时直接执行，加入外层事务。连接来源自己管理事务（例如 Spring）时交给它（加入或开启它的事务），
     * 它不能开启事务时报错。
     */
    public <T> T transaction(java.util.function.Supplier<T> work) {
        if (bound.get() != null) return work.get();
        if (connections.managesTransactions()) {
            if (connections.startsTransactions()) return connections.transaction(work);
            throw new UnsupportedOperationException(database + ": the connection provider joins its own transactions (e.g. Spring); "
                + "give it a transaction manager (SpringConnections.of(dataSource, transactionManager)) or use @Transactional");
        }
        Connection connection = null;
        boolean autoCommit = true;
        try {
            connection = connections.acquire();
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            bound.set(connection);
            T result = work.get();
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new IllegalStateException(database + ": the transaction failed: " + e.getMessage(), e);
        } catch (RuntimeException | Error e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            bound.remove();
            if (connection != null) {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // 归还前尽力恢复，失败不覆盖事务本身的结果
                }
                try {
                    connections.release(connection);
                } catch (SQLException ignored) {
                    // 同上
                }
            }
        }
    }

    /** 是否是空跑的连接来源：写语句不报告真实的行数，所以不检查期望的行数。 */
    public boolean dryRun() { return DryRun.is(connections); }

    /** 回滚，失败只吞掉，保留原来的异常。 */
    private static void rollbackQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 原来的异常更重要
        }
    }

    /** 当前线程在事务里时用事务的连接，否则从连接来源取一个。 */
    private Connection acquire() throws SQLException {
        Connection c = bound.get();
        return c != null ? c : connections.acquire();
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
        return configure(connection.prepareStatement(statement.sql()), statement, timeout);
    }

    /**
     * 设置提取行数、超时和参数，返回要使用的语句：驱动不按 setQueryTimeout 取消时（见 {@link Dialect#honoursQueryTimeout}），
     * 返回的语句带一个到时调用 {@code cancel()} 的看门狗，关闭语句时撤销。出错时关闭 ps。
     */
    private PreparedStatement configure(PreparedStatement ps, Rendered statement, Duration timeout) throws SQLException {
        try {
            if (fetchSize != 0) ps.setFetchSize(fetchSize); // MySQL 传 Integer.MIN_VALUE 表示逐行流式读取
            Duration limit = timeout != null ? timeout : defaultTimeout;
            boolean watch = limit != null && !dialect.honoursQueryTimeout();
            if (limit != null && !watch) ps.setQueryTimeout(seconds(limit));
            connections.prepared(ps);   // 在超时之后，连接来源（例如 Spring 的事务超时）能看到并调整它
            bind(ps, statement);
            return watch ? Watchdog.guard(ps, limit) : ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    /** 按语句的绑定依次设置参数。 */
    private void bind(PreparedStatement ps, Rendered statement) throws SQLException {
        List<Binding> bindings = statement.bindings();
        for (int i = 0; i < bindings.size(); i++) {
            Binding b = bindings.get(i);
            types.write(ps, i + 1, b.type().storageType(), b.databaseValue());
        }
    }

    /** JDBC 的超时以整秒计，不足一秒的向上取整，即最短一秒后取消。 */
    private static int seconds(Duration timeout) {
        long seconds = (timeout.toMillis() + 999) / 1000;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
    }

    /** 归还连接（事务里的连接由事务归还）；归还失败只吞掉，不覆盖语句本身的结果。 */
    private void release(Connection connection) {
        if (connection == null || connection == bound.get()) return;
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
