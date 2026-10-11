package com.nozz.vouch.util;

import com.mojang.authlib.GameProfile;
import com.nozz.vouch.config.VouchConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerPlayer;

/**
 * Talks to the Mojang APIs on behalf of the mixed online/offline login flow.
 *
 * <p>Two questions are asked here, and they are not the same question:
 * <ul>
 *   <li><b>Does this username belong to a premium account?</b> — answered by
 *       {@code api.mojang.com}, used before the handshake to decide whether to
 *       send an encryption challenge at all.</li>
 *   <li><b>Did this client actually authenticate with Mojang for our server
 *       id?</b> — answered by the session server's {@code hasJoined}, and it is
 *       the only one of the two that proves account ownership.</li>
 * </ul>
 *
 * <p>Every answer is a {@link PremiumLookup}, which distinguishes "no" from
 * "we could not find out". Collapsing those two is a security bug: a Mojang
 * outage answered as "not premium" lets a premium player in under an offline
 * UUID, losing their world data and leaving their username claimable by a
 * cracked client. Only definitive answers are ever cached.
 *
 * <p>All HTTP runs on a dedicated executor. It must never share the mod's
 * general async pool, which also runs Argon2 hashing: a login would otherwise
 * queue behind password hashes, and the netty event loops behind that.
 */
