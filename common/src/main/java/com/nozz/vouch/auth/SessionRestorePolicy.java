package com.nozz.vouch.auth;

/**
 * Decides whether a returning player may skip the password.
 *
 * <p>Be clear about what a stored session proves: that somebody using this
 * username reached the server from this address, recently. On an offline-mode
 * server the UUID is derived from the username, so that half is chosen by
 * whoever is connecting; the address is the only real evidence, and behind
 * CGNAT an address is shared with strangers. This is address trust, not a
 * token — the mod has no channel to hand a vanilla client a secret and get it
 * back, so nothing stronger is available.
 *
 * <p>The rules below therefore keep that weak evidence from being used where
 * better evidence already exists, or where a second factor is still owed.
 */
public final class SessionRestorePolicy {

    private SessionRestorePolicy() {
    }

    public static SessionRestoreDecision decide(SessionRestoreFacts facts) {
        if (!facts.persistenceEnabled()) {
            return SessionRestoreDecision.NO_SESSION;
        }
        // The address is the whole proof. "We could not determine it" must never
        // be treated as a match.
        if (!facts.addressKnown()) {
            return SessionRestoreDecision.NO_SESSION;
        }
        // A player who completed the premium handshake this login already proved
        // ownership cryptographically. Consulting the weaker evidence as well
        // would only add a second way in.
        if (facts.premiumVerifiedThisLogin()) {
            return SessionRestoreDecision.NO_SESSION;
        }
        if (!facts.liveSessionForThisAddress()) {
            return SessionRestoreDecision.NO_SESSION;
        }
        if (facts.twoFactorEnabled() && facts.require2faOnRestore()) {
            return SessionRestoreDecision.RESTORE_NEEDS_2FA;
        }
        return SessionRestoreDecision.RESTORE;
    }
}
