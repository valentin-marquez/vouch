package com.nozz.vouch.auth;

import com.nozz.vouch.VouchMod;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.util.Messages;
import com.nozz.vouch.util.QRMapRenderer;
import com.nozz.vouch.util.UXManager;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.nozz.vouch.util.PacketHelper.sendPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Manages pre-authentication state for players.
 * 
 * Handles:
 * - Countdown timer with visual feedback (BossBar + ActionBar)
 * - Pre-auth effects (blindness, slowness, hiding)
 * - Position freezing
 * - UX feedback integration
 */
public final class PreAuthManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/PreAuth");

    private static PreAuthManager instance;

    private final Map<UUID, ScheduledFuture<?>> countdownTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> remainingSeconds = new ConcurrentHashMap<>();
    
    private final ScheduledThreadPoolExecutor scheduler;

    private PreAuthManager() {
        this.scheduler = new ScheduledThreadPoolExecutor(1);
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public static PreAuthManager getInstance() {
        if (instance == null) {
            instance = new PreAuthManager();
        }
        return instance;
    }

    /**
     * Start pre-auth state for a player.
     * Applies effects and starts countdown.
     */
    @SuppressWarnings("unused")
    public void startPreAuth(ServerPlayerEntity player, PlayerSession session, boolean isRegistered) {
        UUID uuid = player.getUuid();
        VouchConfigManager config = VouchConfigManager.getInstance();
        UXManager ux = UXManager.getInstance();

        LOGGER.debug("Starting pre-auth for player {}", player.getName().getString());

        // Store original position for freeze
        if (config.freezePosition()) {
            session.setJailPosition(
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch()
            );
        }

        applyPreAuthEffects(player);

        if (config.hideFromTabList()) {
            hideFromTabList(player);
        }

        if (config.hideFromOthers()) {
            player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.INVISIBILITY, -1, 0, false, false, false
            ));
        }

        if (isRegistered) {
            ux.sendWelcomeTitleRegistered(player);
            ux.sendWelcomeMessageRegistered(player);
        } else {
            ux.sendWelcomeTitleUnregistered(player);
            ux.sendWelcomeMessageUnregistered(player);
        }

        int totalSeconds = config.getLoginTimeout();
        startCountdown(player, totalSeconds);

        LOGGER.debug("Pre-auth started for {} (timeout: {}s)", player.getName().getString(), totalSeconds);
    }

    /**
     * Apply pre-auth status effects
     */
    private void applyPreAuthEffects(ServerPlayerEntity player) {
        VouchConfigManager config = VouchConfigManager.getInstance();

        // Blindness
        if (config.getBlindnessLevel() > 0) {
            player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.BLINDNESS, -1, config.getBlindnessLevel() - 1, 
                false, false, false
            ));
        }

        // Slowness
        if (config.getSlownessLevel() > 0) {
            player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOWNESS, -1, config.getSlownessLevel() - 1, 
                false, false, false
            ));
        }

        // Prevent fly kick by allowing flight temporarily
        PlayerSession session = AuthManager.getInstance().getSession(player.getUuid());
        if (session != null) {
            session.setOriginalAllowFlight(player.getAbilities().allowFlying);
        }
        player.getAbilities().allowFlying = true;
        player.sendAbilitiesUpdate();
    }

    /**
     * Hide player from tablist
     */
    private void hideFromTabList(ServerPlayerEntity player) {
        try {
            var server = player.getServer();
            if (server != null) {
                var packet = new PlayerRemoveS2CPacket(List.of(player.getUuid()));
                // Send to all other players
                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                    if (!other.getUuid().equals(player.getUuid())) {
                        sendPacket(other, packet);
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.warn("Failed to hide player from tab list: {}", e.getMessage());
        }
    }

    /**
     * Show player in tablist
     */
    private void showInTabList(ServerPlayerEntity player) {
        try {
            var server = player.getServer();
            if (server != null) {
                var packet = new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, player);
                server.getPlayerManager().sendToAll(packet);
            }
        } catch (Throwable e) {
            LOGGER.warn("Failed to show player in tab list: {}", e.getMessage());
        }
    }

    /**
     * Start the countdown timer for a player
     */
    private void startCountdown(ServerPlayerEntity player, int totalSeconds) {
        UUID uuid = player.getUuid();
        
        remainingSeconds.put(uuid, totalSeconds);

        // Show initial BossBar
        UXManager.getInstance().showCountdownBossBar(player, totalSeconds);

        // Schedule countdown tick every second
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            VouchMod.getInstance().runOnMainThread(() -> tickCountdown(player, totalSeconds));
        }, 1, 1, TimeUnit.SECONDS);

        countdownTasks.put(uuid, task);
    }

    /**
     * Tick the countdown for a player (called every second)
     */
    private void tickCountdown(ServerPlayerEntity player, int totalSeconds) {
        UUID uuid = player.getUuid();
        

        if (!player.isAlive() || !AuthManager.getInstance().isPendingAuth(uuid)) {
            cancelCountdown(uuid);
            return;
        }

        Integer remaining = remainingSeconds.get(uuid);
        if (remaining == null) return;

        remaining--;
        remainingSeconds.put(uuid, remaining);

        UXManager ux = UXManager.getInstance();
        PlayerSession session = AuthManager.getInstance().getSession(uuid);


        ux.updateCountdownBossBar(player, remaining, totalSeconds);
 
        if (session != null && session.isAwaiting2FA()) {
            ux.sendAwaiting2FAActionBar(player);
        } else if (session != null && session.isRateLimited()) {
            ux.sendRateLimitedActionBar(player, session.getSecondsUntilRetry());
        } else {
            ux.sendPreAuthActionBar(player, remaining);
        }

        // Check timeout
        if (remaining <= 0) {
            onTimeout(player);
        }
    }

    /**
     * Called when countdown reaches zero
     */
    private void onTimeout(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        
        cancelCountdown(uuid);
        UXManager.getInstance().playAuthTimeoutSound(player);

        // Remove any QR map from inventory before disconnecting
        QRMapRenderer.removeQRMap(player);

        // Disconnect player
        player.networkHandler.disconnect(Messages.authTimeout());
        LOGGER.info("Player {} kicked for auth timeout", player.getName().getString());
    }

    /**
     * Enforce position freeze for a player
     */
    public void enforcePositionFreeze(ServerPlayerEntity player) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.freezePosition()) return;

        PlayerSession session = AuthManager.getInstance().getSession(player.getUuid());
        if (session == null || !session.isJailPosSet()) return;

        // Teleport back to jail position if moved
        double dx = player.getX() - session.getJailX();
        double dy = player.getY() - session.getJailY();
        double dz = player.getZ() - session.getJailZ();
        
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > 0.1) { // Allow tiny movements
            // Use requestTeleport for smooth teleportation
            player.requestTeleport(
                session.getJailX(), 
                session.getJailY(), 
                session.getJailZ()
            );
            
            // If camera freeze is enabled, also reset rotation
            if (config.freezeCamera()) {
                player.setYaw(session.getJailYaw());
                player.setPitch(session.getJailPitch());
            }
        }
    }

    /**
     * End pre-auth state for a player (on successful auth)
     */
    public void endPreAuth(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        VouchConfigManager config = VouchConfigManager.getInstance();

        LOGGER.debug("Ending pre-auth for player {}", player.getName().getString());

        cancelCountdown(uuid);

        QRMapRenderer.removeQRMap(player);

        player.removeStatusEffect(StatusEffects.BLINDNESS);
        player.removeStatusEffect(StatusEffects.SLOWNESS);
        player.removeStatusEffect(StatusEffects.INVISIBILITY);

        PlayerSession session = AuthManager.getInstance().getSession(uuid);
        if (session != null) {
            player.getAbilities().allowFlying = session.getOriginalAllowFlight();
            player.sendAbilitiesUpdate();
        } else {
            // Defensive: if no session object is available (unexpected), ensure flight is disabled
            player.getAbilities().allowFlying = false;
            player.sendAbilitiesUpdate();
            LOGGER.warn("endPreAuth called but no session found for {} - resetting flight state", player.getName().getString());
        }

        if (config.hideFromTabList()) {
            showInTabList(player);
        }

        UXManager.getInstance().clearTitle(player);
    }

    /**
     * Cancel countdown for a player
     */
    private void cancelCountdown(UUID uuid) {
        ScheduledFuture<?> task = countdownTasks.remove(uuid);
        if (task != null) {
            task.cancel(false);
        }
        remainingSeconds.remove(uuid);
        UXManager.getInstance().removeCountdownBossBar(uuid);
    }

    /**
     * Clean up when player disconnects
     */
    public void onPlayerDisconnect(UUID uuid) {
        cancelCountdown(uuid);
        UXManager.getInstance().cleanupPlayer(uuid);
    }

    /**
     * Get remaining seconds for a player's countdown
     */
    public int getRemainingSeconds(UUID uuid) {
        return remainingSeconds.getOrDefault(uuid, 0);
    }

    /**
     * Check if a player has an active countdown
     */
    public boolean hasActiveCountdown(UUID uuid) {
        return countdownTasks.containsKey(uuid) && remainingSeconds.containsKey(uuid);
    }

    public void shutdown() {
        // Cancel all tasks
        for (ScheduledFuture<?> task : countdownTasks.values()) {
            task.cancel(false);
        }
        countdownTasks.clear();
        remainingSeconds.clear();

        // Shutdown scheduler
        scheduler.shutdownNow();
        
        LOGGER.debug("PreAuthManager shutdown complete");
    }
}
