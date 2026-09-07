package com.nozz.vouch.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of {@link Argon2Hasher} around the PHC string format, the legacy
 * {@code salt$hash} format and rehash detection.
 *
 * <p>The golden vectors below were produced directly with BouncyCastle's
 * {@code Argon2BytesGenerator} (not with Argon2Hasher), so they are independent
 * evidence of what a stored hash looks like.
 */
class Argon2HasherTest {

    /**
     * Argon2id over "correct horse battery staple" with m=8192, t=1, p=1 - deliberately
     * *different* from the parameters the current configuration would produce, which is
     * exactly the situation an operator creates by editing crypto.argon2.memory_cost.
     */
    private static final String PHC_LOW_COST_HASH =
            "$argon2id$v=19$m=8192,t=1,p=1$dm91Y2gtcGhjLXZlY3Rvcg$V8f4/cMqWhiGYubIIEsb7S2iogn5IVi+ZLwWp/mCSsc";
    private static final String PHC_LOW_COST_PASSWORD = "correct horse battery staple";

    /**
     * A hash in the pre-PHC storage format: padded base64 salt, '$', padded base64 hash.
     * Computed with the parameters the current defaults produce (m=19456, t=2, p=1),
     * because the legacy format carries no parameters and must be re-derived from config.
     */
    private static final String LEGACY_HASH =
            "dm91Y2gtbGVnYWN5LXZlYw==$4xwaqmkBjCFitE+2rIuhyeC6qGEdk9udyCB0/nOlMbI=";
    private static final String LEGACY_PASSWORD = "legacy-player-password";

    @Test
    void hashProducesAPhcStringCarryingTheArgon2idParameters() {
        String stored = Argon2Hasher.hash("s0me-p4ssword");

        assertTrue(stored.matches("^\\$argon2id\\$v=19\\$m=\\d+,t=\\d+,p=\\d+\\$[A-Za-z0-9+/]+\\$[A-Za-z0-9+/]+$"),
                "expected a PHC string, got: " + stored);

        String[] parts = stored.split("\\$");
        assertFalse(parts[4].contains("="), "PHC requires unpadded base64 for the salt, got: " + stored);
        assertFalse(parts[5].contains("="), "PHC requires unpadded base64 for the digest, got: " + stored);
    }

    @Test
    void hashEncodesA16ByteSaltAndA32ByteDigest() {
        String[] parts = Argon2Hasher.hash("s0me-p4ssword").split("\\$");

        assertEquals(16, Base64.getDecoder().decode(parts[4]).length, "salt length");
        assertEquals(32, Base64.getDecoder().decode(parts[5]).length, "digest length");
    }

    @Test
    void verifyAcceptsThePasswordThatProducedTheHash() {
        String stored = Argon2Hasher.hash("s0me-p4ssword");

        assertTrue(Argon2Hasher.verify("s0me-p4ssword", stored));
    }

    @Test
    void verifyRejectsAWrongPassword() {
        String stored = Argon2Hasher.hash("s0me-p4ssword");

        assertFalse(Argon2Hasher.verify("s0me-p4sswore", stored));
        assertFalse(Argon2Hasher.verify("", stored));
    }

    @Test
    void hashUsesAFreshSaltEveryTimeSoTwoHashesOfTheSamePasswordDiffer() {
        String first = Argon2Hasher.hash("same-password");
        String second = Argon2Hasher.hash("same-password");

        assertNotEquals(first, second);
        assertTrue(Argon2Hasher.verify("same-password", first));
        assertTrue(Argon2Hasher.verify("same-password", second));
    }

    /**
     * The regression this whole format change exists for: a stored hash created with
     * different Argon2 parameters than the running configuration must still verify,
     * because the parameters are read out of the stored string.
     */
    @Test
    void verifyUsesTheParametersStoredInTheHashRatherThanTheCurrentConfiguration() {
        assertTrue(Argon2Hasher.verify(PHC_LOW_COST_PASSWORD, PHC_LOW_COST_HASH),
                "a hash created with m=8192,t=1,p=1 must verify even though the current settings differ");
    }

    @Test
    void verifyRejectsAWrongPasswordAgainstAStoredPhcHash() {
        assertFalse(Argon2Hasher.verify("wrong horse battery staple", PHC_LOW_COST_HASH));
    }

    @Test
    void verifyStillAcceptsHashesInTheLegacySaltDollarHashFormat() {
        assertTrue(Argon2Hasher.verify(LEGACY_PASSWORD, LEGACY_HASH),
                "pre-existing installs store 'b64salt$b64hash' and must keep working");
    }

    @Test
    void verifyRejectsAWrongPasswordAgainstALegacyHash() {
        assertFalse(Argon2Hasher.verify("not-the-password", LEGACY_HASH));
    }

    @Test
    void verifyReturnsFalseInsteadOfThrowingForMalformedStoredHashes() {
        assertFalse(Argon2Hasher.verify("pw", ""));
        assertFalse(Argon2Hasher.verify("pw", "garbage"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2id$v=19$m=8192,t=1,p=1$dm91Y2gtcGhjLXZlY3Rvcg"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2id$v=19$m=8192,t=1$abc$def"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2i$v=19$m=8192,t=1,p=1$dm91Y2gtcGhjLXZlY3Rvcg$V8f4"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2id$v=16$m=8192,t=1,p=1$dm91Y2gtcGhjLXZlY3Rvcg$V8f4"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2id$v=19$m=lots,t=1,p=1$dm91Y2gtcGhjLXZlY3Rvcg$V8f4"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2id$v=19$m=8192,t=1,p=1$***$***"));
        assertFalse(Argon2Hasher.verify("pw", "$argon2id$v=19$m=0,t=0,p=0$dm91Y2gtcGhjLXZlY3Rvcg$V8f4"));
        assertFalse(Argon2Hasher.verify("pw", PHC_LOW_COST_HASH.substring(0, PHC_LOW_COST_HASH.length() - 10)));
        assertFalse(Argon2Hasher.verify("pw", null));
    }

    @Test
    void needsRehashIsTrueForLegacyFormatHashes() {
        assertTrue(Argon2Hasher.needsRehash(LEGACY_HASH));
    }

    @Test
    void needsRehashIsTrueWhenTheStoredParametersAreWeakerThanTheCurrentSettings() {
        assertTrue(Argon2Hasher.needsRehash(PHC_LOW_COST_HASH));
    }

    @Test
    void needsRehashIsFalseForAHashJustCreatedWithTheCurrentSettings() {
        assertFalse(Argon2Hasher.needsRehash(Argon2Hasher.hash("s0me-p4ssword")));
    }

    @Test
    void needsRehashIsTrueForUnreadableStoredHashes() {
        assertTrue(Argon2Hasher.needsRehash("garbage"));
        assertTrue(Argon2Hasher.needsRehash(""));
        assertTrue(Argon2Hasher.needsRehash(null));
    }

    @Test
    void hashAsyncAndVerifyAsyncRoundTripThroughTheSharedExecutor() {
        String stored = Argon2Hasher.hashAsync("async-password").join();

        assertTrue(Argon2Hasher.verifyAsync("async-password", stored).join());
        assertFalse(Argon2Hasher.verifyAsync("other-password", stored).join());
    }
}
