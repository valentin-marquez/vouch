package com.nozz.vouch.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.nozz.vouch.db.DatabaseTestSupport.freshDatabaseName;
import static com.nozz.vouch.db.DatabaseTestSupport.openPool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code has2FAEnabled} must fail CLOSED.
 *
 * <p>It used to catch {@link SQLException} and return {@code false}, which is
 * indistinguishable from "this player has no 2FA". Two callers then went on to
 * authenticate on the password alone, so any database hiccup - or an attacker
 * exhausting the connection pool - was a 2FA bypass.
 */
class TwoFactorFailClosedTest {

    private DatabaseManager db;

    @BeforeEach
    void setUp() throws SQLException {
        openPool(freshDatabaseName("vouch_2fa"));
        db = DatabaseManager.getInstance();
        db.initializeSchema();
    }

    @AfterEach
    void tearDown() {
        DatabaseTestSupport.tearDown();
    }

    @Test
    void reportsTheRealAnswerWhileTheDatabaseIsHealthy() throws Exception {
        UUID withTotp = UUID.randomUUID();
        UUID withoutTotp = UUID.randomUUID();

        db.registerPlayerWith2FA(withTotp, "TwoFa", "JBSWY3DPEHPK3PXP").get(5, TimeUnit.SECONDS);
        db.registerPlayer(withoutTotp, "NoTwoFa", "hash").get(5, TimeUnit.SECONDS);

        assertTrue(db.has2FAEnabled(withTotp).get(5, TimeUnit.SECONDS));
        assertFalse(db.has2FAEnabled(withoutTotp).get(5, TimeUnit.SECONDS));
    }

    @Test
    void anUnregisteredPlayerGenuinelyHasNo2FA() throws Exception {
        assertFalse(db.has2FAEnabled(UUID.randomUUID()).get(5, TimeUnit.SECONDS),
                "no row means the player is not registered, which is a real 'no 2FA'");
    }

    @Test
    void completesExceptionallyWhenTheDatabaseIsUnavailable() {
        UUID uuid = UUID.randomUUID();
        ConnectionFactory.getInstance().close();

        CompletableFuture<Boolean> future = db.has2FAEnabled(uuid);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS),
                "a database failure must NOT resolve to false - that is a 2FA bypass");
        assertInstanceOf(SQLException.class, thrown.getCause(),
                "expected the underlying SQLException, got: " + thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("refusing to assume 2FA is disabled"),
                thrown.getCause().getMessage());
    }

    /**
     * The signature is unchanged, so existing {@code thenAccept} callers still compile.
     * With an exceptionally-completed future their consumer simply never runs, which
     * leaves the player unauthenticated - closed, not open.
     */
    @Test
    void aThenAcceptCallerNeverSeesAValueOnFailure() throws Exception {
        ConnectionFactory.getInstance().close();

        boolean[] consumerRan = {false};
        CompletableFuture<Void> chain = db.has2FAEnabled(UUID.randomUUID())
                .thenAccept(has2FA -> consumerRan[0] = true);

        assertThrows(ExecutionException.class, () -> chain.get(5, TimeUnit.SECONDS));
        assertFalse(consumerRan[0], "the caller must not be told '2FA is off' when the DB is down");
    }

    @Test
    void signatureIsStillCompletableFutureOfBoolean() {
        CompletableFuture<Boolean> future = db.has2FAEnabled(UUID.randomUUID());
        assertEquals(CompletableFuture.class, future.getClass());
    }
}
