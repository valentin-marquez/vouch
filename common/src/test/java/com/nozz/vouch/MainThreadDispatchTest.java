package com.nozz.vouch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code onMain} — the single hop from an async result back onto the server thread.
 *
 * <p>Before this existed there were roughly thirty-five hand-written
 * {@code future.thenAccept(v -> runOnMainThread(...))} chains and exactly one
 * {@code .exceptionally()} in the entire mod. Every other async failure was
 * swallowed whole: no message, no log line, no state change. The player sat in the
 * jail watching a countdown they could not stop until it kicked them, and the
 * server operator had nothing to go on.
 *
 * <p>So the contract asserted here is mostly about the failure path: the success
 * consumer must not run, something must be logged, and the caller's recovery must
 * run on the main thread like the success path would have.
 *
 * <p>With no server bound, {@code runOnMainThread} executes inline, which is what
 * lets this be a unit test.
 */
class MainThreadDispatchTest {

    private static final class Boom extends RuntimeException {
        Boom() {
            super("the database went away");
        }
    }

    @Test
    void aSuccessfulFutureReachesTheConsumer() {
        AtomicReference<String> seen = new AtomicReference<>();

        VouchMod.getInstance().onMain(
                CompletableFuture.completedFuture("hello"), "greeting", seen::set);

        assertEquals("hello", seen.get());
    }

    @Test
    void aNullResultStillReachesTheConsumer() {
        AtomicBoolean ran = new AtomicBoolean();

        VouchMod.getInstance().onMain(
                CompletableFuture.completedFuture(null), "void work", value -> ran.set(true));

        assertTrue(ran.get(), "a Void future completing is still a completion");
    }

    @Test
    void aFailedFutureNeverReachesTheConsumer() {
        AtomicReference<String> seen = new AtomicReference<>();

        VouchMod.getInstance().onMain(
                CompletableFuture.failedFuture(new Boom()), "greeting", seen::set);

        assertNull(seen.get());
    }

    @Test
    void aFailedFutureRunsTheRecoveryTheCallerSupplied() {
        AtomicBoolean recovered = new AtomicBoolean();

        VouchMod.getInstance().onMain(
                CompletableFuture.failedFuture(new Boom()), "greeting",
                value -> {
                }, () -> recovered.set(true));

        assertTrue(recovered.get(), "the player has to be told something");
    }

    @Test
    void aSuccessfulFutureDoesNotRunTheRecovery() {
        AtomicBoolean recovered = new AtomicBoolean();
        AtomicBoolean consumed = new AtomicBoolean();

        VouchMod.getInstance().onMain(
                CompletableFuture.completedFuture("ok"), "greeting",
                value -> consumed.set(true), () -> recovered.set(true));

        assertTrue(consumed.get());
        assertFalse(recovered.get());
    }

    @Test
    void aConsumerThatThrowsIsTreatedAsAFailureRatherThanVanishing() {
        AtomicBoolean recovered = new AtomicBoolean();

        VouchMod.getInstance().onMain(
                CompletableFuture.completedFuture("ok"), "greeting",
                value -> {
                    throw new Boom();
                },
                () -> recovered.set(true));

        assertTrue(recovered.get(),
                "a handler blowing up on the main thread must not be swallowed either");
    }

    @Test
    void aRecoveryThatThrowsDoesNotEscapeIntoTheServerThread() {
        VouchMod.getInstance().onMain(
                CompletableFuture.failedFuture(new Boom()), "greeting",
                value -> {
                },
                () -> {
                    throw new Boom();
                });
        // Reaching here at all is the assertion: nothing propagated out of the hop.
    }

    @Test
    void aFutureThatFailsLaterIsStillHandled() throws Exception {
        CompletableFuture<String> pending = new CompletableFuture<>();
        AtomicBoolean recovered = new AtomicBoolean();

        VouchMod.getInstance().onMain(pending, "greeting", value -> {
        }, () -> recovered.set(true));

        assertFalse(recovered.get(), "nothing should happen before the future settles");
        pending.completeExceptionally(new CompletionException(new Boom()));

        assertTrue(recovered.get());
    }
}
