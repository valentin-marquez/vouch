package com.nozz.vouch.command;

import com.nozz.vouch.auth.LoginOutcome;
import com.nozz.vouch.auth.RateLimiter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.nozz.vouch.command.RateLimitDecision.CLEAR;
import static com.nozz.vouch.command.RateLimitDecision.LEAVE_ALONE;
import static com.nozz.vouch.command.RateLimitDecision.PENALIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a credential check does to the rate limiter.
 *
 * <p>This exists because of two bugs that were only visible in the ordering of
 * the calls, not in any single one of them:
 *
 * <ol>
 *   <li>{@code /login} cleared the limiter the instant the <em>password</em>
 *       matched — before the second factor had been checked at all. A stolen
 *       password therefore bought an attacker an unlimited number of guesses at
 *       the six-digit code, because every fresh password submission reset the
 *       counter.</li>
 *   <li>{@code /2fa verify} and {@code /2fa disable} consulted no limiter at
 *       all, so the same six digits could be brute-forced directly.</li>
 * </ol>
 *
 * <p>Both are now one enum, and the commands do nothing to the limiter except
 * through it — so these tests exercise the code the commands actually run.
 */
class RateLimitDecisionTest {

    private static String freshIp() {
        return "198.51.100." + Math.abs(UUID.randomUUID().hashCode() % 250);
    }

    @Nested
    class LoginOutcomes {

        @Test
        void aPasswordThatStillOwesASecondFactorDoesNotClearTheLimiter() {
            assertNotEquals(CLEAR, RateLimitDecision.forLogin(LoginOutcome.NEEDS_2FA));
            assertEquals(LEAVE_ALONE, RateLimitDecision.forLogin(LoginOutcome.NEEDS_2FA));
        }

        @Test
        void onlyAnOutcomeThatActuallyLetsThePlayerInClearsTheLimiter() {
            for (LoginOutcome outcome : LoginOutcome.values()) {
                assertEquals(outcome.grantsAccess(), RateLimitDecision.forLogin(outcome) == CLEAR,
                        outcome + " must clear the limiter only if it grants access");
            }
        }

        @Test
        void aWrongPasswordCountsAgainstThePlayer() {
            assertEquals(PENALIZE, RateLimitDecision.forLogin(LoginOutcome.WRONG_PASSWORD));
        }

        @Test
        void aDatabaseFailureIsNotHeldAgainstThePlayer() {
            assertEquals(LEAVE_ALONE, RateLimitDecision.forLogin(LoginOutcome.CREDENTIALS_UNAVAILABLE));
        }

        @Test
        void probingForAccountsThatDoNotExistStillCosts() {
            assertEquals(PENALIZE, RateLimitDecision.forLogin(LoginOutcome.NOT_REGISTERED));
        }
    }

    @Nested
    class TwoFactorOutcomes {

        @Test
        void awrongCodeCountsAgainstThePlayer() {
            assertEquals(PENALIZE, RateLimitDecision.forTwoFactorCode(false));
        }

        @Test
        void aCorrectCodeClearsTheLimiter() {
            assertEquals(CLEAR, RateLimitDecision.forTwoFactorCode(true));
        }
    }

    @Nested
    class PasswordChanges {

        @Test
        void gettingTheCurrentPasswordWrongCounts() {
            assertEquals(PENALIZE, RateLimitDecision.forPasswordChange(false));
        }

        @Test
        void gettingItRightDoesNotForgiveEarlierFailures() {
            // The player is already logged in, so this proves nothing the limiter is
            // guarding: clearing would let someone inside one account reset the counter
            // protecting every other login from that address.
            assertEquals(LEAVE_ALONE, RateLimitDecision.forPasswordChange(true));
        }
    }

    @Nested
    class AppliedToTheRealLimiter {

        @Test
        void aCorrectPasswordAwaitingASecondFactorLeavesTheCounterWhereItWas() {
            RateLimiter limiter = RateLimiter.getInstance();
            String ip = freshIp();

            // Enough wrong passwords to be blocked.
            for (int i = 0; i < 12; i++) {
                RateLimitDecision.forLogin(LoginOutcome.WRONG_PASSWORD).applyTo(limiter, ip);
            }
            assertTrue(isBlocked(limiter, ip), "twelve wrong passwords should block the address");

            // The bug: a correct password used to clear this before 2FA was checked.
            RateLimitDecision.forLogin(LoginOutcome.NEEDS_2FA).applyTo(limiter, ip);

            assertTrue(isBlocked(limiter, ip),
                    "a password accepted but not yet seconded must not unlock the limiter");
        }

        @Test
        void aFullyAuthenticatedLoginDoesClearTheCounter() {
            RateLimiter limiter = RateLimiter.getInstance();
            String ip = freshIp();

            for (int i = 0; i < 12; i++) {
                RateLimitDecision.forLogin(LoginOutcome.WRONG_PASSWORD).applyTo(limiter, ip);
            }
            assertTrue(isBlocked(limiter, ip));

            RateLimitDecision.forLogin(LoginOutcome.AUTHENTICATED).applyTo(limiter, ip);

            assertFalse(isBlocked(limiter, ip));
        }

        @Test
        void repeatedWrongTwoFactorCodesEventuallyBlockTheAddress() {
            RateLimiter limiter = RateLimiter.getInstance();
            String ip = freshIp();

            assertFalse(isBlocked(limiter, ip), "a fresh address starts unblocked");

            for (int i = 0; i < 12; i++) {
                RateLimitDecision.forTwoFactorCode(false).applyTo(limiter, ip);
            }

            assertTrue(isBlocked(limiter, ip),
                    "a six-digit code must not be guessable without limit");
        }

        @Test
        void leaveAloneTouchesNothing() {
            RateLimiter limiter = RateLimiter.getInstance();
            String ip = freshIp();

            for (int i = 0; i < 12; i++) {
                RateLimitDecision.forLogin(LoginOutcome.WRONG_PASSWORD).applyTo(limiter, ip);
            }

            LEAVE_ALONE.applyTo(limiter, ip);

            assertTrue(isBlocked(limiter, ip));
        }

        private boolean isBlocked(RateLimiter limiter, String ip) {
            return !limiter.getBlockRemaining(ip).equals(Duration.ZERO);
        }
    }
}
