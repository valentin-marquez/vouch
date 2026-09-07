package com.nozz.vouch.auth;

/** What to do with a returning player who may have a stored session. */
public enum SessionRestoreDecision {
    /** No usable session: the player authenticates normally. */
    NO_SESSION(false),

    /** Session accepted; the player is in. */
    RESTORE(true),

    /** Session accepted, but the account's second factor is still owed. */
    RESTORE_NEEDS_2FA(false);

    private final boolean grantsAccess;

    SessionRestoreDecision(boolean grantsAccess) {
        this.grantsAccess = grantsAccess;
    }

    public boolean grantsAccess() {
        return grantsAccess;
    }
}
