package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Binding;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Dialect.CursorCleanup;
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

/** Runs rendered statements: binds parameters through the type registry, reads rows, notifies listeners. */
public final class JdbcExecutor {
    @FunctionalInterface
    public interface RowReader {
        Object[] read(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface ResultHandler<T> {
        T handle(ResultSet rs) throws SQLException;
    }

    private final String database;
    private final Dialect dialect;
    private final ConnectionProvider connections;
    private final TypeRegistry types;
    private final List<QueryListener> listeners;
    private final int fetchSize;
    private final Duration defaultTimeout;

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

    public TypeRegistry types() { return types; }

    /** Reads columns 1..n with the given types, decoding through each type's converter. */
    public RowReader columns(List<SqlType<?>> columnTypes) {
        SqlType<?>[] t = columnTypes.toArray(SqlType<?>[]::new);
        return rs -> {
            Object[] row = new Object[t.length];
            for (int i = 0; i < t.length; i++) row[i] = t[i].fromDatabase(types.read(rs, i + 1, t[i].storageType()));
            return row;
        };
    }

    /** {@code timeout} null means the database instance's default. */
    public List<Object[]> fetch(Rendered statement, RowReader reader, Duration timeout) {
        return query(statement, rs -> {
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) rows.add(reader.read(rs));
            return rows;
        }, List::size, timeout);
    }

    /** Runs a statement and hands its result set to the handler; rowCount reports the size to listeners. */
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
            throw new QueryExecutionException(database, statement, e);
        } catch (RuntimeException e) {
            notify(statement, start, 0, e, false);
            throw e;
        } finally {
            release(connection);
        }
    }

    /** A lazily read result; closing the stream closes the cursor and releases the connection. */
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
            throw new QueryExecutionException(database, statement, e);
        }
        Connection c = connection;
        PreparedStatement p = ps;
        CursorCleanup undo = cleanup;
        ResultSet r = rs;
        long[] count = {0};
        Spliterator<Object[]> rows = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super Object[]> action) {
                try {
                    if (!r.next()) return false;
                    count[0]++;
                    action.accept(reader.read(r));
                    return true;
                } catch (SQLException e) {
                    throw new QueryExecutionException(database, statement, e);
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

    /** Lets the database report a cache hit to listeners without running anything. */
    public void cacheHit(Rendered statement, long rows) { notify(statement, System.nanoTime(), rows, null, true); }

    /** Fails before contacting the database, whose own error for too many parameters is often cryptic. */
    private void checkParameterCount(Rendered statement) {
        int count = statement.bindings().size();
        if (count > dialect.maxParameters()) {
            throw new IllegalStateException(database + ": the statement binds " + count + " parameters; " + dialect.name() + " accepts at most "
                + dialect.maxParameters() + ". Split the list into several queries, or select the values with a subquery instead of passing them");
        }
    }

    private PreparedStatement prepare(Connection connection, Rendered statement, Duration timeout) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(statement.sql());
        try {
            if (fetchSize != 0) ps.setFetchSize(fetchSize); // MySQL streams row by row with Integer.MIN_VALUE
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

    /** JDBC counts whole seconds; a shorter timeout still cancels after one. */
    private static int seconds(Duration timeout) {
        long seconds = (timeout.toMillis() + 999) / 1000;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
    }

    private void release(Connection connection) {
        if (connection == null) return;
        try {
            connections.release(connection);
        } catch (SQLException ignored) {
            // The statement's own outcome matters more than a failed release.
        }
    }

    private static void closeQuietly(ResultSet rs, PreparedStatement ps) {
        try {
            if (rs != null) rs.close();
        } catch (SQLException ignored) {
            // closing is best effort
        }
        try {
            if (ps != null) ps.close();
        } catch (SQLException ignored) {
            // closing is best effort
        }
    }

    private static void cleanUpQuietly(CursorCleanup cleanup) {
        try {
            cleanup.run();
        } catch (SQLException ignored) {
            // like closing: the stream's own outcome matters more
        }
    }

    private void notify(Rendered statement, long start, long rows, Throwable error, boolean cached) {
        if (listeners.isEmpty()) return;
        var event = new QueryListener.Event(database, statement, Duration.ofNanos(System.nanoTime() - start), rows, error, cached);
        for (QueryListener l : listeners) {
            try {
                l.executed(event);
            } catch (RuntimeException ignored) {
                // A listener must not break the query it observes.
            }
        }
    }
}
