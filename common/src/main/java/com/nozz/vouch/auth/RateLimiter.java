package com.nozz.vouch.auth;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nozz.vouch.config.VouchConfigManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Handles rate limiting for login/registration attempts.
 * Uses Guava (bundled with MC) for high-performance in-memory tracking.
 * 
 * Configuration values from vouch.toml:
 * - auth.max_attempts: Number of attempts before lockout
 * - auth.lockout_duration: Lockout duration in seconds
 */
public class RateLimiter {
    private static RateLimiter instance;
    private final Cache<String, AttemptState> ipTracker;

    private RateLimiter() {
        this.ipTracker = CacheBuilder.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build();
    }

    public static synchronized RateLimiter getInstance() {
        if (instance == null) {
            instance = new RateLimiter();
        }
        return instance;
    }

    /**
     * Get configuration values
     */
    private int getMaxAttempts() {
        try {
            return VouchConfigManager.getInstance().getMaxLoginAttempts();
        } catch (Exception e) {
            return 5;
        }
    }

    private int getLockoutDuration() {
        try {
            return VouchConfigManager.getInstance().getLockoutDuration();
        } catch (Exception e) {
            return 300; 
        }
    }

    /**
     * Check if an IP is currently blocked.
     * @return Duration remaining if blocked, or Duration.ZERO if allowed.
     */
    public Duration getBlockRemaining(String ip) {
        AttemptState state = ipTracker.getIfPresent(ip);
        if (state == null) {
            return Duration.ZERO;
        }

        if (state.blockedUntil != null && Instant.now().isBefore(state.blockedUntil)) {
             return Duration.between(Instant.now(), state.blockedUntil);
        }
        
        return Duration.ZERO;
    }

    /**
     * Record a failed attempt for an IP.
     * Applies blocking rules based on configuration.
     */
    public void recordFailure(String ip) {
        AttemptState state = ipTracker.getIfPresent(ip);
        if (state == null) {
            state = new AttemptState();
            ipTracker.put(ip, state);
        }
        
        state.failures++;
        state.lastAttempt = Instant.now();

        int maxAttempts = getMaxAttempts();
        int lockoutSeconds = getLockoutDuration();

        // Apply progressive blocking based on config
        if (state.failures >= maxAttempts * 2) {
            // Double the attempts: Long lockout (1 hour)
            state.blockedUntil = Instant.now().plus(Duration.ofHours(1));
        } else if (state.failures >= maxAttempts) {
            // At max attempts: Standard lockout from config
            state.blockedUntil = Instant.now().plus(Duration.ofSeconds(lockoutSeconds));
        } else if (state.failures >= Math.max(3, maxAttempts / 2)) {
            // Half of max attempts: Short warning lockout (30 seconds)
            state.blockedUntil = Instant.now().plus(Duration.ofSeconds(30));
        }
        // Below threshold: No block yet
    }

    /**
     * Clear failures on successful login.
     */
    public void recordSuccess(String ip) {
        ipTracker.invalidate(ip);
    }

    private static class AttemptState {
        int failures = 0;
        @SuppressWarnings("unused")
        Instant lastAttempt = Instant.now();
        Instant blockedUntil = null;
    }
}
