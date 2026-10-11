package com.nozz.vouch.auth;

import org.junit.jupiter.api.Test;

import static com.nozz.vouch.auth.SessionRestoreDecision.NO_SESSION;
import static com.nozz.vouch.auth.SessionRestoreDecision.RESTORE;
import static com.nozz.vouch.auth.SessionRestoreDecision.RESTORE_NEEDS_2FA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * When a returning player may skip the password.
 *
 * What the stored session actually proves is narrow: that somebody with this
 * username reached us from this address recently. On an offline-mode server the
 * UUID is derived from the username, so the username half is attacker-chosen
 * and the address is the only real evidence — and behind CGNAT an address is
 * shared by strangers. Every rule here exists to keep that weak proof from
 * being used where a stronger one is available or a second factor is owed.
 */
class SessionRestorePolicyTest {

    /** A returning player with a live session from the same address, no 2FA. */
    private static SessionRestoreFacts.Builder returningPlayer() {
        return SessionRestoreFacts.builder()
                .persistenceEnabled(true)
                .addressKnown(true)
                .liveSessionForThisAddress(true)
                .premiumVerifiedThisLogin(false)
                .twoFactorEnabled(false)
                .require2faOnRestore(true);
    }

    @Test
    void aReturningPlayerSkipsThePassword() {
        assertEquals(RESTORE, SessionRestorePolicy.decide(returningPlayer().build()));
    }

    @Test
    void noSessionMeansTheOrdinaryLogin() {
        assertEquals(NO_SESSION,
                SessionRestorePolicy.decide(returningPlayer().liveSessionForThisAddress(false).build()));
    }

    @Test
    void persistenceTurnedOffIsHonoured() {
        assertEquals(NO_SESSION,
                SessionRestorePolicy.decide(returningPlayer().persistenceEnabled(false).build()));
    }

    @Test
    void anUnresolvableAddressCannotRestore() {
        // The address is the entire evidence. Without it there is nothing to
        // check, and "we could not tell" must never read as "close enough".
        assertEquals(NO_SESSION,
                SessionRestorePolicy.decide(returningPlayer().addressKnown(false).build()));
    }

    @Test
    void aPremiumVerifiedPlayerNeverUsesASession() {
        // They proved ownership of the account cryptographically during this
        // login. Falling back to the weaker IP evidence would only add a way in.
        assertEquals(NO_SESSION,
                SessionRestorePolicy.decide(returningPlayer().premiumVerifiedThisLogin(true).build()));
    }

    @Test
    void premiumVerificationOutranksEvenALiveSession() {
        assertEquals(NO_SESSION, SessionRestorePolicy.decide(returningPlayer()
                .premiumVerifiedThisLogin(true)
                .liveSessionForThisAddress(true)
                .build()));
    }

    @Test
    void aSecondFactorIsStillOwedWhenTheAccountHasOne() {
        assertEquals(RESTORE_NEEDS_2FA,
                SessionRestorePolicy.decide(returningPlayer().twoFactorEnabled(true).build()));
    }

    @Test
    void theSecondFactorOnRestoreCanBeWaivedByConfiguration() {
        assertEquals(RESTORE, SessionRestorePolicy.decide(returningPlayer()
                .twoFactorEnabled(true)
                .require2faOnRestore(false)
                .build()));
    }

    @Test
    void waivingTheSecondFactorCannotResurrectADeadSession() {
        // The waiver relaxes which factors are owed, never whether a session
        // exists in the first place.
        assertEquals(NO_SESSION, SessionRestorePolicy.decide(returningPlayer()
                .twoFactorEnabled(true)
                .require2faOnRestore(false)
                .liveSessionForThisAddress(false)
                .build()));
    }

    @Test
    void onlyRestoreGrantsImmediateAccess() {
        for (SessionRestoreDecision decision : SessionRestoreDecision.values()) {
            assertEquals(decision == RESTORE, decision.grantsAccess(), decision + ".grantsAccess()");
        }
    }
}
