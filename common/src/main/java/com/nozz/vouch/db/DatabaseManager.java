package com.nozz.vouch.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Database manager for Vouch player data.
 *
 * Handles storage and retrieval of:
 * - Player credentials (UUID, password hash)
 * - 2FA secrets
 * - Session data for persistence
 * - Premium (forced-offline) overrides
 *
 * <h2>Schema versioning</h2>
 * The schema is owned by a migration ladder keyed off the {@code vouch_schema_version}
 * table (see {@link #initializeSchema()}). {@code CREATE TABLE IF NOT EXISTS} alone is
 * NOT a schema management strategy: on an existing table it silently does nothing, so a
 * release that adds a column would log success and then fail every write on every
 * already-deployed server.
 *
 * <h2>Threading</h2>
 * Every async method runs on this manager's own bounded, named, daemon-threaded
 * executor - never on {@link java.util.concurrent.ForkJoinPool#commonPool()}, whose
 * parallelism is {@code cores - 1} (i.e. a single thread on a 2-vCPU host) and which is
 * shared with the rest of the JVM. Blocking JDBC calls with a 10s pool timeout have no
 * business there.
 */
public final class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/DatabaseManager");

    /**
     * Schema version this build expects. Bump it and add a {@code case} to
     * {@link #migrationStatements} whenever the schema changes.
     */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /**
     * Version stamped onto a database that predates schema versioning entirely
     * (Vouch &lt;= 0.3.1). Such databases have {@code vouch_players} but no version
     * table; they must be adopted at this version, never re-created from scratch.
     */
    static final int LEGACY_SCHEMA_VERSION = 1;

    static final String VERSION_TABLE = "vouch_schema_version";
    static final String PLAYERS_TABLE = "vouch_players";
    static final String SESSIONS_TABLE = "vouch_sessions";
    static final String PREMIUM_OVERRIDES_TABLE = "vouch_premium_overrides";
    static final String SESSIONS_INDEX = "idx_sessions_uuid_ip";

    /** The executor never drops below this many threads, however small the pool is. */
    private static final int MIN_DB_THREADS = 2;

    private static DatabaseManager instance;
    private final ConnectionFactory connectionFactory;

    private final Object executorLock = new Object();
    private volatile ExecutorService dbExecutor;

    private DatabaseManager() {
        this.connectionFactory = ConnectionFactory.getInstance();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    // ==================== Executor ====================

    /**
     * The executor all database work runs on. Created lazily and sized from the
     * connection pool, since more threads than connections only queues work up
     * against Hikari's connection timeout.
     */
    public ExecutorService getExecutor() {
        ExecutorService current = dbExecutor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (executorLock) {
            if (dbExecutor == null || dbExecutor.isShutdown()) {
                int threads = Math.max(MIN_DB_THREADS, connectionFactory.getPoolMaxSize());
                dbExecutor = createExecutor(threads);
                // Drain before the pool is torn down, without DatabaseManager having to
                // be wired into the mod's shutdown path by hand.
                connectionFactory.addPreCloseHook(this::shutdown);
                LOGGER.debug("Database executor started with {} threads", threads);
            }
            return dbExecutor;
        }
    }

    private static ExecutorService createExecutor(int threads) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Vouch-DB-" + counter.getAndIncrement());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, error) ->
                        LOGGER.error("Uncaught error on database thread {}", thread.getName(), error));
                return t;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Stop the database executor, letting in-flight queries finish. Idempotent, and
     * a later call re-creates the executor, so this is safe to call on reload.
     */
    public void shutdown() {
        ExecutorService executor;
        synchronized (executorLock) {
            executor = dbExecutor;
            dbExecutor = null;
        }
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Database executor did not drain in 5s, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.debug("Database executor stopped");
    }

    private <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, getExecutor());
    }

    private CompletableFuture<Void> asyncRun(Runnable work) {
        return CompletableFuture.runAsync(work, getExecutor());
    }

    // ==================== Schema versioning ====================

    /**
     * Bring the database up to {@link #CURRENT_SCHEMA_VERSION}.
     *
     * <p>Three starting states are handled:
     * <ul>
     *   <li><b>Empty database</b> - no version table, no {@code vouch_players}: stamped
     *       at 0 and taken through the whole ladder.</li>
     *   <li><b>Legacy database</b> (Vouch &lt;= 0.3.1) - {@code vouch_players} exists but
     *       there is no version table: <em>adopted</em> at {@link #LEGACY_SCHEMA_VERSION}
     *       with its data untouched, then migrated forward.</li>
     *   <li><b>Versioned database</b> - the ladder runs from whatever version is recorded.</li>
     * </ul>
     *
     * <p>Each migration runs in its own transaction and advances the version marker as its
     * final statement. A failure aborts with an exception and leaves the marker at the
     * previous version, so the migration is retried on the next boot rather than the
     * server carrying on against a half-built schema. (MySQL and H2 commit implicitly on
     * DDL, so the table changes themselves may survive a rollback there - the un-advanced
     * version marker plus idempotent {@code IF NOT EXISTS} DDL is what makes the retry safe.)
     */
    public void initializeSchema() throws SQLException {
        LOGGER.info("Initializing database schema...");

        ConnectionFactory.DatabaseType dbType = connectionFactory.getDatabaseType();

        try (Connection conn = connectionFactory.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            try {
                int from = detectAndStampSchemaVersion(conn, dbType);

                if (from > CURRENT_SCHEMA_VERSION) {
                    throw new SQLException("Vouch database is at schema v" + from
                            + " but this build only understands v" + CURRENT_SCHEMA_VERSION
                            + ". Downgrading is not supported - restore a backup or upgrade the mod.");
                }
                if (from == CURRENT_SCHEMA_VERSION) {
                    LOGGER.info("Database schema is up to date (v{})", from);
                    return;
                }

                LOGGER.info("Migrating database schema v{} -> v{}", from, CURRENT_SCHEMA_VERSION);
                for (int target = from + 1; target <= CURRENT_SCHEMA_VERSION; target++) {
                    boolean sessionsIndexExists = indexExists(conn, SESSIONS_TABLE, SESSIONS_INDEX);
                    applyMigration(conn, target, migrationStatements(dbType, target, sessionsIndexExists));
                    LOGGER.info("Applied schema migration v{}", target);
                }
                LOGGER.info("Database schema initialized successfully (v{})", CURRENT_SCHEMA_VERSION);
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Connection is going back to the pool either way.
                }
            }
        }
    }

    /**
     * Work out which schema version the database is at, creating and stamping the
     * version table when it is absent.
     *
     * <p>The load-bearing case is the middle branch: a 0.3.1 database in the wild carries
     * no version marker at all, so "no version table" must NOT be read as "empty database".
     */
    static int detectAndStampSchemaVersion(Connection conn, ConnectionFactory.DatabaseType dbType)
            throws SQLException {
        if (tableExists(conn, VERSION_TABLE)) {
            return readSchemaVersion(conn);
        }

        int detected;
        if (tableExists(conn, PLAYERS_TABLE)) {
            detected = LEGACY_SCHEMA_VERSION;
            LOGGER.info("Found a pre-versioning Vouch database ({} exists, no {} table) - "
                    + "adopting it as schema v{}", PLAYERS_TABLE, VERSION_TABLE, LEGACY_SCHEMA_VERSION);
        } else {
            detected = 0;
            LOGGER.info("No existing Vouch tables found - creating a fresh schema");
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(versionTableDdl(dbType));
        }
        writeSchemaVersion(conn, detected);
        return detected;
    }

    static String versionTableDdl(ConnectionFactory.DatabaseType dbType) {
        // Single-row table (id is always 1); no dialect differences needed here.
        return """
                CREATE TABLE IF NOT EXISTS vouch_schema_version (
                    id INTEGER PRIMARY KEY,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMP
                )
                """;
    }

    private static int readSchemaVersion(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT version FROM " + VERSION_TABLE + " WHERE id = 1");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("version");
            }
        }
        // Version table present but empty: fall back to the same legacy-adoption rule so
        // an interrupted first run cannot be mistaken for an empty database.
        return tableExists(conn, PLAYERS_TABLE) ? LEGACY_SCHEMA_VERSION : 0;
    }

    private static void writeSchemaVersion(Connection conn, int version) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + VERSION_TABLE + " SET version = ?, updated_at = ? WHERE id = 1")) {
            update.setInt(1, version);
            update.setTimestamp(2, Timestamp.from(Instant.now()));
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO " + VERSION_TABLE + " (id, version, updated_at) VALUES (1, ?, ?)")) {
            insert.setInt(1, version);
            insert.setTimestamp(2, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    /**
     * Run one migration inside a transaction, advancing the version marker as the last
     * statement so the marker can only move once every statement has succeeded.
     *
     * @throws SQLException with the failing statement in the message; callers must let
     *         this abort startup rather than continuing on a half-migrated schema.
     */
    static void applyMigration(Connection conn, int targetVersion, List<String> statements) throws SQLException {
        conn.setAutoCommit(false);
        String failing = null;
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                failing = sql;
                stmt.execute(sql);
            }
            failing = null;
            writeSchemaVersion(conn, targetVersion);
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            String where = failing == null
                    ? "while recording the new schema version"
                    : "on statement: " + failing.strip().replaceAll("\\s+", " ");
            throw new SQLException("Vouch schema migration to v" + targetVersion + " FAILED " + where
                    + ". The schema version marker was left unchanged; fix the database and restart. "
                    + "Vouch must not run against a half-migrated schema.", e);
        }
    }

    /**
     * The statements making up a single migration step, per dialect.
     *
     * <p>Note what is deliberately absent: {@code CREATE INDEX IF NOT EXISTS}. H2 and
     * PostgreSQL accept it, but it is a MariaDB extension that MySQL 8 rejects outright -
     * which is exactly how {@code vouch_premium_overrides} came to be missing on every
     * MySQL deployment (the failure aborted the rest of the single try-block). Index
     * creation is guarded by a {@code DatabaseMetaData} check instead, uniformly.
     *
     * @param sessionsIndexExists whether {@code idx_sessions_uuid_ip} is already present
     */
    static List<String> migrationStatements(ConnectionFactory.DatabaseType dbType,
                                            int version,
                                            boolean sessionsIndexExists) {
        List<String> statements = new ArrayList<>();

        switch (version) {
            case 1 -> {
                // v1: the baseline schema (what Vouch <= 0.3.1 was meant to create).
                statements.add("""
                        CREATE TABLE IF NOT EXISTS vouch_players (
                            uuid VARCHAR(36) PRIMARY KEY,
                            username VARCHAR(16) NOT NULL,
                            password_hash VARCHAR(255) NOT NULL,
                            totp_secret VARCHAR(64),
                            totp_enabled BOOLEAN DEFAULT FALSE,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            last_login TIMESTAMP,
                            last_ip VARCHAR(45)
                        )
                        """);
                statements.add(sessionsTableDdl(dbType));
                statements.add(premiumOverridesTableDdl());
                if (!sessionsIndexExists) {
                    statements.add(sessionsIndexDdl());
                }
            }
            case 2 -> {
                // v2: repair pass for databases created by <= 0.3.1.
                //
                // On MySQL the 0.3.1 initializer died on `CREATE INDEX IF NOT EXISTS`
                // before it ever reached vouch_premium_overrides, so those servers have
                // been running without the table (and without the index) since install.
                // Both statements are no-ops where 0.3.1 succeeded (H2, PostgreSQL).
                statements.add(premiumOverridesTableDdl());
                if (!sessionsIndexExists) {
                    statements.add(sessionsIndexDdl());
                }
            }
            default -> throw new IllegalArgumentException("No migration defined for schema v" + version);
        }

        return statements;
    }

    private static String sessionsTableDdl(ConnectionFactory.DatabaseType dbType) {
        return switch (dbType) {
            case POSTGRESQL -> """
                    CREATE TABLE IF NOT EXISTS vouch_sessions (
                        id SERIAL PRIMARY KEY,
                        uuid VARCHAR(36) NOT NULL,
                        ip_address VARCHAR(45) NOT NULL,
                        session_token VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NOT NULL,
                        FOREIGN KEY (uuid) REFERENCES vouch_players(uuid) ON DELETE CASCADE
                    )
                    """;
            case H2, MYSQL -> """
                    CREATE TABLE IF NOT EXISTS vouch_sessions (
                        id INTEGER PRIMARY KEY AUTO_INCREMENT,
                        uuid VARCHAR(36) NOT NULL,
                        ip_address VARCHAR(45) NOT NULL,
                        session_token VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NOT NULL,
                        FOREIGN KEY (uuid) REFERENCES vouch_players(uuid) ON DELETE CASCADE
                    )
                    """;
        };
    }

    private static String premiumOverridesTableDdl() {
        // CREATE TABLE IF NOT EXISTS is valid on all three dialects.
        return """
                CREATE TABLE IF NOT EXISTS vouch_premium_overrides (
                    username VARCHAR(16) PRIMARY KEY,
                    forced_offline BOOLEAN DEFAULT FALSE,
                    set_by VARCHAR(36),
                    set_at TIMESTAMP
                )
                """;
    }

    private static String sessionsIndexDdl() {
        return "CREATE INDEX " + SESSIONS_INDEX + " ON " + SESSIONS_TABLE + " (uuid, ip_address)";
    }

    /**
     * Case-insensitive table lookup: H2 folds unquoted identifiers to upper case,
     * PostgreSQL to lower case, and MySQL depends on {@code lower_case_table_names}.
     */
    static boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = catalogOf(conn);
        for (String candidate : new String[]{table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getTables(catalog, null, candidate, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Same case dance as {@link #tableExists}, for indexes. */
    static boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        if (!tableExists(conn, table)) {
            return false;
        }
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = catalogOf(conn);
        for (String candidate : new String[]{table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getIndexInfo(catalog, null, candidate, false, true)) {
                while (rs.next()) {
                    String found = rs.getString("INDEX_NAME");
                    if (found != null && found.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            } catch (SQLException ignored) {
                // Some drivers throw rather than return empty for an unknown table-name case.
            }
        }
        return false;
    }

    private static String catalogOf(Connection conn) {
        try {
            return conn.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Check if a player is registered
     */
    public CompletableFuture<Boolean> isRegistered(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT 1 FROM vouch_players WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }

            } catch (SQLException e) {
                LOGGER.error("Error checking player registration", e);
                return false;
            }
        });
    }

    /**
     * Register a new player
     */
    public CompletableFuture<Boolean> registerPlayer(UUID uuid, String username, String passwordHash) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO vouch_players (uuid, username, password_hash) VALUES (?, ?, ?)")) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, username);
                stmt.setString(3, passwordHash);
                stmt.executeUpdate();

                LOGGER.info("Player {} registered successfully", username);
                return true;

            } catch (SQLException e) {
                LOGGER.error("Error registering player {}", username, e);
                return false;
            }
        });
    }

    /**
     * Ensure a premium-verified player has a row in vouch_players.
     * Uses INSERT ... WHERE NOT EXISTS to avoid duplicates if the player already registered.
     */
    public CompletableFuture<Boolean> ensurePremiumPlayerRegistered(UUID uuid, String username) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO vouch_players (uuid, username, password_hash) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM vouch_players WHERE uuid = ?)")) {

                String premiumHash = "PREMIUM_ACCOUNT";
                stmt.setString(1, uuid.toString());
                stmt.setString(2, username);
                stmt.setString(3, premiumHash);
                stmt.setString(4, uuid.toString());
                int rows = stmt.executeUpdate();

                if (rows > 0) {
                    LOGGER.info("Premium player {} auto-registered in database", username);
                }
                return true;

            } catch (SQLException e) {
                LOGGER.error("Error ensuring premium player {} is registered", username, e);
                return false;
            }
        });
    }

    /**
     * Register a new player with 2FA directly (for 2FA-only mode).
     * Creates the player record with the TOTP secret enabled and no password.
     */
    public CompletableFuture<Boolean> registerPlayerWith2FA(UUID uuid, String username, String totpSecret) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO vouch_players (uuid, username, password_hash, totp_secret, totp_enabled) VALUES (?, ?, '', ?, TRUE)")) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, username);
                stmt.setString(3, totpSecret);
                stmt.executeUpdate();

                LOGGER.info("Player {} registered with 2FA (2FA-only mode)", username);
                return true;

            } catch (SQLException e) {
                LOGGER.error("Error registering player {} with 2FA", username, e);
                return false;
            }
        });
    }

    /**
     * Get stored password hash for a player
     */
    public CompletableFuture<Optional<String>> getPasswordHash(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT password_hash FROM vouch_players WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("password_hash"));
                    }
                }

            } catch (SQLException e) {
                LOGGER.error("Error getting password hash", e);
            }
            return Optional.empty();
        });
    }

    /**
     * Replace the stored password hash of an existing account.
     *
     * <p>Two callers: an explicit password change, and the transparent rehash that
     * {@code /login} performs when {@code Argon2Hasher.needsRehash} says the stored
     * hash was produced with parameters the server no longer uses. The rehash can
     * only happen there, because that successful verification is the one moment the
     * plain text password exists.
     *
     * <p>A blank hash is refused rather than written: the column is the only thing
     * standing between an account and anyone who claims it, and a caller that
     * managed to produce an empty hash has already gone wrong.
     *
     * @return {@code true} when a row was updated; {@code false} for an unknown
     *         account, a refused hash, or a database error
     */
    public CompletableFuture<Boolean> updatePassword(UUID uuid, String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            LOGGER.error("Refusing to store a blank password hash for {}", uuid);
            return CompletableFuture.completedFuture(false);
        }
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE vouch_players SET password_hash = ? WHERE uuid = ?")) {

                stmt.setString(1, passwordHash);
                stmt.setString(2, uuid.toString());
                return stmt.executeUpdate() > 0;

            } catch (SQLException e) {
                LOGGER.error("Error updating password for {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * Every registered username, for seeding the in-memory set that feeds
     * tab-completion at server start.
     *
     * <p>Run once at boot, never per keystroke. Returns an empty list rather than
     * failing: an empty completion list is a worse experience than a full one, but
     * it is not an outage.
     */
    public CompletableFuture<List<String>> listRegisteredUsernames() {
        return async(() -> {
            List<String> usernames = new ArrayList<>();
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT username FROM vouch_players");
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String username = rs.getString("username");
                    if (username != null && !username.isBlank()) {
                        usernames.add(username);
                    }
                }

            } catch (SQLException e) {
                LOGGER.error("Error listing registered usernames", e);
            }
            return usernames;
        });
    }

    /** One account by UUID, without its credentials. See {@link PlayerRecord}. */
    public CompletableFuture<Optional<PlayerRecord>> getPlayerRecord(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT uuid, username, totp_enabled, last_login, last_ip, created_at"
                                 + " FROM vouch_players WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(readPlayerRecord(rs)) : Optional.<PlayerRecord>empty();
                }

            } catch (SQLException e) {
                LOGGER.error("Error reading player record for {}", uuid, e);
                return Optional.<PlayerRecord>empty();
            }
        });
    }

    /**
     * One account by username, case-insensitively.
     *
     * <p>Admin commands name a player by the string an admin typed, and that player
     * may well be offline and absent from the server's user cache. Looking the name
     * up here is what lets {@code /vouch admin user &lt;player&gt; info} answer for
     * someone who has not logged in for a month.
     */
    public CompletableFuture<Optional<PlayerRecord>> findPlayerByName(String username) {
        if (username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT uuid, username, totp_enabled, last_login, last_ip, created_at"
                                 + " FROM vouch_players WHERE LOWER(username) = ?")) {

                stmt.setString(1, normalized);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(readPlayerRecord(rs)) : Optional.<PlayerRecord>empty();
                }

            } catch (SQLException e) {
                LOGGER.error("Error looking up player named {}", username, e);
                return Optional.<PlayerRecord>empty();
            }
        });
    }

    private static PlayerRecord readPlayerRecord(ResultSet rs) throws SQLException {
        return new PlayerRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getBoolean("totp_enabled"),
                Optional.ofNullable(rs.getTimestamp("last_login")).map(Timestamp::toInstant),
                Optional.ofNullable(rs.getString("last_ip")),
                Optional.ofNullable(rs.getTimestamp("created_at")).map(Timestamp::toInstant));
    }

    /**
     * Update last login info
     */
    public CompletableFuture<Void> updateLastLogin(UUID uuid, String ip) {
        return asyncRun(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE vouch_players SET last_login = ?, last_ip = ? WHERE uuid = ?")) {

                stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                stmt.setString(2, ip);
                stmt.setString(3, uuid.toString());
                stmt.executeUpdate();

            } catch (SQLException e) {
                LOGGER.error("Error updating last login", e);
            }
        });
    }

    /**
     * Store TOTP secret for a player
     */
    public CompletableFuture<Boolean> storeTOTPSecret(UUID uuid, String secret) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE vouch_players SET totp_secret = ?, totp_enabled = TRUE WHERE uuid = ?")) {

                stmt.setString(1, secret);
                stmt.setString(2, uuid.toString());
                int updated = stmt.executeUpdate();

                return updated > 0;

            } catch (SQLException e) {
                LOGGER.error("Error storing TOTP secret", e);
                return false;
            }
        });
    }

    /**
     * Get TOTP secret for a player
     */
    public CompletableFuture<Optional<String>> getTOTPSecret(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT totp_secret FROM vouch_players WHERE uuid = ? AND totp_enabled = TRUE")) {

                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(rs.getString("totp_secret"));
                    }
                }

            } catch (SQLException e) {
                LOGGER.error("Error getting TOTP secret", e);
            }
            return Optional.empty();
        });
    }

    /**
     * Check if player has 2FA enabled
     */
    public CompletableFuture<Boolean> has2FAEnabled(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT totp_enabled FROM vouch_players WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("totp_enabled");
                    }
                }
                // No row: the player is not registered, so they genuinely have no 2FA.
                return false;

            } catch (SQLException e) {
                // FAIL CLOSED. Returning false here would be indistinguishable from
                // "this player has no 2FA", and callers would then authenticate on the
                // password alone - so any database hiccup (or an attacker exhausting the
                // connection pool) would be a 2FA bypass.
                LOGGER.error("Error checking 2FA status for {} - failing closed", uuid, e);
                throw new CompletionException(
                        new SQLException("Could not determine 2FA status for " + uuid
                                + "; refusing to assume 2FA is disabled", e));
            }
        });
    }

    /**
     * Disable 2FA for a player (removes secret and sets flag to false)
     */
    public CompletableFuture<Boolean> disable2FA(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE vouch_players SET totp_secret = NULL, totp_enabled = FALSE WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                int updated = stmt.executeUpdate();

                if (updated > 0) {
                    LOGGER.info("2FA disabled for player {}", uuid);
                    return true;
                }
                return false;

            } catch (SQLException e) {
                LOGGER.error("Error disabling 2FA", e);
                return false;
            }
        });
    }

    /**
     * Delete a player's registration (admin command)
     */
    public CompletableFuture<Boolean> unregisterPlayer(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM vouch_players WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    LOGGER.info("Player {} unregistered by admin", uuid);
                    return true;
                }
                return false;

            } catch (SQLException e) {
                LOGGER.error("Error unregistering player", e);
                return false;
            }
        });
    }

    // ==================== Session Persistence (Phase 4) ====================

    /**
     * Create a new persistent session for a player.
     * Replaces any existing session for the same UUID+IP combination.
     * 
     * @param uuid Player UUID
     * @param ip Player's IP address
     * @param tokenHash SHA-256 hash of the session token
     * @param expiresAt When the session expires
     */
    public CompletableFuture<Boolean> createSession(UUID uuid, String ip, String tokenHash, Instant expiresAt) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection()) {
                // First, delete any existing session for this UUID+IP
                try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM vouch_sessions WHERE uuid = ? AND ip_address = ?")) {
                    deleteStmt.setString(1, uuid.toString());
                    deleteStmt.setString(2, ip);
                    deleteStmt.executeUpdate();
                }

                // Then create the new session
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO vouch_sessions (uuid, ip_address, session_token, expires_at) VALUES (?, ?, ?, ?)")) {
                    insertStmt.setString(1, uuid.toString());
                    insertStmt.setString(2, ip);
                    insertStmt.setString(3, tokenHash);
                    insertStmt.setTimestamp(4, Timestamp.from(expiresAt));
                    insertStmt.executeUpdate();
                }

                LOGGER.debug("Session created for player {} from IP {}", uuid, ip);
                return true;

            } catch (SQLException e) {
                LOGGER.error("Error creating session for player {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * Validate if a player has a valid (non-expired) session from the given IP.
     * 
     * @param uuid Player UUID
     * @param ip Player's current IP address
     * @return true if a valid session exists, false otherwise
     */
    public CompletableFuture<Boolean> validateSession(UUID uuid, String ip) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT 1 FROM vouch_sessions WHERE uuid = ? AND ip_address = ? AND expires_at > ?")) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, ip);
                stmt.setTimestamp(3, Timestamp.from(Instant.now()));

                try (ResultSet rs = stmt.executeQuery()) {
                    boolean valid = rs.next();
                    if (valid) {
                        LOGGER.debug("Valid session found for player {} from IP {}", uuid, ip);
                    }
                    return valid;
                }

            } catch (SQLException e) {
                LOGGER.error("Error validating session for player {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * Validate if a player has ANY valid (non-expired) session (ignoring IP).
     * Used when session.bind_to_ip is false.
     * 
     * @param uuid Player UUID
     * @return true if a valid session exists for this UUID, false otherwise
     */
    public CompletableFuture<Boolean> hasAnyValidSession(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT 1 FROM vouch_sessions WHERE uuid = ? AND expires_at > ?")) {

                stmt.setString(1, uuid.toString());
                stmt.setTimestamp(2, Timestamp.from(Instant.now()));

                try (ResultSet rs = stmt.executeQuery()) {
                    boolean valid = rs.next();
                    if (valid) {
                        LOGGER.debug("Valid session found for player {} (IP not required)", uuid);
                    }
                    return valid;
                }

            } catch (SQLException e) {
                LOGGER.error("Error validating session for player {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * Delete a specific session for a player from a specific IP.
     * 
     * @param uuid Player UUID
     * @param ip IP address of the session to delete
     */
    public CompletableFuture<Boolean> deleteSession(UUID uuid, String ip) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM vouch_sessions WHERE uuid = ? AND ip_address = ?")) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, ip);
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    LOGGER.debug("Session deleted for player {} from IP {}", uuid, ip);
                    return true;
                }
                return false;

            } catch (SQLException e) {
                LOGGER.error("Error deleting session for player {}", uuid, e);
                return false;
            }
        });
    }

    /**
     * Delete ALL sessions for a player (used for full logout).
     * 
     * @param uuid Player UUID
     */
    public CompletableFuture<Integer> deleteAllSessions(UUID uuid) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM vouch_sessions WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    LOGGER.info("All sessions ({}) deleted for player {}", deleted, uuid);
                }
                return deleted;

            } catch (SQLException e) {
                LOGGER.error("Error deleting all sessions for player {}", uuid, e);
                return 0;
            }
        });
    }

    /**
     * Cleanup expired sessions from the database.
     * Should be called periodically to prevent table bloat.
     * 
     * @return Number of expired sessions deleted
     */
    public CompletableFuture<Integer> cleanupExpiredSessions() {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM vouch_sessions WHERE expires_at < ?")) {

                stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    LOGGER.info("Cleaned up {} expired sessions", deleted);
                }
                return deleted;

            } catch (SQLException e) {
                LOGGER.error("Error cleaning up expired sessions", e);
                return 0;
            }
        });
    }

    /**
     * Get the premium override status for a player username.
     * Returns a tri-state: NO_OVERRIDE (no DB row), MARKED_ONLINE, or FORCED_OFFLINE.
     */
    public CompletableFuture<PremiumOverrideStatus> getPremiumOverrideStatus(String username) {
        return async(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT forced_offline FROM vouch_premium_overrides WHERE username = ?")) {

                stmt.setString(1, username.toLowerCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("forced_offline")
                                ? PremiumOverrideStatus.FORCED_OFFLINE
                                : PremiumOverrideStatus.MARKED_ONLINE;
                    }
                }
                return PremiumOverrideStatus.NO_OVERRIDE;

            } catch (SQLException e) {
                LOGGER.error("Error checking premium override status for {}", username, e);
                return PremiumOverrideStatus.NO_OVERRIDE;
            }
        });
    }

    /**
     * Set or update the forced-offline status for a player username.
     */
    public CompletableFuture<Boolean> setForcedOffline(String username, boolean forced, String setBy) {
        return async(() -> {
            String sql = connectionFactory.getDatabaseType() == ConnectionFactory.DatabaseType.POSTGRESQL
                    ? """
                      INSERT INTO vouch_premium_overrides (username, forced_offline, set_by, set_at)
                      VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                      ON CONFLICT (username) DO UPDATE SET forced_offline = ?, set_by = ?, set_at = CURRENT_TIMESTAMP
                      """
                    : """
                      REPLACE INTO vouch_premium_overrides (username, forced_offline, set_by, set_at)
                      VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                      """;

            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, username.toLowerCase());
                stmt.setBoolean(2, forced);
                stmt.setString(3, setBy);

                if (connectionFactory.getDatabaseType() == ConnectionFactory.DatabaseType.POSTGRESQL) {
                    stmt.setBoolean(4, forced);
                    stmt.setString(5, setBy);
                }

                return stmt.executeUpdate() > 0;

            } catch (SQLException e) {
                LOGGER.error("Error setting forced-offline for {}", username, e);
                return false;
            }
        });
    }
}
