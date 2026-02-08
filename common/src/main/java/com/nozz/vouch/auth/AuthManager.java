package com.nozz.vouch.auth;

import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.util.QRMapRenderer;
import com.nozz.vouch.util.SessionTokenGenerator;
import com.nozz.vouch.util.UXManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player authentication state and sessions.
 * 
 * Tracks which players are authenticated and handles the pre-auth "jail" state.
 */
public final class AuthManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/AuthManager");
    private static AuthManager instance;
    private final Map<UUID, PlayerSession> pendingSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSession> activeSessions = new ConcurrentHashMap<>();

    private AuthManager() {
    }

    public static AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    /**
     * Check if a player is authenticated
     */
    public boolean isAuthenticated(ServerPlayerEntity player) {
        return isAuthenticated(player.getUuid());
    }

    public boolean isAuthenticated(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

    /**
     * Check if a player is in pre-auth jail (waiting to authenticate)
     */
    public boolean isPendingAuth(ServerPlayerEntity player) {
        return isPendingAuth(player.getUuid());
    }

    public boolean isPendingAuth(UUID uuid) {
        return pendingSessions.containsKey(uuid);
    }

    /**
     * Put a player in pre-auth jail.
     * Delegates to PreAuthManager for UX and effects.
     */
    public void addPendingPlayer(ServerPlayerEntity player, boolean isRegistered) {
        UUID uuid = player.getUuid();
        activeSessions.remove(uuid);

        String ip = getPlayerIP(player);
        PlayerSession session = new PlayerSession(uuid, player.getName().getString(), ip);
        
        session.setOriginalAllowFlight(player.getAbilities().allowFlying);
        
        pendingSessions.put(uuid, session);

        PreAuthManager.getInstance().startPreAuth(player, session, isRegistered);

        LOGGER.debug("Player {} added to pre-auth jail", player.getName().getString());
    }

    /**
     * Put a player in pre-auth jail (legacy method, defaults to unregistered)
     */
    public void addPendingPlayer(ServerPlayerEntity player) {
        addPendingPlayer(player, false);
    }

    /**
     * Authenticate a player and move them from pending to active
     */
    public void authenticatePlayer(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        PlayerSession session = pendingSessions.remove(uuid);

        if (session != null) {
            session.markAuthenticated();
            activeSessions.put(uuid, session);

            PreAuthManager.getInstance().endPreAuth(player);
            if (VouchConfigManager.getInstance().isSessionPersistenceEnabled()) {
                createPersistentSession(uuid, session.getIpAddress());
            }

            LOGGER.info("Player {} authenticated successfully", player.getName().getString());
        }
    }

    /**
     * Authenticate a player directly (for session restoration).
     * Used when a valid persistent session is found.
     *
     * Reuses a pending session when available to preserve original state
     * (e.g., originalAllowFlight, jail position). Ensures pre-auth effects
     * are cleared immediately.
     */
    public void authenticateFromSession(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        String ip = getPlayerIP(player);

        // Prefer reusing a pending session so original state (allowFlying, etc.) is preserved
        PlayerSession pending = pendingSessions.remove(uuid);
        if (pending != null) {
            pending.markAuthenticated();
            activeSessions.put(uuid, pending);

            // End any pre-auth UX/effects immediately
            PreAuthManager.getInstance().endPreAuth(player);

            if (VouchConfigManager.getInstance().isSessionPersistenceEnabled()) {
                createPersistentSession(uuid, ip);
            }

            LOGGER.info("Player {} authenticated via restored pending session", player.getName().getString());
            return;
        }

        // No pending session - create a new active session and defensively end pre-auth effects
        PlayerSession session = new PlayerSession(uuid, player.getName().getString(), ip);
        session.markAuthenticated();
        activeSessions.put(uuid, session);

        // Ensure any lingering pre-auth state is cleared even if we didn't have a pending session object
        PreAuthManager.getInstance().endPreAuth(player);

        if (VouchConfigManager.getInstance().isSessionPersistenceEnabled()) {
            createPersistentSession(uuid, ip);
        }

        LOGGER.info("Player {} authenticated via persistent session", player.getName().getString());
    }

    /**
     * Create a persistent session in the database
     */
    private void createPersistentSession(UUID uuid, String ip) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        String token = SessionTokenGenerator.generateToken();
        String tokenHash = SessionTokenGenerator.hashToken(token);
        Instant expiresAt = Instant.now().plusSeconds(config.getSessionDuration());

        DatabaseManager.getInstance().createSession(uuid, ip, tokenHash, expiresAt)
                .thenAccept(success -> {
                    if (success) {
                        LOGGER.debug("Persistent session created for {} (expires in {}s)", uuid, config.getSessionDuration());
                    }
                });
    }

    /**
     * Mark a player as requiring 2FA verification (password passed, awaiting code)
     * Keeps player in pending state but marks they need 2FA
     */
    public void require2FA(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        PlayerSession session = pendingSessions.get(uuid);
        
        if (session != null) {
            session.setAwaiting2FA(true);
            LOGGER.debug("Player {} requires 2FA verification", player.getName().getString());
        }
    }

    /**
     * Complete authentication after 2FA verification
     * Called when player passes both password and 2FA code
     */
    public void complete2FAAuthentication(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        PlayerSession session = pendingSessions.get(uuid);
        
        if (session != null && session.isAwaiting2FA()) {
            session.setAwaiting2FA(false);
            authenticatePlayer(player);
            LOGGER.info("Player {} completed 2FA authentication", player.getName().getString());
        }
    }

    /**
     * Remove a player from all tracking (on disconnect)
     * This overload takes the player object to properly cleanup QR maps.
     */
    public void removePlayer(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        
        QRMapRenderer.removeQRMap(player);
        
        pendingSessions.remove(uuid);
        activeSessions.remove(uuid);
        
        PreAuthManager.getInstance().onPlayerDisconnect(uuid);
        QRMapRenderer.onPlayerDisconnect(uuid);
        UXManager.getInstance().cleanupPlayer(uuid);
    }

    /**
     * Remove a player from all tracking by UUID only.
     * Use this when the player object is not available.
     * Note: QR map cleanup cannot be performed without the player object.
     */
    public void removePlayer(UUID uuid) {
        pendingSessions.remove(uuid);
        activeSessions.remove(uuid);

        PreAuthManager.getInstance().onPlayerDisconnect(uuid);
        QRMapRenderer.onPlayerDisconnect(uuid);
        UXManager.getInstance().cleanupPlayer(uuid);
    }

    /**
     * Get a player's session if it exists
     */
    public PlayerSession getSession(UUID uuid) {
        PlayerSession session = activeSessions.get(uuid);
        if (session == null) {
            session = pendingSessions.get(uuid);
        }
        return session;
    }

    /**
     * Check if a player has a valid persistent session (Phase 4).
     * 
     * Respects session.bind_to_ip config:
     * - If true: Validates session for specific UUID + IP combination
     * - If false: Validates session for UUID only (any IP allowed)
     * 
     * @param uuid Player UUID
     * @param ip Player's current IP address
     * @return CompletableFuture that resolves to true if session is valid
     */
    public CompletableFuture<Boolean> hasValidSession(UUID uuid, String ip) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        
        if (!config.isSessionPersistenceEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        
        // If bind_to_ip is disabled, use a wildcard approach
        // We still validate by IP since that's how sessions are stored,
        // but we could extend DatabaseManager to support IP-less validation
        if (!config.isSessionBindToIp()) {
            // For simplicity, when bind_to_ip is false, we validate any existing session for this UUID
            return DatabaseManager.getInstance().hasAnyValidSession(uuid);
        }
        
        return DatabaseManager.getInstance().validateSession(uuid, ip);
    }

    /**
     * Logout a player - removes in-memory session AND persistent session.
     * Use this for explicit logout (e.g., /logout command).
     * 
     * @param player The player to logout
     * @return CompletableFuture that completes when logout is done
     */
    public CompletableFuture<Void> logout(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        
        pendingSessions.remove(uuid);
        activeSessions.remove(uuid);

        return DatabaseManager.getInstance().deleteAllSessions(uuid)
                .thenAccept(deleted -> {
                    LOGGER.info("Player {} logged out, {} session(s) invalidated", 
                            player.getName().getString(), deleted);
                });
    }

    private String getPlayerIP(ServerPlayerEntity player) {
        try {
            var address = player.networkHandler.getConnectionAddress();
            if (address != null) {
                String ip = address.toString();
                if (ip.startsWith("/")) {
                    ip = ip.substring(1);
                }
                int colonIndex = ip.lastIndexOf(':');
                if (colonIndex > 0) {
                    ip = ip.substring(0, colonIndex);
                }
                return ip;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not get IP for player {}", player.getName().getString());
        }
        return "unknown";
    }

    public void shutdown() {
        pendingSessions.clear();
        activeSessions.clear();
        
        PreAuthManager.getInstance().shutdown();
        UXManager.getInstance().shutdown();
        
        LOGGER.debug("AuthManager shutdown complete");
    }
}
