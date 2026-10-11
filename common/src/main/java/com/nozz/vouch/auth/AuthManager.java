package com.nozz.vouch.auth;

import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.util.PlayerIps;
import com.nozz.vouch.util.QRMapRenderer;
import com.nozz.vouch.util.SessionTokenGenerator;
import com.nozz.vouch.util.UXManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

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
    public boolean isAuthenticated(ServerPlayer player) {
        return isAuthenticated(player.getUUID());
    }

    public boolean isAuthenticated(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

    /**
     * Check if a player is in pre-auth jail (waiting to authenticate)
     */
    public boolean isPendingAuth(ServerPlayer player) {
        return isPendingAuth(player.getUUID());
    }

    public boolean isPendingAuth(UUID uuid) {
        return pendingSessions.containsKey(uuid);
    }

    /**
     * Put a player in pre-auth jail.
     * Delegates to PreAuthManager for UX and effects.
     */
    public void addPendingPlayer(ServerPlayer player, boolean isRegistered) {
        UUID uuid = player.getUUID();
        activeSessions.remove(uuid);

        String ip = PlayerIps.of(player).orElse(null);
        PlayerSession session = new PlayerSession(uuid, player.getName().getString(), ip);
        
        session.setOriginalAllowFlight(player.getAbilities().mayfly);
        
        pendingSessions.put(uuid, session);

        PreAuthManager.getInstance().startPreAuth(player, session, isRegistered);

        LOGGER.debug("Player {} added to pre-auth jail", player.getName().getString());
    }

    /**
     * Put a player in pre-auth jail (legacy method, defaults to unregistered)
     */
    public void addPendingPlayer(ServerPlayer player) {
        addPendingPlayer(player, false);
    }

    /**
     * Authenticate a player and move them from pending to active
     */
    public void authenticatePlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
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
     */
    public void authenticateFromSession(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String ip = PlayerIps.of(player).orElse(null);

        PlayerSession session = new PlayerSession(uuid, player.getName().getString(), ip);
        session.markAuthenticated();
        activeSessions.put(uuid, session);

        LOGGER.info("Player {} authenticated via persistent session", player.getName().getString());
    }

    /**
     * Authenticate a player directly as a verified premium account.
     * Creates a persistent session if enabled.
     */
    public void authenticateAsPremium(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String ip = PlayerIps.of(player).orElse(null);

        PlayerSession session = new PlayerSession(uuid, player.getName().getString(), ip);
        session.setPremiumVerified(true);
        session.markAuthenticated();
        activeSessions.put(uuid, session);

        // Deliberately no persistent session: this player proved ownership of
        // the account cryptographically during login and will do so again next
        // time. Storing address-trust evidence for them would only add a way in
        // for whoever shares their address.

        LOGGER.info("Player {} authenticated via premium auto-login", player.getName().getString());
    }

    /**
     * Put a premium-verified player in pre-auth jail ONLY for 2FA verification.
     * Password check is bypassed; only 2FA code is required.
     */
    public void addPendingPremiumFor2FA(ServerPlayer player) {
        UUID uuid = player.getUUID();
        activeSessions.remove(uuid);

        String ip = PlayerIps.of(player).orElse(null);
        PlayerSession session = new PlayerSession(uuid, player.getName().getString(), ip);
        session.setPremiumVerified(true);
        session.setAwaiting2FA(true);
        session.setOriginalAllowFlight(player.getAbilities().mayfly);

        pendingSessions.put(uuid, session);

        PreAuthManager.getInstance().startPreAuthPremium2FA(player, session);

        LOGGER.debug("Premium player {} added to pre-auth jail for 2FA verification", player.getName().getString());
    }

    /**
     * A restored session got the player this far, but the account has a second
     * factor and a session is not one. Jails them owing only the code.
     */
    public void addPendingForSession2FA(ServerPlayer player) {
        UUID uuid = player.getUUID();
        activeSessions.remove(uuid);

        PlayerSession session = new PlayerSession(uuid, player.getName().getString(),
                PlayerIps.of(player).orElse(null));
        session.setAwaiting2FA(true);
        session.setOriginalAllowFlight(player.getAbilities().mayfly);

        pendingSessions.put(uuid, session);

        PreAuthManager.getInstance().startPreAuthPremium2FA(player, session);

        LOGGER.debug("Player {} restored a session and still owes a 2FA code",
                player.getName().getString());
    }

    /**
     * Create a persistent session in the database
     */
    private void createPersistentSession(UUID uuid, String ip) {
        // The address is the whole of what a session later proves. With no
        // address there is nothing to bind to and nothing to check on return,
        // so no session is worth storing.
        if (ip == null) {
            LOGGER.debug("No resolvable address for {}; not storing a session", uuid);
            return;
        }
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
    public void require2FA(ServerPlayer player) {
        UUID uuid = player.getUUID();
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
    public void complete2FAAuthentication(ServerPlayer player) {
        UUID uuid = player.getUUID();
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
    public void removePlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        
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
     * A session is always bound to the address it was created from. The address
     * is the only evidence a stored session carries: on an offline-mode server
     * the UUID is derived from the username, so it proves nothing on its own.
     * The former session.bind_to_ip = false mode, which checked the UUID alone,
     * therefore authenticated anyone who knew a username, and is gone.
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
        
        return DatabaseManager.getInstance().validateSession(uuid, ip);
    }

    /**
     * Logout a player - removes in-memory session AND persistent session.
     * Use this for explicit logout (e.g., /logout command).
     * 
     * @param player The player to logout
     * @return CompletableFuture that completes when logout is done
     */
    public CompletableFuture<Void> logout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        
        pendingSessions.remove(uuid);
        activeSessions.remove(uuid);

        return DatabaseManager.getInstance().deleteAllSessions(uuid)
                .thenAccept(deleted -> {
                    LOGGER.info("Player {} logged out, {} session(s) invalidated", 
                            player.getName().getString(), deleted);
                });
    }


    public void shutdown() {
        pendingSessions.clear();
        activeSessions.clear();
        
        PreAuthManager.getInstance().shutdown();
        UXManager.getInstance().shutdown();
        
        LOGGER.debug("AuthManager shutdown complete");
    }
}
