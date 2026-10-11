package com.nozz.vouch.auth;

import org.junit.jupiter.api.Test;

import static com.nozz.vouch.auth.LoginOutcome.AUTHENTICATED;
import static com.nozz.vouch.auth.LoginOutcome.AUTHENTICATED_MUST_SETUP_2FA;
import static com.nozz.vouch.auth.LoginOutcome.CREDENTIALS_UNAVAILABLE;
import static com.nozz.vouch.auth.LoginOutcome.NEEDS_2FA;
import static com.nozz.vouch.auth.LoginOutcome.NOT_REGISTERED;
import static com.nozz.vouch.auth.LoginOutcome.WRONG_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The authentication decision matrix.
 *
 * This is the most security-relevant conditional in the mod and until now it
 * lived five levels deep inside a chain of async callbacks in VouchCommands,
 * where no test could reach it. Each case below is a rule someone could
 * plausibly break while editing the login flow.
 */
class LoginPolicyTest {

    /** A registered player with a valid password, no 2FA, not an operator. */
    private static LoginFacts.Builder ordinaryPlayer() {
        return LoginFacts.builder()
                .registered(true)
                .passwordHashPresent(true)
                .passwordValid(true)
                .twoFactorEnabled(false)
                .operator(false)
                .require2faForOps(false);
    }

    @Test
    void unregisteredPlayerIsToldToRegister() {
        LoginFacts facts = ordinaryPlayer().registered(false).build();

        assertEquals(NOT_REGISTERED, LoginPolicy.decide(facts));
    }

    @Test
    void registrationIsCheckedBeforeThePassword() {
        // An unregistered player must never be told "wrong password" — that
        // would confirm which usernames exist on the server.
        LoginFacts facts = ordinaryPlayer().registered(false).passwordValid(false).build();

        assertEquals(NOT_REGISTERED, LoginPolicy.decide(facts));
    }

    @Test
    void aRegisteredPlayerWithNoStoredHashIsAFailure() {
        // Registered but the hash could not be read: a database problem, never
        // a reason to let somebody in.
        LoginFacts facts = ordinaryPlayer().passwordHashPresent(false).build();

        assertEquals(CREDENTIALS_UNAVAILABLE, LoginPolicy.decide(facts));
    }

    @Test
    void wrongPasswordIsRejected() {
        LoginFacts facts = ordinaryPlayer().passwordValid(false).build();

        assertEquals(WRONG_PASSWORD, LoginPolicy.decide(facts));
    }

    @Test
    void correctPasswordAuthenticatesAnOrdinaryPlayer() {
        assertEquals(AUTHENTICATED, LoginPolicy.decide(ordinaryPlayer().build()));
    }

    @Test
    void twoFactorIsRequiredWhenEnabled() {
        LoginFacts facts = ordinaryPlayer().twoFactorEnabled(true).build();

        assertEquals(NEEDS_2FA, LoginPolicy.decide(facts));
    }

    @Test
    void twoFactorOutranksTheOperatorSetupNag() {
        // An operator who already has 2FA gets the ordinary 2FA challenge,
        // not the "please set it up" path.
        LoginFacts facts = ordinaryPlayer()
                .twoFactorEnabled(true)
                .operator(true)
                .require2faForOps(true)
                .build();

        assertEquals(NEEDS_2FA, LoginPolicy.decide(facts));
    }

    @Test
    void operatorWithoutTwoFactorIsLetInAndAskedToSetItUp() {
        // Documents today's behaviour deliberately: require_2fa_for_ops does
        // NOT gate access, it only nags. Locking operators out of their own
        // server on a config flip would be a nastier failure than this is.
        LoginFacts facts = ordinaryPlayer().operator(true).require2faForOps(true).build();

        assertEquals(AUTHENTICATED_MUST_SETUP_2FA, LoginPolicy.decide(facts));
    }

    @Test
    void operatorIsNotNaggedWhenTheSettingIsOff() {
        LoginFacts facts = ordinaryPlayer().operator(true).require2faForOps(false).build();

        assertEquals(AUTHENTICATED, LoginPolicy.decide(facts));
    }

    @Test
    void nonOperatorIsNeverNaggedEvenWhenTheSettingIsOn() {
        LoginFacts facts = ordinaryPlayer().operator(false).require2faForOps(true).build();

        assertEquals(AUTHENTICATED, LoginPolicy.decide(facts));
    }

    @Test
    void onlyTheTwoAuthenticatedOutcomesGrantAccess() {
        // Guards against a future outcome being added without deciding
        // whether it lets the player in.
        for (LoginOutcome outcome : LoginOutcome.values()) {
            boolean expected = outcome == AUTHENTICATED || outcome == AUTHENTICATED_MUST_SETUP_2FA;
            assertEquals(expected, outcome.grantsAccess(), outcome + ".grantsAccess()");
        }
    }
}
