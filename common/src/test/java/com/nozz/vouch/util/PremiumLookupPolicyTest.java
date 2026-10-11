package com.nozz.vouch.util;

import com.nozz.vouch.util.PremiumVerifier.PremiumLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP-status policy that decides whether a player is premium.
 *
 * <p>This is the single most security-relevant decision Vouch makes on an
 * offline-mode server. The old code collapsed every non-200 response to
 * {@code false}, which meant a Mojang outage, a rate limit and a genuinely
 * unregistered username were indistinguishable — and the outage answer was
 * then cached for five minutes. A premium player caught by that would be let
 * in under an offline UUID (losing their world data), and their username
 * would be claimable by a cracked client.
 *
 * <p>The rule these tests pin down: only an explicit "this name/session does
 * not exist" answer may ever mean "not premium". Everything else means "we do
 * not know", and a caller that does not know must fail closed.
 */
class PremiumLookupPolicyTest {

    // ---------- Mojang profile endpoint (api.mojang.com/users/profiles/minecraft/<name>) ----------

    @Test
    void profileLookupTreats200AsPremium() {
        assertEquals(PremiumLookup.PREMIUM, PremiumVerifier.classifyProfileStatus(200));
    }

    @Test
    void profileLookupTreats404AsNotPremium() {
        assertEquals(PremiumLookup.NOT_PREMIUM, PremiumVerifier.classifyProfileStatus(404));
    }

    @Test
    void profileLookupTreats204AsNotPremium() {
        // Some Mojang deployments answer an unknown username with 204 No Content
        // rather than 404. Both are a definitive "no such account".
        assertEquals(PremiumLookup.NOT_PREMIUM, PremiumVerifier.classifyProfileStatus(204));
    }

    @Test
    void profileLookupTreatsRateLimitAsUnavailable() {
        assertEquals(PremiumLookup.UNAVAILABLE, PremiumVerifier.classifyProfileStatus(429));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void profileLookupTreatsServerErrorsAsUnavailable(int status) {
        assertEquals(PremiumLookup.UNAVAILABLE, PremiumVerifier.classifyProfileStatus(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 405, 418})
    void profileLookupTreatsOtherClientErrorsAsUnavailable(int status) {
        // A 400 means we sent a malformed request — our bug, never evidence
        // that the player is not premium.
        assertEquals(PremiumLookup.UNAVAILABLE, PremiumVerifier.classifyProfileStatus(status));
    }

    // ---------- Mojang session server (sessionserver.mojang.com/session/minecraft/hasJoined) ----------

    @Test
    void sessionCheckTreats200AsPremium() {
        assertEquals(PremiumLookup.PREMIUM, PremiumVerifier.classifySessionStatus(200));
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 404})
    void sessionCheckTreatsEmptyAnswerAsNotAuthenticated(int status) {
        // hasJoined answers 204 when the client never authenticated with Mojang
        // for this server id. That is a definitive rejection, not an outage.
        assertEquals(PremiumLookup.NOT_PREMIUM, PremiumVerifier.classifySessionStatus(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 429, 500, 503})
    void sessionCheckTreatsEverythingElseAsUnavailable(int status) {
        assertEquals(PremiumLookup.UNAVAILABLE, PremiumVerifier.classifySessionStatus(status));
    }

    // ---------- Caching ----------

    @Test
    void unavailableIsNeverCacheable() {
        // Caching an outage-derived negative is how a five-minute Mojang blip
        // turns into five minutes of premium players joining as offline users.
        assertFalse(PremiumLookup.UNAVAILABLE.isCacheable());
    }

    @Test
    void definitiveAnswersAreCacheable() {
        assertTrue(PremiumLookup.PREMIUM.isCacheable());
        assertTrue(PremiumLookup.NOT_PREMIUM.isCacheable());
    }

    // ---------- Whole-range sweep ----------

    @Test
    void noStatusOtherThan200EverMeansPremium() {
        for (int status = 100; status < 600; status++) {
            if (status == 200) {
                continue;
            }
            assertNotEquals(PremiumLookup.PREMIUM, PremiumVerifier.classifyProfileStatus(status),
                    "profile status " + status + " must not be read as premium");
            assertNotEquals(PremiumLookup.PREMIUM, PremiumVerifier.classifySessionStatus(status),
                    "session status " + status + " must not be read as premium");
        }
    }

    @Test
    void onlyExplicitNotFoundAnswersEverMeanNotPremium() {
        for (int status = 100; status < 600; status++) {
            if (status == 204 || status == 404) {
                continue;
            }
            assertNotEquals(PremiumLookup.NOT_PREMIUM, PremiumVerifier.classifyProfileStatus(status),
                    "profile status " + status + " must not be read as a definitive negative");
            assertNotEquals(PremiumLookup.NOT_PREMIUM, PremiumVerifier.classifySessionStatus(status),
                    "session status " + status + " must not be read as a definitive negative");
        }
    }
}
