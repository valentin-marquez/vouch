package com.nozz.vouch.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record of which connections Vouch actually verified cryptographically.
 *
 * <p>Without it, the join handler re-derives "this player is premium" from
 * nothing more than the UUID not looking like an offline UUID. A Geyser or
 * Floodgate player, or any UUID injected by a proxy that forwards player data,
 * satisfies that test just as well as a real Mojang session does — and gets a
 * password-less login out of it. The handshake already knows the answer for
 * certain; these tests pin down how that knowledge is carried across to the
 * join event.
 */
class VerifiedPremiumLoginTest {

    private static final UUID PREMIUM_UUID = UUID.fromString("4566e69f-c907-48ee-8d71-d7ba5aa00d20");

    private final AtomicLong now = new AtomicLong(50_000L);

    private MixedModeLoginHandler handler() {
        return new MixedModeLoginHandler(now::get);
    }

    @Test
    void aRecordedLoginIsReportedAsVerified() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        assertTrue(handler.wasVerifiedAtLogin(PREMIUM_UUID, "Thinkofdeath"));
    }

    @Test
    void anUnknownUuidIsNotVerified() {
        MixedModeLoginHandler handler = handler();

        assertFalse(handler.wasVerifiedAtLogin(UUID.randomUUID(), "Thinkofdeath"));
    }

    @Test
    void theRecordIsConsumedByTheFirstQuery() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        assertTrue(handler.wasVerifiedAtLogin(PREMIUM_UUID, "Thinkofdeath"));
        assertFalse(handler.wasVerifiedAtLogin(PREMIUM_UUID, "Thinkofdeath"),
                "a single verified handshake must not authorise a second join");
    }

    @Test
    void theUsernameMustMatchTheOneThatWasVerified() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        assertFalse(handler.wasVerifiedAtLogin(PREMIUM_UUID, "SomeoneElse"));
    }

    @Test
    void aMismatchedUsernameDoesNotConsumeTheRecord() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        handler.wasVerifiedAtLogin(PREMIUM_UUID, "SomeoneElse");

        assertTrue(handler.wasVerifiedAtLogin(PREMIUM_UUID, "Thinkofdeath"),
                "a wrong-name probe must not be able to burn a real player's record");
    }

    @Test
    void theUsernameComparisonIgnoresCase() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        assertTrue(handler.wasVerifiedAtLogin(PREMIUM_UUID, "THINKOFDEATH"));
    }

    @Test
    void aRecordOlderThanItsTtlIsNotHonoured() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        now.addAndGet(MixedModeLoginHandler.VERIFIED_LOGIN_TTL.toMillis() + 1);

        assertFalse(handler.wasVerifiedAtLogin(PREMIUM_UUID, "Thinkofdeath"));
    }

    @Test
    void aRecordIsStillHonouredJustInsideItsTtl() {
        MixedModeLoginHandler handler = handler();
        handler.recordVerifiedPremiumLogin(PREMIUM_UUID, "Thinkofdeath");

        now.addAndGet(MixedModeLoginHandler.VERIFIED_LOGIN_TTL.toMillis() - 1);

        assertTrue(handler.wasVerifiedAtLogin(PREMIUM_UUID, "Thinkofdeath"));
    }
}
