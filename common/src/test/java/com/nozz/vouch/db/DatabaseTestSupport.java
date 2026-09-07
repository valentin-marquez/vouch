package com.nozz.vouch.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory H2 harness for the database tests.
 *
 * <p>{@link ConnectionFactory#initialize(ConnectionFactory.DatabaseType, String, String, String, int, int)}
 * is the seam: it takes the pool sizing as parameters, so none of this needs
 * {@code VouchConfigManager} to have been loaded.
 *
 * <p>The URL mirrors production H2 as closely as an in-memory database can:
 * {@code MODE=MySQL} is what {@code VouchConfigManager.getJdbcUrl} builds, and it is
 * what makes {@code AUTO_INCREMENT} and {@code REPLACE INTO} behave as they do on a
 * real server. {@code DB_CLOSE_DELAY=-1} keeps the database alive while the pool is
 * closed and reopened mid-test.
 */
final class DatabaseTestSupport {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private DatabaseTestSupport() {
    }

    static String freshDatabaseName(String prefix) {
        return prefix + "_" + DB_COUNTER.incrementAndGet();
    }

    static String jdbcUrl(String databaseName) {
        return "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    /** Open (or re-open) the pool against the given in-memory database. */
    static void openPool(String databaseName, int poolMaxSize, int poolMinIdle) {
        ConnectionFactory.getInstance().initialize(
                ConnectionFactory.DatabaseType.H2, jdbcUrl(databaseName), "sa", "",
                poolMaxSize, poolMinIdle);
    }

    static void openPool(String databaseName) {
        openPool(databaseName, 4, 1);
    }

    /** Drop everything and release the pool, so the next test starts clean. */
    static void tearDown() {
        ConnectionFactory factory = ConnectionFactory.getInstance();
        if (factory.isInitialized()) {
            try (Connection conn = factory.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
                // Best effort: every test uses a uniquely named database anyway.
            }
        }
        DatabaseManager.getInstance().shutdown();
        factory.close();
    }

    static void exec(Connection conn, String... statements) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    /**
     * The exact schema Vouch 0.3.1 left behind, written out by hand rather than
     * generated from production code so the test still describes the old shape after
     * the production DDL moves on.
     *
     * @param withIndexAndOverrides {@code true} reproduces an H2/PostgreSQL 0.3.1
     *        database (the initializer ran to completion); {@code false} reproduces a
     *        MySQL one, where {@code CREATE INDEX IF NOT EXISTS} threw and took
     *        {@code vouch_premium_overrides} down with it.
     */
    static void createLegacy031Schema(Connection conn, boolean withIndexAndOverrides) throws SQLException {
        exec(conn, """
                CREATE TABLE vouch_players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    totp_secret VARCHAR(64),
                    totp_enabled BOOLEAN DEFAULT FALSE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    last_login TIMESTAMP,
                    last_ip VARCHAR(45)
                )
                """, """
                CREATE TABLE vouch_sessions (
                    id INTEGER PRIMARY KEY AUTO_INCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    ip_address VARCHAR(45) NOT NULL,
                    session_token VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP NOT NULL,
                    FOREIGN KEY (uuid) REFERENCES vouch_players(uuid) ON DELETE CASCADE
                )
                """);

        if (withIndexAndOverrides) {
            exec(conn,
                    "CREATE INDEX idx_sessions_uuid_ip ON vouch_sessions(uuid, ip_address)",
                    """
                    CREATE TABLE vouch_premium_overrides (
                        username VARCHAR(16) PRIMARY KEY,
                        forced_offline BOOLEAN DEFAULT FALSE,
                        set_by VARCHAR(36),
                        set_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    static int readSchemaVersion(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT version FROM vouch_schema_version WHERE id = 1");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt("version") : -1;
        }
    }

    static long countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
