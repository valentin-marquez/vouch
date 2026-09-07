package com.nozz.vouch.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.nozz.vouch.db.DatabaseTestSupport.freshDatabaseName;
import static com.nozz.vouch.db.DatabaseTestSupport.openPool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the executor every async database call runs on.
 *
 * <p>These calls used to pass no executor at all, so all of them landed on
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}, whose parallelism is
 * {@code availableProcessors() - 1}: a single thread on a 2-vCPU VPS, shared with the
 * whole JVM, running blocking JDBC against a 10-second connection timeout.
 */
class DatabaseManagerExecutorTest {

    private static final int POOL_SIZE = 6;

    private DatabaseManager db;

    @BeforeEach
    void setUp() throws SQLException {
        openPool(freshDatabaseName("vouch_exec"), POOL_SIZE, 1);
        db = DatabaseManager.getInstance();
        db.initializeSchema();
    }

    @AfterEach
    void tearDown() {
        DatabaseTestSupport.tearDown();
    }

    @Test
    void databaseThreadsAreNamedAndDaemon() throws Exception {
        Future<String[]> probe = db.getExecutor().submit(() -> {
            Thread t = Thread.currentThread();
            return new String[]{t.getName(), String.valueOf(t.isDaemon())};
        });
        String[] result = probe.get(5, TimeUnit.SECONDS);

        assertTrue(result[0].startsWith("Vouch-DB-"),
                "database threads should be identifiable in a thread dump, got: " + result[0]);
        assertEquals("true", result[1], "database threads must be daemons so they never block JVM exit");
    }

    /**
     * The executor is saturated first so the query is provably still queued when the
     * continuation is attached - otherwise {@code thenApply} could run on the test
     * thread and the assertion would prove nothing.
     */
    @Test
    void aRealQueryRunsOnTheVouchExecutorNotTheCommonPool() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            db.getExecutor().submit(() -> {
                occupied.countDown();
                gate.await();
                return null;
            });
        }
        assertTrue(occupied.await(5, TimeUnit.SECONDS), "could not saturate the executor");

        CompletableFuture<String> completingThread =
                db.isRegistered(UUID.randomUUID()).thenApply(ignored -> Thread.currentThread().getName());
        assertFalse(completingThread.isDone(), "the query should still be queued behind the blocked threads");

        gate.countDown();
        String thread = completingThread.get(10, TimeUnit.SECONDS);

        assertTrue(thread.startsWith("Vouch-DB-"),
                "isRegistered completed on the wrong thread: " + thread);
        assertFalse(thread.contains("ForkJoinPool.commonPool"),
                "database work must never run on the common pool");
    }

    @Test
    void executorIsSizedFromTheConnectionPool() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) db.getExecutor();
        assertEquals(POOL_SIZE, ConnectionFactory.getInstance().getPoolMaxSize());
        assertEquals(POOL_SIZE, executor.getCorePoolSize(),
                "more threads than connections just queues work against Hikari's timeout");
    }

    @Test
    void executorNeverDropsBelowTwoThreadsOnATinyPool() {
        DatabaseTestSupport.tearDown();
        openPool(freshDatabaseName("vouch_exec_tiny"), 1, 1);

        ThreadPoolExecutor executor = (ThreadPoolExecutor) DatabaseManager.getInstance().getExecutor();
        assertTrue(executor.getCorePoolSize() >= 2,
                "expected at least 2 threads, got " + executor.getCorePoolSize());
    }

    @Test
    void shutdownIsIdempotentAndTheExecutorCanBeRecreated() {
        ExecutorService first = db.getExecutor();

        db.shutdown();
        db.shutdown();
        assertTrue(first.isShutdown());

        ExecutorService second = db.getExecutor();
        assertNotSame(first, second, "a later call must hand out a working executor again");
        assertFalse(second.isShutdown());
    }

    /** {@code ConnectionFactory.close()} is the mod's shutdown path; the executor rides along. */
    @Test
    void closingTheConnectionPoolDrainsTheExecutor() {
        ExecutorService executor = db.getExecutor();
        assertFalse(executor.isShutdown());

        ConnectionFactory.getInstance().close();

        assertTrue(executor.isShutdown(),
                "closing the pool must stop the database executor, not leak its threads");
    }

    @Test
    void moreConcurrentCallsThanThreadsAreQueuedNotRejected() throws Exception {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(db.isRegistered(UUID.randomUUID()));
        }
        for (CompletableFuture<Boolean> future : futures) {
            assertFalse(future.get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncCallsStillWorkEndToEnd() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertTrue(db.registerPlayer(uuid, "Herobrine", "hash").get(5, TimeUnit.SECONDS));
        assertTrue(db.isRegistered(uuid).get(5, TimeUnit.SECONDS));
        assertEquals("hash", db.getPasswordHash(uuid).get(5, TimeUnit.SECONDS).orElseThrow());
        db.updateLastLogin(uuid, "198.51.100.4").get(5, TimeUnit.SECONDS);
        assertTrue(db.unregisterPlayer(uuid).get(5, TimeUnit.SECONDS));
        assertFalse(db.isRegistered(uuid).get(5, TimeUnit.SECONDS));
    }

    /**
     * Regression for the MySQL fallout of the old single-try-block schema init: this
     * table did not exist there, so every premium-override query failed forever.
     */
    @Test
    void premiumOverrideRoundTripsNowThatTheTableExists() throws Exception {
        assertEquals(PremiumOverrideStatus.NO_OVERRIDE,
                db.getPremiumOverrideStatus("Nobody").get(5, TimeUnit.SECONDS));

        assertTrue(db.setForcedOffline("Cracked", true, "admin-uuid").get(5, TimeUnit.SECONDS));
        assertEquals(PremiumOverrideStatus.FORCED_OFFLINE,
                db.getPremiumOverrideStatus("CRACKED").get(5, TimeUnit.SECONDS));

        assertTrue(db.setForcedOffline("Cracked", false, "admin-uuid").get(5, TimeUnit.SECONDS));
        assertEquals(PremiumOverrideStatus.MARKED_ONLINE,
                db.getPremiumOverrideStatus("cracked").get(5, TimeUnit.SECONDS));
    }

    @Test
    void sessionsRoundTrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        db.registerPlayer(uuid, "Sessioned", "hash").get(5, TimeUnit.SECONDS);

        assertTrue(db.createSession(uuid, "192.0.2.9", "token-hash",
                java.time.Instant.now().plusSeconds(600)).get(5, TimeUnit.SECONDS));
        assertTrue(db.validateSession(uuid, "192.0.2.9").get(5, TimeUnit.SECONDS));
        assertFalse(db.validateSession(uuid, "192.0.2.10").get(5, TimeUnit.SECONDS));
        assertTrue(db.hasAnyValidSession(uuid).get(5, TimeUnit.SECONDS));
        assertEquals(1, db.deleteAllSessions(uuid).get(5, TimeUnit.SECONDS));
        assertFalse(db.hasAnyValidSession(uuid).get(5, TimeUnit.SECONDS));
    }
}
