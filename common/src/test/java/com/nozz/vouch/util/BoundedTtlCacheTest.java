package com.nozz.vouch.util;

import com.nozz.vouch.util.PremiumVerifier.BoundedTtlCache;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The premium-lookup cache used to be two plain {@link java.util.concurrent.ConcurrentHashMap}s
 * that were only ever written to. One of them is keyed by a username taken
 * straight off the wire in {@code onHello}, so an attacker could grow it
 * without bound by reconnecting with fresh names.
 *
 * <p>Time is injected so expiry can be asserted exactly, without sleeping.
 */
class BoundedTtlCacheTest {

    private final AtomicLong now = new AtomicLong(1_000L);

    private <K, V> BoundedTtlCache<K, V> cache(int maxEntries, Duration ttl) {
        return new BoundedTtlCache<>(maxEntries, ttl, now::get);
    }

    @Test
    void returnsAStoredValueBeforeTheTtlElapses() {
        BoundedTtlCache<String, String> cache = cache(8, Duration.ofMinutes(5));
        cache.put("Notch", "premium");

        now.addAndGet(Duration.ofMinutes(4).toMillis());

        assertEquals(Optional.of("premium"), cache.get("Notch"));
    }

    @Test
    void dropsAValueOnceTheTtlHasElapsed() {
        BoundedTtlCache<String, String> cache = cache(8, Duration.ofMinutes(5));
        cache.put("Notch", "premium");

        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);

        assertEquals(Optional.empty(), cache.get("Notch"));
    }

    @Test
    void anExpiredEntryIsRemovedRatherThanLeftBehind() {
        BoundedTtlCache<String, String> cache = cache(8, Duration.ofSeconds(30));
        cache.put("Notch", "premium");

        now.addAndGet(Duration.ofMinutes(1).toMillis());
        cache.get("Notch");

        assertEquals(0, cache.size());
    }

    @Test
    void neverGrowsPastItsCapacity() {
        BoundedTtlCache<String, String> cache = cache(16, Duration.ofHours(1));

        for (int i = 0; i < 5_000; i++) {
            cache.put("player" + i, "premium");
        }

        assertEquals(16, cache.size());
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryWhenFull() {
        BoundedTtlCache<String, String> cache = cache(2, Duration.ofHours(1));
        cache.put("a", "1");
        cache.put("b", "2");

        cache.put("c", "3");

        assertEquals(Optional.empty(), cache.get("a"));
        assertEquals(Optional.of("2"), cache.get("b"));
        assertEquals(Optional.of("3"), cache.get("c"));
    }

    @Test
    void readingAnEntryMakesItTheMostRecentlyUsed() {
        BoundedTtlCache<String, String> cache = cache(2, Duration.ofHours(1));
        cache.put("a", "1");
        cache.put("b", "2");

        cache.get("a");
        cache.put("c", "3");

        assertTrue(cache.get("a").isPresent(), "'a' was just read and must survive eviction");
        assertFalse(cache.get("b").isPresent(), "'b' was the least recently used");
    }

    @Test
    void removeDropsASingleEntry() {
        BoundedTtlCache<String, String> cache = cache(8, Duration.ofHours(1));
        cache.put("a", "1");
        cache.put("b", "2");

        cache.remove("a");

        assertEquals(Optional.empty(), cache.get("a"));
        assertEquals(Optional.of("2"), cache.get("b"));
    }

    @Test
    void clearEmptiesTheCache() {
        BoundedTtlCache<String, String> cache = cache(8, Duration.ofHours(1));
        cache.put("a", "1");
        cache.put("b", "2");

        cache.clear();

        assertEquals(0, cache.size());
    }
}
