package com.nozz.vouch.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.nozz.vouch.db.DatabaseTestSupport.countRows;
import static com.nozz.vouch.db.DatabaseTestSupport.createLegacy031Schema;
import static com.nozz.vouch.db.DatabaseTestSupport.exec;
import static com.nozz.vouch.db.DatabaseTestSupport.freshDatabaseName;
import static com.nozz.vouch.db.DatabaseTestSupport.openPool;
import static com.nozz.vouch.db.DatabaseTestSupport.readSchemaVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the schema migration ladder against a real H2 database.
 *
 * <p>The reason these exist: before this, {@code initializeSchema()} was four
 * {@code CREATE TABLE IF NOT EXISTS} statements with no version marker anywhere. On an
 * existing table that statement silently does nothing - no error, no added column - so
 * the first release to change the schema would have logged "initialized successfully"
 * and then failed every write on every already-deployed server.
 *
 * <p>{@link #legacyDatabaseWithoutVersionTableIsAdoptedNotClobbered()} is the one that
 * matters most: 0.3.1 databases in the wild carry no version marker at all.
 */
class SchemaMigrationTest {

    private String dbName;
    private DatabaseManager db;

    @BeforeEach
    void setUp() {
        dbName = freshDatabaseName("vouch_schema");
        openPool(dbName);
        db = DatabaseManager.getInstance();
    }

    @AfterEach
    void tearDown() {
        DatabaseTestSupport.tearDown();
    }

    // ==================== Fresh databases ====================

    @Test
    void emptyDatabaseGoesStraightToTheCurrentVersion() throws SQLException {
        db.initializeSchema();

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertEquals(DatabaseManager.CURRENT_SCHEMA_VERSION, readSchemaVersion(conn),
                    "a fresh database should be stamped at the current version");

            assertTrue(DatabaseManager.tableExists(conn, DatabaseManager.PLAYERS_TABLE));
            assertTrue(DatabaseManager.tableExists(conn, DatabaseManager.SESSIONS_TABLE));
            assertTrue(DatabaseManager.tableExists(conn, DatabaseManager.PREMIUM_OVERRIDES_TABLE));
            assertTrue(DatabaseManager.tableExists(conn, DatabaseManager.VERSION_TABLE));
            assertTrue(DatabaseManager.indexExists(conn,
                    DatabaseManager.SESSIONS_TABLE, DatabaseManager.SESSIONS_INDEX));
        }
    }

    @Test
    void repeatedInitializationIsIdempotentAndKeepsData() throws SQLException {
        db.initializeSchema();

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            insertPlayer(conn, "11111111-1111-1111-1111-111111111111", "Steve", "hash-steve");
        }

        db.initializeSchema();
        db.initializeSchema();

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertEquals(DatabaseManager.CURRENT_SCHEMA_VERSION, readSchemaVersion(conn));
            assertEquals(1, countRows(conn, DatabaseManager.PLAYERS_TABLE),
                    "re-running initializeSchema must never drop rows");
            assertEquals(1, countRows(conn, DatabaseManager.VERSION_TABLE),
                    "the version table holds exactly one row");
        }
    }

    // ==================== Legacy (0.3.1) adoption ====================

    /**
     * The whole point of the task: a 0.3.1 database has {@code vouch_players} but no
     * version table. That must be read as "this is v1, adopt it", not as "this is empty".
     */
    @Test
    void legacyDatabaseWithoutVersionTableIsAdoptedNotClobbered() throws SQLException {
        String uuid = "22222222-2222-2222-2222-222222222222";

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            // An H2/PostgreSQL-shaped 0.3.1 database: everything 0.3.1 meant to create.
            createLegacy031Schema(conn, true);
            insertPlayer(conn, uuid, "Alex", "$argon2id$v=19$legacy-hash");
            insertSession(conn, uuid, "203.0.113.7", "legacy-token-hash");

            assertFalse(DatabaseManager.tableExists(conn, DatabaseManager.VERSION_TABLE),
                    "precondition: a 0.3.1 database carries no version marker");
        }

        db.initializeSchema();

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertEquals(DatabaseManager.CURRENT_SCHEMA_VERSION, readSchemaVersion(conn),
                    "the legacy database should be adopted and migrated forward");

            // Existing rows survive untouched - this is a live server's account data.
            assertEquals(1, countRows(conn, DatabaseManager.PLAYERS_TABLE));
            assertEquals("$argon2id$v=19$legacy-hash", passwordHashOf(conn, uuid),
                    "the pre-existing password hash must survive the migration verbatim");
            assertEquals("Alex", usernameOf(conn, uuid));
            assertEquals(1, countRows(conn, DatabaseManager.SESSIONS_TABLE),
                    "existing sessions must survive the migration");
        }
    }

    /**
     * The MySQL-shaped 0.3.1 database. Those servers never got
     * {@code vouch_premium_overrides}: {@code CREATE INDEX IF NOT EXISTS} threw first
     * and, because the old initializer ran everything in one try-block, the statements
     * after it were skipped. The v2 repair migration is what fixes them.
     */
    @Test
    void legacyMysqlShapedDatabaseGetsItsMissingPremiumOverridesTable() throws SQLException {
        String uuid = "33333333-3333-3333-3333-333333333333";

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            createLegacy031Schema(conn, false);
            insertPlayer(conn, uuid, "Notch", "hash-notch");

            assertFalse(DatabaseManager.tableExists(conn, DatabaseManager.PREMIUM_OVERRIDES_TABLE),
                    "precondition: the MySQL 0.3.1 initializer never created this table");
            assertFalse(DatabaseManager.indexExists(conn,
                            DatabaseManager.SESSIONS_TABLE, DatabaseManager.SESSIONS_INDEX),
                    "precondition: the session index was never created either");
        }

        db.initializeSchema();

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertEquals(DatabaseManager.CURRENT_SCHEMA_VERSION, readSchemaVersion(conn));
            assertTrue(DatabaseManager.tableExists(conn, DatabaseManager.PREMIUM_OVERRIDES_TABLE),
                    "the v2 repair migration must create the missing overrides table");
            assertTrue(DatabaseManager.indexExists(conn,
                            DatabaseManager.SESSIONS_TABLE, DatabaseManager.SESSIONS_INDEX),
                    "the v2 repair migration must create the missing session index");
            assertEquals(1, countRows(conn, DatabaseManager.PLAYERS_TABLE),
                    "the repair must not touch existing player data");
        }
    }

    /**
     * Detection in isolation: "the players table exists but there is no version table"
     * must resolve to v1, and "nothing exists at all" must resolve to v0. Getting this
     * backwards is what would re-create a live server's schema from scratch.
     */
    @Test
    void versionDetectionDistinguishesALegacyDatabaseFromAnEmptyOne() throws SQLException {
        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertEquals(0, DatabaseManager.detectAndStampSchemaVersion(
                            conn, ConnectionFactory.DatabaseType.H2),
                    "an empty database is v0");
        }

        DatabaseTestSupport.tearDown();
        dbName = freshDatabaseName("vouch_schema");
        openPool(dbName);

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            createLegacy031Schema(conn, true);
            assertEquals(DatabaseManager.LEGACY_SCHEMA_VERSION,
                    DatabaseManager.detectAndStampSchemaVersion(conn, ConnectionFactory.DatabaseType.H2),
                    "a pre-versioning database must be adopted as v1, not treated as empty");
            assertEquals(DatabaseManager.LEGACY_SCHEMA_VERSION, readSchemaVersion(conn),
                    "detection also stamps the marker it decided on");
        }
    }

    @Test
    void theRepairMigrationNeverRecreatesThePlayersTable() {
        for (ConnectionFactory.DatabaseType type : ConnectionFactory.DatabaseType.values()) {
            assertFalse(DatabaseManager.migrationStatements(type, 2, true).stream()
                            .anyMatch(s -> s.contains(DatabaseManager.PLAYERS_TABLE)),
                    type + ": the v2 repair must not touch the players table");
        }
    }

    @Test
    void aDatabaseFromTheFutureIsRefusedRatherThanDowngraded() throws SQLException {
        db.initializeSchema();

        try (Connection conn = ConnectionFactory.getInstance().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE vouch_schema_version SET version = ? WHERE id = 1")) {
            stmt.setInt(1, DatabaseManager.CURRENT_SCHEMA_VERSION + 5);
            stmt.executeUpdate();
        }

        SQLException error = assertThrows(SQLException.class, () -> db.initializeSchema());
        assertTrue(error.getMessage().contains("Downgrading is not supported"),
                "expected a loud refusal, got: " + error.getMessage());
    }

    // ==================== Migration failure handling ====================

    /**
     * A failed migration must not advance the version marker, so the next boot retries
     * it instead of the server carrying on against a half-built schema.
     */
    @Test
    void aFailedMigrationAbortsLoudlyAndLeavesTheVersionMarkerAlone() throws SQLException {
        db.initializeSchema();
        int versionBefore;
        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            versionBefore = readSchemaVersion(conn);
        }

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            SQLException error = assertThrows(SQLException.class, () ->
                    DatabaseManager.applyMigration(conn, versionBefore + 1, List.of(
                            "CREATE TABLE vouch_migration_probe (id INTEGER PRIMARY KEY)",
                            "THIS IS NOT VALID SQL AT ALL")));

            assertTrue(error.getMessage().contains("FAILED"), error.getMessage());
            assertTrue(error.getMessage().contains("THIS IS NOT VALID SQL AT ALL"),
                    "the failing statement should be named in the error: " + error.getMessage());
            conn.setAutoCommit(true);
        }

        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertEquals(versionBefore, readSchemaVersion(conn),
                    "a failed migration must leave the version marker untouched so it is retried");
        }
    }

    // ==================== Per-dialect DDL ====================

    /**
     * {@code CREATE INDEX IF NOT EXISTS} is an H2/PostgreSQL/MariaDB extension. MySQL 8
     * rejects it outright, which is what silently cost every MySQL deployment its
     * {@code vouch_premium_overrides} table.
     */
    @Test
    void noDialectEmitsCreateIndexIfNotExists() {
        for (ConnectionFactory.DatabaseType type : ConnectionFactory.DatabaseType.values()) {
            for (int version = 1; version <= DatabaseManager.CURRENT_SCHEMA_VERSION; version++) {
                for (boolean indexExists : new boolean[]{true, false}) {
                    for (String sql : DatabaseManager.migrationStatements(type, version, indexExists)) {
                        String normalized = sql.toUpperCase().replaceAll("\\s+", " ");
                        assertFalse(normalized.contains("INDEX IF NOT EXISTS"),
                                type + " v" + version + " emits invalid-on-MySQL DDL: " + sql);
                    }
                }
            }
        }
    }

    @Test
    void indexCreationIsSkippedWhenTheIndexAlreadyExists() {
        for (ConnectionFactory.DatabaseType type : ConnectionFactory.DatabaseType.values()) {
            List<String> withIndex = DatabaseManager.migrationStatements(type, 1, false);
            List<String> withoutIndex = DatabaseManager.migrationStatements(type, 1, true);

            assertTrue(withIndex.stream().anyMatch(s -> s.contains(DatabaseManager.SESSIONS_INDEX)),
                    type + ": index should be created when absent");
            assertFalse(withoutIndex.stream().anyMatch(s -> s.contains(DatabaseManager.SESSIONS_INDEX)),
                    type + ": index creation must be skipped when it already exists");
        }
    }

    @Test
    void everyDialectCreatesThePremiumOverridesTable() {
        for (ConnectionFactory.DatabaseType type : ConnectionFactory.DatabaseType.values()) {
            assertTrue(DatabaseManager.migrationStatements(type, 1, false).stream()
                            .anyMatch(s -> s.contains(DatabaseManager.PREMIUM_OVERRIDES_TABLE)),
                    type + " v1 must create " + DatabaseManager.PREMIUM_OVERRIDES_TABLE);
            assertTrue(DatabaseManager.migrationStatements(type, 2, true).stream()
                            .anyMatch(s -> s.contains(DatabaseManager.PREMIUM_OVERRIDES_TABLE)),
                    type + " v2 must repair " + DatabaseManager.PREMIUM_OVERRIDES_TABLE);
        }
    }

    @Test
    void postgresUsesSerialAndTheOthersUseAutoIncrement() {
        String postgres = String.join("\n",
                DatabaseManager.migrationStatements(ConnectionFactory.DatabaseType.POSTGRESQL, 1, false));
        assertTrue(postgres.contains("SERIAL PRIMARY KEY"));
        assertFalse(postgres.contains("AUTO_INCREMENT"));

        for (ConnectionFactory.DatabaseType type : List.of(
                ConnectionFactory.DatabaseType.H2, ConnectionFactory.DatabaseType.MYSQL)) {
            String sql = String.join("\n", DatabaseManager.migrationStatements(type, 1, false));
            assertTrue(sql.contains("AUTO_INCREMENT"), type + " should use AUTO_INCREMENT");
        }
    }

    @Test
    void thereIsNoMigrationBeyondTheCurrentVersion() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseManager.migrationStatements(
                ConnectionFactory.DatabaseType.H2, DatabaseManager.CURRENT_SCHEMA_VERSION + 1, false));
    }

    // ==================== helpers ====================

    private static void insertPlayer(Connection conn, String uuid, String username, String hash)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO vouch_players (uuid, username, password_hash) VALUES (?, ?, ?)")) {
            stmt.setString(1, uuid);
            stmt.setString(2, username);
            stmt.setString(3, hash);
            stmt.executeUpdate();
        }
    }

    private static void insertSession(Connection conn, String uuid, String ip, String token)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO vouch_sessions (uuid, ip_address, session_token, expires_at) VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, uuid);
            stmt.setString(2, ip);
            stmt.setString(3, token);
            stmt.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(3600)));
            stmt.executeUpdate();
        }
    }

    private static String passwordHashOf(Connection conn, String uuid) throws SQLException {
        return singleString(conn, "SELECT password_hash FROM vouch_players WHERE uuid = ?", uuid);
    }

    private static String usernameOf(Connection conn, String uuid) throws SQLException {
        return singleString(conn, "SELECT username FROM vouch_players WHERE uuid = ?", uuid);
    }

    private static String singleString(Connection conn, String sql, String arg) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, arg);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
