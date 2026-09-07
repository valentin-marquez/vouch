package com.nozz.vouch.command;

import com.nozz.vouch.auth.LoginOutcome;
import com.nozz.vouch.auth.RateLimiter;

import java.util.Optional;

/**
 * What one credential check should do to the rate limiter.
 *
 * <p>The command layer never calls {@link RateLimiter#recordSuccess(String)} or
 * {@link RateLimiter#recordFailure(String)} directly; it computes a decision here
 * and applies it. That is not tidiness — it is the fix for two bugs that lived
 * entirely in <em>when</em> those calls were made:
 *
 * <ul>
 *   <li>{@code /login} recorded success as soon as the password matched, before the
 *       2FA check. An attacker holding a stolen password could resubmit it between
 *       code guesses and keep the counter at zero forever, so the second factor was
 *       effectively unlimited. {@link LoginOutcome#NEEDS_2FA} now leaves the counter
 *       exactly where it was — the login is not finished, so nothing is forgiven.</li>
 *   <li>{@code /2fa verify} and {@code /2fa disable} consulted no limiter at all,
 *       leaving six digits brute-forceable. They now go through
 *       {@link #forTwoFactorCode(boolean)}.</li>
 * </ul>
 */
public enum RateLimitDecision {

    /** The attempt succeeded outright: forget the failures. */
    CLEAR,

    /** The attempt failed on something the player controls: count it. */
    PENALIZE,

    /**
     * The attempt neither succeeded nor failed on the player's account — it is
     * half-finished, or the server itself could not answer. Leave the counter alone.
     */
    LEAVE_ALONE;

    /**
     * The effect of a login attempt.
     *
     * <p>Only an outcome that actually admits the player clears the counter, which is
     * exactly {@link LoginOutcome#grantsAccess()} — deriving it from there rather than
     * listing cases keeps the two from drifting apart when an outcome is added.
     */
    public static RateLimitDecision forLogin(LoginOutcome outcome) {
        if (outcome.grantsAccess()) {
            return CLEAR;
        }
        return switch (outcome) {
            // Both are the player guessing: a wrong password, or a username that has no
            // account here. Charging for the latter is what stops account enumeration
            // from being free.
            case WRONG_PASSWORD, NOT_REGISTERED -> PENALIZE;

            // The password was right but the login is not over. Forgiving the counter
            // here is what made the second factor brute-forceable.
            case NEEDS_2FA -> LEAVE_ALONE;

            // The database could not produce the hash. The player did nothing wrong and
            // an outage must not lock the server's own users out.
            case CREDENTIALS_UNAVAILABLE -> LEAVE_ALONE;

            default -> LEAVE_ALONE;
        };
    }

    /** The effect of checking a TOTP code, whether for login, setup or disabling. */
    public static RateLimitDecision forTwoFactorCode(boolean valid) {
        return valid ? CLEAR : PENALIZE;
    }

    /**
     * The effect of checking the current password during a password change.
     *
     * <p>A wrong one is a guess against a live credential and counts. A right one is
     * <em>not</em> forgiveness for earlier failures: the player is already logged in,
     * so this proved nothing the limiter is protecting. Clearing here would let an
     * attacker who is inside one account reset the counter that guards every other
     * login attempt from the same address.
     */
    public static RateLimitDecision forPasswordChange(boolean currentPasswordValid) {
        return currentPasswordValid ? LEAVE_ALONE : PENALIZE;
    }

    /** Apply this decision. A null or blank address is ignored rather than bucketed. */
    public void applyTo(RateLimiter limiter, String ip) {
        if (limiter == null || ip == null || ip.isBlank()) {
            return;
        }
        switch (this) {
            case CLEAR -> limiter.recordSuccess(ip);
            case PENALIZE -> limiter.recordFailure(ip);
            case LEAVE_ALONE -> {
            }
        }
    }

    /**
     * Apply this decision to an address that may not have resolved.
     *
     * <p>{@code PlayerIps.of} returns an empty Optional when the connection address
     * cannot be parsed. Collapsing that into a placeholder string would put every
     * such player in one shared bucket, where one of them could lock out all the
     * others — so an unresolved address is simply not counted.
     */
    public void applyTo(RateLimiter limiter, Optional<String> ip) {
        ip.ifPresent(address -> applyTo(limiter, address));
    }
}
