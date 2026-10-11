package com.nozz.vouch.auth;

/**
 * Decides the result of a login attempt from already-gathered facts.
 *
 * The order of these checks is itself a security property: registration is
 * settled before the password is judged, so a failed attempt never reveals
 * whether the username exists on this server.
 */
public final class LoginPolicy {

    private LoginPolicy() {
    }

    public static LoginOutcome decide(LoginFacts facts) {
        if (!facts.registered()) {
            return LoginOutcome.NOT_REGISTERED;
        }
        if (!facts.passwordHashPresent()) {
            return LoginOutcome.CREDENTIALS_UNAVAILABLE;
        }
        if (!facts.passwordValid()) {
            return LoginOutcome.WRONG_PASSWORD;
        }
        if (facts.twoFactorEnabled()) {
            return LoginOutcome.NEEDS_2FA;
        }
        if (facts.operator() && facts.require2faForOps()) {
            return LoginOutcome.AUTHENTICATED_MUST_SETUP_2FA;
        }
        return LoginOutcome.AUTHENTICATED;
    }
}
