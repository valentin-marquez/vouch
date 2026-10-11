package com.nozz.vouch.auth;

/**
 * The possible results of evaluating a login attempt.
 *
 * Separated from the command layer so the decision can be tested without a
 * server: see {@link LoginPolicy}.
 */
public enum LoginOutcome {
    /** No account exists for this player. */
    NOT_REGISTERED(false),

    /** The account exists but its password hash could not be read. */
    CREDENTIALS_UNAVAILABLE(false),

    /** The password did not match. */
    WRONG_PASSWORD(false),

    /** Password accepted; the player still owes a second factor. */
    NEEDS_2FA(false),

    /** Fully authenticated. */
    AUTHENTICATED(true),

    /**
     * Authenticated, but the player is an operator on a server configured to
     * want 2FA from operators and has not set it up yet. Access is granted and
     * the player is asked to configure it.
     */
    AUTHENTICATED_MUST_SETUP_2FA(true);

    private final boolean grantsAccess;

    LoginOutcome(boolean grantsAccess) {
        this.grantsAccess = grantsAccess;
    }

    /** Whether this outcome lets the player into the world. */
    public boolean grantsAccess() {
        return grantsAccess;
    }
}
