package com.nozz.vouch.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Database manager for Vouch player data.
 * 
 * Handles storage and retrieval of:
 * - Player credentials (UUID, password hash)
 * - 2FA secrets
 * - Session data for persistence
 */
public final class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/DatabaseManager");

    private static DatabaseManager instance;
    private final ConnectionFactory connectionFactory;

    private DatabaseManager() {
        this.connectionFactory = ConnectionFactory.getInstance();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Initialize database schema
     */
    public void initializeSchema() throws SQLException {
        LOGGER.info("Initializing database schema...");

        ConnectionFactory.DatabaseType dbType = connectionFactory.getDatabaseType();
        
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {

            // Players table - compatible with all databases
            stmt.execute("""
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

            // Sessions table - use database-specific syntax for auto-increment
            String sessionsTableSql = switch (dbType) {
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
                case SQLITE -> """
                    CREATE TABLE IF NOT EXISTS vouch_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid VARCHAR(36) NOT NULL,
                        ip_address VARCHAR(45) NOT NULL,
                        session_token VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NOT NULL,
                        FOREIGN KEY (uuid) REFERENCES vouch_players(uuid) ON DELETE CASCADE
                    )
                    """;
                default -> """
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
            stmt.execute(sessionsTableSql);

            // Index for session lookups
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_sessions_uuid_ip 
                    ON vouch_sessions(uuid, ip_address)
                    """);

            LOGGER.info("Database schema initialized successfully");
        }
    }

    /**
     * Check if a player is registered
     */
    public CompletableFuture<Boolean> isRegistered(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
     * Register a new player with 2FA directly (for 2FA-only mode).
     * Creates the player record with the TOTP secret enabled and no password.
     */
    public CompletableFuture<Boolean> registerPlayerWith2FA(UUID uuid, String username, String totpSecret) {
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
     * Update last login info
     */
    public CompletableFuture<Void> updateLastLogin(UUID uuid, String ip) {
        return CompletableFuture.runAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = connectionFactory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT totp_enabled FROM vouch_players WHERE uuid = ?")) {

                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("totp_enabled");
                    }
                }

            } catch (SQLException e) {
                LOGGER.error("Error checking 2FA status", e);
            }
            return false;
        });
    }

    /**
     * Disable 2FA for a player (removes secret and sets flag to false)
     */
    public CompletableFuture<Boolean> disable2FA(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
        return CompletableFuture.supplyAsync(() -> {
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
}
