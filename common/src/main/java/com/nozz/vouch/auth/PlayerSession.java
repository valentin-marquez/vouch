package com.nozz.vouch.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a player's authentication session.
 * 
 * Tracks authentication state, timestamps, and session data
 * for both pending and authenticated players.
 */
public final class PlayerSession {
    private final UUID playerUuid;
    private final String playerName;
    private final String ipAddress;
    private final Instant createdAt;

    private Instant authenticatedAt;
    private boolean authenticated;
    private boolean has2FAEnabled;
    private boolean requires2FA;

    // 2FA state tracking
    private boolean awaiting2FA;           // Player passed password, needs 2FA code
    private String pending2FASecret;        // Secret during 2FA setup (not yet confirmed)
    private boolean is2FAOnlyRegistration;  // True if registering in 2FA-only mode (no password)

    // Login attempt tracking for rate limiting
    private int failedAttempts;
    private Instant lastAttemptAt;

    // State restoration
    private boolean originalAllowFlight;

    // Jail Position (for granular pre-auth)
    private double jailX;
    private double jailY;
    private double jailZ;
    private float jailYaw;
    private float jailPitch;
    private boolean jailPosSet = false;

    public PlayerSession(UUID playerUuid, String playerName, String ipAddress) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.ipAddress = ipAddress;
        this.createdAt = Instant.now();
        this.authenticated = false;
        this.has2FAEnabled = false;
        this.requires2FA = false;
        this.awaiting2FA = false;
        this.pending2FASecret = null;
        this.failedAttempts = 0;
    }

    public void markAuthenticated() {
        this.authenticated = true;
        this.authenticatedAt = Instant.now();
        this.failedAttempts = 0;
    }

    public void recordFailedAttempt() {
        this.failedAttempts++;
        this.lastAttemptAt = Instant.now();
    }

    /**
     * Check if player is rate-limited (too many failed attempts)
     * Default: 5 attempts, then 30 second cooldown
     */
    public boolean isRateLimited() {
        if (failedAttempts < 5) {
            return false;
        }

        if (lastAttemptAt == null) {
            return false;
        }

        // 30 second cooldown after 5 failed attempts
        return Instant.now().isBefore(lastAttemptAt.plusSeconds(30));
    }

    public int getSecondsUntilRetry() {
        if (!isRateLimited() || lastAttemptAt == null) {
            return 0;
        }
        long remaining = lastAttemptAt.plusSeconds(30).getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, (int) remaining);
    }

    // Getters
    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAuthenticatedAt() {
        return authenticatedAt;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public boolean has2FAEnabled() {
        return has2FAEnabled;
    }

    public void set2FAEnabled(boolean enabled) {
        this.has2FAEnabled = enabled;
    }

    public boolean requires2FA() {
        return requires2FA;
    }

    public void setRequires2FA(boolean requires) {
        this.requires2FA = requires;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setOriginalAllowFlight(boolean allow) {
        this.originalAllowFlight = allow;
    }

    public boolean getOriginalAllowFlight() {
        return originalAllowFlight;
    }

    // Jail Position Getters/Setters
    public void setJailPosition(double x, double y, double z, float yaw, float pitch) {
        this.jailX = x;
        this.jailY = y;
        this.jailZ = z;
        this.jailYaw = yaw;
        this.jailPitch = pitch;
        this.jailPosSet = true;
    }

    public boolean isJailPosSet() {
        return jailPosSet;
    }

    public double getJailX() { return jailX; }
    public double getJailY() { return jailY; }
    public double getJailZ() { return jailZ; }
    public float getJailYaw() { return jailYaw; }
    public float getJailPitch() { return jailPitch; }

    /**
     * Check if player is awaiting 2FA verification (passed password check)
     */
    public boolean isAwaiting2FA() {
        return awaiting2FA;
    }

    /**
     * Set whether player is awaiting 2FA verification
     */
    public void setAwaiting2FA(boolean awaiting) {
        this.awaiting2FA = awaiting;
    }

    /**
     * Get the pending 2FA secret (during setup, before confirmation)
     */
    public String getPending2FASecret() {
        return pending2FASecret;
    }

    /**
     * Set the pending 2FA secret for setup verification
     */
    public void setPending2FASecret(String secret) {
        this.pending2FASecret = secret;
    }

    /**
     * Clear the pending 2FA secret (after setup complete or cancelled)
     */
    public void clearPending2FASecret() {
        this.pending2FASecret = null;
        this.is2FAOnlyRegistration = false;
    }

    /**
     * Mark this session as a 2FA-only registration (no password required)
     */
    public void markAs2FAOnlyRegistration() {
        this.is2FAOnlyRegistration = true;
    }

    /**
     * Check if this is a 2FA-only registration in progress
     */
    public boolean is2FAOnlyRegistration() {
        return is2FAOnlyRegistration;
    }
}
