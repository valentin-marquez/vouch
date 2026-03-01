package com.nozz.vouch.util;

import com.nozz.vouch.VouchMod;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies whether a player has a valid Mojang/Microsoft premium account.
 * 
 * Works by checking the Mojang API for the player's username and comparing
 * the returned UUID against the player's actual game UUID. In offline-mode
 * servers, cracked clients generate UUIDs from "OfflinePlayer:" + name, 
 * which differs from the Mojang-assigned UUID. If they match, the player
 * is confirmed premium.
 * 
 * Results are cached in memory with a configurable TTL to avoid API spam.
 */
public final class PremiumVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/PremiumVerifier");

    private static final String MOJANG_API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    private static PremiumVerifier instance;

    private final HttpClient httpClient;
    private final Map<UUID, CachedResult> cache = new ConcurrentHashMap<>();

    private PremiumVerifier() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    public static PremiumVerifier getInstance() {
        if (instance == null) {
            instance = new PremiumVerifier();
        }
        return instance;
    }

    /**
     * Check if a player has a valid premium/online Mojang account.
     * 
     * Calls the Mojang API asynchronously and compares UUIDs.
     * On any error (network, API rate limit, etc.), returns false
     * so the player falls back to normal authentication.
     * 
     * @param player The server player to verify
     * @return CompletableFuture resolving to true if the player is premium
     */
    public CompletableFuture<Boolean> isPremiumPlayer(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        String username = player.getName().getString();

        // Check cache first
        CachedResult cached = cache.get(playerUuid);
        if (cached != null && !cached.isExpired()) {
            LOGGER.debug("Premium check for {} (cached): {}", username, cached.isPremium);
            return CompletableFuture.completedFuture(cached.isPremium);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean isPremium = verifyPremium(username, playerUuid);
                cache.put(playerUuid, new CachedResult(isPremium));
                LOGGER.debug("Premium check for {}: {}", username, isPremium);
                return isPremium;
            } catch (Exception e) {
                LOGGER.warn("Failed to verify premium status for {}: {}", username, e.getMessage());
                return false;
            }
        }, VouchMod.getInstance().getAsyncExecutor());
    }

    /**
     * Verify a player's premium status by calling the Mojang API.
     * 
     * @param username The player's username
     * @param playerUuid The player's current UUID (from the game session)
     * @return true if the Mojang API returns a UUID matching the player's UUID
     */
    private boolean verifyPremium(String username, UUID playerUuid) throws Exception {
        // Validate username before making API call (Minecraft usernames: 3-16 chars, alphanumeric + underscore)
        if (!username.matches("[a-zA-Z0-9_]{3,16}")) {
            LOGGER.debug("Invalid username format for premium check: {}", username);
            return false;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MOJANG_API_URL + username))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String body = response.body();
            UUID mojangUuid = extractUuid(body);

            if (mojangUuid != null) {
                boolean match = mojangUuid.equals(playerUuid);
                if (!match) {
                    LOGGER.debug("UUID mismatch for {}: mojang={}, player={}", 
                            username, mojangUuid, playerUuid);
                }
                return match;
            }
        } else if (response.statusCode() == 404) {
            // Username doesn't exist as a premium account
            LOGGER.debug("Username {} not found in Mojang API (offline/cracked account)", username);
        } else if (response.statusCode() == 429) {
            LOGGER.warn("Mojang API rate limited during premium check for {}", username);
        } else {
            LOGGER.warn("Unexpected Mojang API response for {}: HTTP {}", username, response.statusCode());
        }

        return false;
    }

    /**
     * Extract UUID from Mojang API JSON response.
     * The API returns UUIDs without dashes (32 hex chars).
     * 
     * @param json The raw JSON response body
     * @return The parsed UUID, or null if parsing fails
     */
    private UUID extractUuid(String json) {
        Matcher matcher = UUID_PATTERN.matcher(json);
        if (matcher.find()) {
            String hex = matcher.group(1);
            try {
                // Insert dashes into the 32-char hex string to form a standard UUID
                String formatted = hex.substring(0, 8) + "-" +
                        hex.substring(8, 12) + "-" +
                        hex.substring(12, 16) + "-" +
                        hex.substring(16, 20) + "-" +
                        hex.substring(20);
                return UUID.fromString(formatted);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Failed to parse UUID from Mojang API: {}", hex);
            }
        }
        return null;
    }

    /**
     * Clear the cache for a specific player (e.g., on disconnect).
     */
    public void invalidateCache(UUID uuid) {
        cache.remove(uuid);
    }

    /**
     * Clear all cached results.
     */
    public void clearCache() {
        cache.clear();
    }

    private static final class CachedResult {
        final boolean isPremium;
        final Instant cachedAt;

        CachedResult(boolean isPremium) {
            this.isPremium = isPremium;
            this.cachedAt = Instant.now();
        }

        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(CACHE_TTL));
        }
    }
}
