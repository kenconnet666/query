package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Connection settings for live tests, from QUERY_{NAME}_URL / _USER / _PASSWORD. Tests are skipped
 * when the variables are absent, so a plain build needs no database. CI also sets
 * QUERY_REQUIRE_LIVE, which turns a missing variable into a failure instead of a silent skip.
 */
public record LiveDatabase(String url, String user, String password, List<String> onOpen) {
    /** onOpen: statements run on every new connection, e.g. SQLite attaching the QUERY_IT database. */
    public LiveDatabase {
        onOpen = List.copyOf(onOpen);
    }

    /** A database that needs nothing run when a connection opens. */
    public LiveDatabase(String url, String user, String password) { this(url, user, password, List.of()); }

    public static LiveDatabase fromEnvironment(String name) {
        String url = System.getenv("QUERY_" + name + "_URL");
        String user = System.getenv("QUERY_" + name + "_USER");
        String password = System.getenv("QUERY_" + name + "_PASSWORD");
        boolean configured = url != null && user != null && password != null;
        String message = "QUERY_" + name + "_URL/_USER/_PASSWORD not set";
        if (System.getenv("QUERY_REQUIRE_LIVE") != null && !configured) throw new IllegalStateException(message + " but QUERY_REQUIRE_LIVE is");
        Assumptions.assumeTrue(configured, message + "; skipping live " + name + " tests");
        return new LiveDatabase(url, user, password);
    }

    public Connection open() throws SQLException {
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

    public ConnectionProvider connections() {
        return new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException { return open(); }

            @Override public void release(Connection connection) throws SQLException { connection.close(); }
        };
    }

    /** Runs statements in order; a statement starting with "-" may fail (e.g. dropping what does not exist). */
    public void execute(List<String> statements) throws SQLException {
        try (Connection c = open(); Statement s = c.createStatement()) {
            for (String sql : statements) {
                boolean optional = sql.startsWith("-");
                try {
                    s.execute(optional ? sql.substring(1) : sql);
                } catch (SQLException e) {
                    if (!optional) throw new SQLException(e.getMessage() + "\n" + sql, e);
                }
            }
        }
    }
}
