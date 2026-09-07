package com.nozz.vouch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every step of authentication must be reachable while unauthenticated.
 *
 * The pre-auth gate blocks by command root, and a player who has entered their
 * password but still owes a 2FA code is not authenticated yet — so the roots
 * carrying the second factor are blocked by exactly the same rule as the rest.
 * When the second factor moved under /vouch, the default allowlist stopped
 * containing any root that could complete a login, and every 2FA user was
 * locked out of their own account on a stock configuration.
 *
 * Nothing else caught that: each half was individually correct.
 */
class PreAuthLoginReachabilityTest {

    private static void assertReachable(String command) {
        assertTrue(PreAuthCommandGate.evaluate(command, PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS).allowed(),
                command + " must be runnable before authentication completes, "
                        + "otherwise a player who reaches this step can never finish logging in");
    }

    @Test
    void aPlayerCanRegister() {
        assertReachable("/register hunter2 hunter2");
        assertReachable("/reg hunter2 hunter2");
    }

    @Test
    void aPlayerCanEnterTheirPassword() {
        assertReachable("/login hunter2");
        assertReachable("/l hunter2");
    }

    @Test
    void aPlayerOwingASecondFactorCanSubmitIt() {
        assertReachable("/2fa verify 123456");
        assertReachable("/vouch 2fa verify 123456");
        assertReachable("/auth 2fa verify 123456");
    }

    @Test
    void theGateStillBlocksEverythingElse() {
        // The allowlist widened; make sure it did not become a pass.
        for (String blocked : new String[]{"/tpa someone", "/home", "/msg x hi", "/op me",
                "/minecraft:me waves", "/kit starter", "/give @s diamond"}) {
            assertTrue(!PreAuthCommandGate.evaluate(blocked, PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS).allowed(),
                    blocked + " must stay blocked before authentication");
        }
    }
}