public final class PremiumVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/PremiumVerifier");

    private static final String MOJANG_API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_SERVER_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Hard ceiling on how long a caller can be left waiting, covering the parts
     * {@link HttpClient} timeouts do not — DNS resolution above all. Vanilla
     * disconnects a login after 600 ticks (30s), so this must stay well under
     * that to leave room for the rest of the handshake.
     */
    public static final Duration LOOKUP_DEADLINE = Duration.ofSeconds(8);
    public static final Duration SESSION_DEADLINE = Duration.ofSeconds(10);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Both caches are keyed by attacker-influenced data — a username straight
     * off the wire in one case — so they are bounded as well as expiring.
     */
    private static final int MAX_CACHE_ENTRIES = 1024;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-zA-Z0-9_]{3,16}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    private static PremiumVerifier instance;

    private final HttpClient httpClient;
    private final ExecutorService httpExecutor;
    private final BoundedTtlCache<UUID, Boolean> cache;
    private final BoundedTtlCache<String, PremiumLookup> usernameCache;

    private PremiumVerifier() {
        this.httpExecutor = Executors.newFixedThreadPool(4, new NamedDaemonThreadFactory("Vouch Mojang HTTP"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .executor(httpExecutor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.cache = new BoundedTtlCache<>(MAX_CACHE_ENTRIES, CACHE_TTL, System::currentTimeMillis);
        this.usernameCache = new BoundedTtlCache<>(MAX_CACHE_ENTRIES, CACHE_TTL, System::currentTimeMillis);
    }

    public static synchronized PremiumVerifier getInstance() {
        if (instance == null) {
            instance = new PremiumVerifier();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // HTTP status policy — pure functions, unit tested
    // ------------------------------------------------------------------

    /**
     * What a Mojang response means for a player's premium status.
     */
    public enum PremiumLookup {
        /** Mojang gave a definitive yes. */
        PREMIUM,
        /** Mojang gave a definitive no: no such account, or no such session. */
        NOT_PREMIUM,
        /** Mojang did not answer, or answered something we cannot interpret. */
        UNAVAILABLE;

        /**
         * An outage-derived answer must never be remembered — that is how a
         * momentary blip turns into five minutes of downgraded logins.
         */
        public boolean isCacheable() {
            return this != UNAVAILABLE;
        }
    }

    /**
     * Classify a response from the profile endpoint
     * ({@code api.mojang.com/users/profiles/minecraft/<name>}).
     *
     * <p>Only 404 and 204 are evidence that the username is not premium.
     * A 400 means we sent a bad request, a 429 means we are rate limited, a
     * 5xx means Mojang is unwell — none of those say anything about the player.
     */
    public static PremiumLookup classifyProfileStatus(int statusCode) {
        return switch (statusCode) {
            case 200 -> PremiumLookup.PREMIUM;
            case 204, 404 -> PremiumLookup.NOT_PREMIUM;
            default -> PremiumLookup.UNAVAILABLE;
        };
    }

    /**
     * Classify a response from the session server's {@code hasJoined}.
     *
     * <p>204 (and 404, defensively) is the documented "this client did not
     * authenticate for that server id" answer and is a genuine rejection.
     * Everything else is an outage and must fail closed.
     */
    public static PremiumLookup classifySessionStatus(int statusCode) {
        return switch (statusCode) {
            case 200 -> PremiumLookup.PREMIUM;
            case 204, 404 -> PremiumLookup.NOT_PREMIUM;
            default -> PremiumLookup.UNAVAILABLE;
        };
    }

    // ------------------------------------------------------------------
    // Username lookup (pre-handshake)
    // ------------------------------------------------------------------

    /**
     * Ask whether a username belongs to a premium account.
     *
     * <p>Never blocks, never throws: failures and timeouts arrive as
     * {@link PremiumLookup#UNAVAILABLE} so the caller can decide how to fail.
     * Callers on an offline-mode server must fail <em>closed</em> — falling
     * back to an offline profile on an unknown answer is what lets a Mojang
     * outage hand a premium name to a cracked client.
     */
    public CompletableFuture<PremiumLookup> lookupPremiumUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            // Not a name Mojang could ever have issued. This is a definitive no,
            // and cheap enough that it never reaches the network.
            return CompletableFuture.completedFuture(PremiumLookup.NOT_PREMIUM);
        }

        String normalizedName = username.toLowerCase(Locale.ROOT);
        Optional<PremiumLookup> cached = usernameCache.get(normalizedName);
        if (cached.isPresent()) {
            LOGGER.debug("Premium username check for {} (cached): {}", username, cached.get());
            return CompletableFuture.completedFuture(cached.get());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MOJANG_API_URL + encode(username)))
                .timeout(HTTP_REQUEST_TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    PremiumLookup outcome = classifyProfileStatus(response.statusCode());
                    if (outcome == PremiumLookup.UNAVAILABLE) {
                        LOGGER.warn("Mojang profile lookup for {} returned HTTP {} — treating as unavailable",
                                username, response.statusCode());
                    }
                    if (outcome.isCacheable()) {
                        usernameCache.put(normalizedName, outcome);
                    }
                    LOGGER.debug("Premium username check for {}: {}", username, outcome);
                    return outcome;
                })
                .orTimeout(LOOKUP_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {
                    LOGGER.warn("Mojang profile lookup for {} failed: {}", username, describe(throwable));
                    return PremiumLookup.UNAVAILABLE;
                });
    }

    /**
     * Backwards-compatible boolean view of {@link #lookupPremiumUsername}.
     *
     * <p>Retained for {@code /vouch} admin commands, which only want to show
     * an operator whether a name looks premium. Security decisions must use
     * {@link #lookupPremiumUsername} instead, because this collapses
     * {@code UNAVAILABLE} back into {@code false}.
     */
    public CompletableFuture<Boolean> usernameExistsInMojangAPI(String username) {
        return lookupPremiumUsername(username).thenApply(outcome -> outcome == PremiumLookup.PREMIUM);
    }

    // ------------------------------------------------------------------
    // Session verification (post-handshake)
    // ------------------------------------------------------------------

    /**
     * The outcome of a {@code hasJoined} call.
     *
     * @param outcome what Mojang said
     * @param profile the authenticated profile, present only when {@code outcome} is
     *                {@link PremiumLookup#PREMIUM}
     */
    public record SessionResult(PremiumLookup outcome, Optional<GameProfile> profile) {
        public static SessionResult verified(GameProfile profile) {
            return new SessionResult(PremiumLookup.PREMIUM, Optional.of(profile));
        }

        public static SessionResult rejected() {
            return new SessionResult(PremiumLookup.NOT_PREMIUM, Optional.empty());
        }

        public static SessionResult unavailable() {
            return new SessionResult(PremiumLookup.UNAVAILABLE, Optional.empty());
        }
    }

    /**
     * Verify that a client actually authenticated with Mojang for the server id
     * we challenged it with.
     *
     * <p>Fully asynchronous and bounded by {@link #SESSION_DEADLINE}. It must
     * never be joined on: the caller is a netty event loop shared by every
     * connection on the server, and blocking it stalls the players already
     * online, not just the one logging in.
     */
    public CompletableFuture<SessionResult> verifySession(String username, String serverHash) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return CompletableFuture.completedFuture(SessionResult.rejected());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SESSION_SERVER_URL + "?username=" + encode(username)
                        + "&serverId=" + encode(serverHash)))
                .timeout(HTTP_REQUEST_TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> toSessionResult(username, response.statusCode(), response.body()))
                .orTimeout(SESSION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {
                    LOGGER.warn("Session verification for {} could not be completed: {}",
                            username, describe(throwable));
                    return SessionResult.unavailable();
                });
    }

    private SessionResult toSessionResult(String username, int statusCode, String body) {
        PremiumLookup outcome = classifySessionStatus(statusCode);

        if (outcome == PremiumLookup.NOT_PREMIUM) {
            LOGGER.debug("Session verification rejected for {} (not authenticated with Mojang)", username);
            return SessionResult.rejected();
        }

        if (outcome == PremiumLookup.UNAVAILABLE) {
            LOGGER.warn("Session server returned HTTP {} for {} — treating as unavailable", statusCode, username);
            return SessionResult.unavailable();
        }

        boolean fetchSkins;
        try {
            fetchSkins = VouchConfigManager.getInstance().isPremiumFetchSkins();
        } catch (RuntimeException e) {
            fetchSkins = true;
        }

        Optional<GameProfile> profile = MojangSessionParser.parseProfile(body, fetchSkins);
        if (profile.isEmpty()) {
            // A 200 we cannot parse is not proof of anything. Fail closed.
            LOGGER.warn("Session server returned an unparseable profile for {} — treating as unavailable", username);
            return SessionResult.unavailable();
        }

        LOGGER.info("Session verified for {} (UUID: {}, {} profile properties)",
                profile.get().name(), profile.get().id(), profile.get().properties().size());
        return SessionResult.verified(profile.get());
    }

    // ------------------------------------------------------------------
    // Post-join premium check (UUID comparison)
    // ------------------------------------------------------------------

    /**
     * Check whether an already-joined player's UUID matches the one Mojang has
     * for their username.
     *
     * <p>This is a weaker signal than {@link #verifySession}: it proves the UUID
     * is the one Mojang issues for that name, not that the client owns the
     * account. It is only safe as a secondary check.
     */
    public CompletableFuture<Boolean> isPremiumPlayer(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        String username = player.getName().getString();

        Optional<Boolean> cached = cache.get(playerUuid);
        if (cached.isPresent()) {
            LOGGER.debug("Premium check for {} (cached): {}", username, cached.get());
            return CompletableFuture.completedFuture(cached.get());
        }

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return CompletableFuture.completedFuture(false);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MOJANG_API_URL + encode(username)))
                .timeout(HTTP_REQUEST_TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    PremiumLookup outcome = classifyProfileStatus(response.statusCode());
                    if (outcome != PremiumLookup.PREMIUM) {
                        if (outcome == PremiumLookup.UNAVAILABLE) {
                            LOGGER.warn("Premium check for {} returned HTTP {} — not caching",
                                    username, response.statusCode());
                        } else {
                            cache.put(playerUuid, false);
                        }
                        return false;
                    }

                    UUID mojangUuid = extractUuid(response.body());
                    if (mojangUuid == null) {
                        LOGGER.warn("Premium check for {} got an unparseable profile — not caching", username);
                        return false;
                    }

                    boolean match = mojangUuid.equals(playerUuid);
                    cache.put(playerUuid, match);
                    LOGGER.debug("Premium check for {}: {}", username, match);
                    return match;
                })
                .orTimeout(LOOKUP_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {
                    LOGGER.warn("Premium check for {} failed: {}", username, describe(throwable));
                    return false;
                });
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    public void invalidateCache(UUID uuid) {
        cache.remove(uuid);
    }

    public void clearCache() {
        cache.clear();
        usernameCache.clear();
    }

    /**
     * Stop the dedicated HTTP executor. Safe to call more than once.
     *
     * <p>Not wired anywhere yet; the mod's shutdown hook should call it.
     */
    public void shutdown() {
        httpExecutor.shutdownNow();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String describe(Throwable throwable) {
        Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        String message = cause.getMessage();
        return message != null ? cause.getClass().getSimpleName() + ": " + message : cause.getClass().getSimpleName();
    }

    /**
     * Extract the undashed UUID from a Mojang profile response.
     */
    private static UUID extractUuid(String json) {
        Matcher matcher = UUID_PATTERN.matcher(json);
        if (matcher.find()) {
            String hex = matcher.group(1);
            try {
                String formatted = hex.substring(0, 8) + "-"
                        + hex.substring(8, 12) + "-"
                        + hex.substring(12, 16) + "-"
                        + hex.substring(16, 20) + "-"
                        + hex.substring(20);
                return UUID.fromString(formatted);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Failed to parse UUID from Mojang API: {}", hex);
            }
        }
        return null;
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final String prefix;

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + " #" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * A size-bounded, time-expiring cache.
     *
     * <p>Entries expire after a fixed TTL and the least recently used entry is
     * evicted once the cache is full, so a caller that keys on attacker-supplied
     * data (a username off the wire) cannot grow it without bound.
     *
     * <p>The clock is injected so expiry can be tested without sleeping.
     */
    static final class BoundedTtlCache<K, V> {
        private final long ttlMillis;
        private final LongSupplier clockMillis;
        private final LinkedHashMap<K, Entry<V>> entries;

        BoundedTtlCache(int maxEntries, Duration ttl, LongSupplier clockMillis) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries must be positive");
            }
            this.ttlMillis = ttl.toMillis();
            this.clockMillis = clockMillis;
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, com.nozz.vouch.util.PremiumVerifier.BoundedTtlCache.Entry<V>> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        synchronized Optional<V> get(K key) {
            Entry<V> entry = entries.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (isExpired(entry)) {
                entries.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.value);
        }

        synchronized void put(K key, V value) {
            entries.put(key, new Entry<>(value, clockMillis.getAsLong()));
        }

        synchronized void remove(K key) {
            entries.remove(key);
        }

        synchronized void clear() {
            entries.clear();
        }

        synchronized int size() {
            return entries.size();
        }

        private boolean isExpired(Entry<V> entry) {
            return clockMillis.getAsLong() - entry.storedAt > ttlMillis;
        }

        private record Entry<V>(V value, long storedAt) {
        }
    }
}
