package com.nozz.vouch.auth;

import com.nozz.vouch.auth.MixedModeLoginHandler.TtlStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backs the pending-encryption-challenge map.
 *
 * <p>The old map was filled on every encryption challenge and drained only
 * when the matching key packet arrived. Every connection that took the
 * challenge and then went away — a TCP drop, a client crash, or the expected
 * rejection path where a cracked client picks a premium username — left a
 * permanent entry holding an RSA {@link java.security.KeyPair}. Anyone could
 * trigger that at will, so the store now expires entries and refuses to grow
 * past a hard ceiling.
 *
 * <p>Vanilla disconnects a login that has not finished within 600 ticks
 * (30 seconds), so nothing legitimate ever outlives the TTL.
 */
class TtlStoreTest {

    private final AtomicLong now = new AtomicLong(10_000L);

    private <K, V> TtlStore<K, V> store(int maxEntries, Duration ttl) {
        return new TtlStore<>(maxEntries, ttl, now::get);
    }

    @Test
    void takeReturnsAStoredValue() {
        TtlStore<String, String> store = store(4, Duration.ofSeconds(30));
        assertTrue(store.put("conn-1", "challenge"));

        assertEquals(Optional.of("challenge"), store.take("conn-1"));
    }

    @Test
    void takeConsumesTheEntry() {
        TtlStore<String, String> store = store(4, Duration.ofSeconds(30));
        store.put("conn-1", "challenge");

        store.take("conn-1");

        assertEquals(Optional.empty(), store.take("conn-1"),
                "a challenge must be answerable exactly once");
        assertEquals(0, store.size());
    }

    @Test
    void takeReturnsNothingForAnUnknownKey() {
        TtlStore<String, String> store = store(4, Duration.ofSeconds(30));

        assertEquals(Optional.empty(), store.take("never-seen"));
    }

    @Test
    void anEntryOlderThanTheTtlIsNotReturned() {
        TtlStore<String, String> store = store(4, Duration.ofSeconds(30));
        store.put("conn-1", "challenge");

        now.addAndGet(Duration.ofSeconds(30).toMillis() + 1);

        assertEquals(Optional.empty(), store.take("conn-1"));
    }

    @Test
    void abandonedEntriesDoNotAccumulate() {
        // The leak in its original shape: 5000 connections take a challenge and
        // never answer. Nothing may be left behind once the TTL has passed.
        TtlStore<String, String> store = store(8192, Duration.ofSeconds(30));
        for (int i = 0; i < 5_000; i++) {
            store.put("conn-" + i, "challenge");
        }

        now.addAndGet(Duration.ofMinutes(1).toMillis());
        store.put("fresh", "challenge");

        assertEquals(1, store.size());
    }

    @Test
    void sweepRemovesOnlyExpiredEntries() {
        TtlStore<String, String> store = store(16, Duration.ofSeconds(30));
        store.put("old", "challenge");

        now.addAndGet(Duration.ofSeconds(20).toMillis());
        store.put("recent", "challenge");

        now.addAndGet(Duration.ofSeconds(15).toMillis()); // old is 35s, recent is 15s
        assertEquals(1, store.sweep());
        assertEquals(Optional.empty(), store.take("old"));
        assertEquals(Optional.of("challenge"), store.take("recent"));
    }

    @Test
    void putIsRejectedWhenTheStoreIsFull() {
        TtlStore<String, String> store = store(2, Duration.ofSeconds(30));
        assertTrue(store.put("a", "1"));
        assertTrue(store.put("b", "2"));

        assertFalse(store.put("c", "3"),
                "a full store must refuse new challenges rather than evict an in-flight one");
        assertEquals(Optional.of("1"), store.take("a"));
    }

    @Test
    void aFullStoreAcceptsNewEntriesOnceOldOnesExpire() {
        TtlStore<String, String> store = store(2, Duration.ofSeconds(30));
        store.put("a", "1");
        store.put("b", "2");

        now.addAndGet(Duration.ofMinutes(1).toMillis());

        assertTrue(store.put("c", "3"));
        assertEquals(1, store.size());
    }

    @Test
    void reputForTheSameKeyReplacesRatherThanFillingTheStore() {
        TtlStore<String, String> store = store(1, Duration.ofSeconds(30));
        store.put("a", "1");

        assertTrue(store.put("a", "2"));
        assertEquals(1, store.size());
        assertEquals(Optional.of("2"), store.take("a"));
    }

    @Test
    void discardRemovesAnEntryWithoutReadingIt() {
        TtlStore<String, String> store = store(4, Duration.ofSeconds(30));
        store.put("conn-1", "challenge");

        store.discard("conn-1");

        assertEquals(0, store.size());
    }
}
