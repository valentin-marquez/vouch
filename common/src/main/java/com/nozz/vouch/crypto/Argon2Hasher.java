package com.nozz.vouch.crypto;

import com.nozz.vouch.VouchMod;
import com.nozz.vouch.config.VouchConfigManager;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Argon2id password hasher using Bouncy Castle (pure Java).
 *
 * <p>Hashes are stored in the standard PHC string format, which carries the cost
 * parameters used to produce them:
 *
 * <pre>$argon2id$v=19$m=19456,t=2,p=1$&lt;unpadded base64 salt&gt;$&lt;unpadded base64 hash&gt;</pre>
 *
 * <p>Because the parameters travel with the hash, changing the configuration (or
 * shipping a new default) no longer invalidates every stored password: verification
 * always recomputes with the parameters the hash was created with. The configuration
 * is consulted only when creating a <em>new</em> hash.
 *
 * <p>Hashes written before the PHC migration are stored as
 * {@code base64(salt) + "$" + base64(hash)} with no parameters at all. Those are still
 * accepted and are recomputed with the current configuration, exactly as before;
 * {@link #needsRehash(String)} reports them so they can be upgraded on next login.
 *
 * <p>Default parameters follow the OWASP Argon2id recommendation and are
 * configurable in vouch.toml:
 * <ul>
 *   <li>Memory: 19 MiB (19456 KiB) - crypto.argon2.memory_cost</li>
 *   <li>Iterations: 2 - crypto.argon2.iterations</li>
 *   <li>Parallelism: 1 - crypto.argon2.parallelism</li>
 * </ul>
 *
 * All hashing operations are async to prevent TPS impact.
 */
public final class Argon2Hasher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Argon2");

    // Default Argon2id parameters (used if config unavailable) - OWASP recommendation
    private static final int DEFAULT_MEMORY_COST = 19456;  // 19 MiB in KiB
    private static final int DEFAULT_ITERATIONS = 2;
    private static final int DEFAULT_PARALLELISM = 1;

    private static final int HASH_LENGTH = 32;     // 256 bits
    private static final int SALT_LENGTH = 16;     // 128 bits

    /** PHC identifier for the only algorithm this class produces or accepts. */
    private static final String PHC_ALGORITHM = "argon2id";
    /** Argon2 version 0x13 (19), the only version Bouncy Castle emits here. */
    private static final int PHC_VERSION = Argon2Parameters.ARGON2_VERSION_13;
    /** Number of '$'-separated fields in a PHC string, including the empty leading one. */
    private static final int PHC_FIELD_COUNT = 6;
    /** Number of '$'-separated fields in a pre-PHC (legacy) hash: salt and digest. */
    private static final int LEGACY_FIELD_COUNT = 2;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Argon2Hasher() {
    }

    /**
     * Parsed contents of a stored hash: the digest, its salt and the parameters
     * needed to reproduce it.
     */
    private record StoredHash(byte[] salt, byte[] hash, int memoryCost, int iterations, int parallelism,
                              boolean legacy) {
    }

    /**
     * Get memory cost from config or default.
     */
    private static int getMemoryCost() {
        try {
            return VouchConfigManager.getInstance().getArgon2MemoryCost();
        } catch (Exception e) {
            return DEFAULT_MEMORY_COST;
        }
    }

    /**
     * Get iterations from config or default.
     */
    private static int getIterations() {
        try {
            return VouchConfigManager.getInstance().getArgon2Iterations();
        } catch (Exception e) {
            return DEFAULT_ITERATIONS;
        }
    }

    /**
     * Get parallelism from config or default.
     */
    private static int getParallelism() {
        try {
            return VouchConfigManager.getInstance().getArgon2Parallelism();
        } catch (Exception e) {
            return DEFAULT_PARALLELISM;
        }
    }

    /**
     * Hash a password asynchronously using Argon2id
     *
     * @param password The plain text password
     * @return CompletableFuture containing the encoded hash (PHC string)
     */
    public static CompletableFuture<String> hashAsync(String password) {
        return CompletableFuture.supplyAsync(() -> hash(password), VouchMod.getInstance().getAsyncExecutor());
    }

    /**
     * Verify a password against a stored hash asynchronously
     *
     * @param password The plain text password to verify
     * @param storedHash The stored hash to verify against
     * @return CompletableFuture containing true if password matches
     */
    public static CompletableFuture<Boolean> verifyAsync(String password, String storedHash) {
        return CompletableFuture.supplyAsync(() -> verify(password, storedHash), VouchMod.getInstance().getAsyncExecutor());
    }

    /**
     * Hash a password synchronously (use hashAsync for production).
     *
     * @return a PHC string of the form
     *         {@code $argon2id$v=19$m=<memory>,t=<iterations>,p=<parallelism>$<salt>$<hash>}
     */
    public static String hash(String password) {
        long start = System.currentTimeMillis();

        int memoryCost = getMemoryCost();
        int iterations = getIterations();
        int parallelism = getParallelism();

        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);

        byte[] hash = computeHash(password.toCharArray(), salt, memoryCost, iterations, parallelism);

        String encoded = encode(salt, hash, memoryCost, iterations, parallelism);

        LOGGER.debug("Password hashed in {}ms", System.currentTimeMillis() - start);
        return encoded;
    }

    /**
     * Verify a password synchronously (use verifyAsync for production).
     *
     * <p>Accepts both the PHC format and the legacy {@code salt$hash} format. PHC hashes
     * are recomputed with the parameters stored alongside them; legacy hashes carry no
     * parameters and are recomputed with the current configuration.
     */
    public static boolean verify(String password, String storedHash) {
        long start = System.currentTimeMillis();

        try {
            StoredHash parsed = parse(storedHash);
            if (parsed == null) {
                LOGGER.warn("Invalid hash format");
                return false;
            }

            byte[] computedHash = computeHash(password.toCharArray(), parsed.salt(),
                    parsed.memoryCost(), parsed.iterations(), parsed.parallelism());

            // Constant-time comparison to prevent timing attacks
            boolean matches = constantTimeEquals(parsed.hash(), computedHash);

            LOGGER.debug("Password verified in {}ms (match={})", System.currentTimeMillis() - start, matches);
            return matches;

        } catch (Exception e) {
            LOGGER.error("Error verifying password", e);
            return false;
        }
    }

    /**
     * Whether a stored hash should be recomputed because it does not match what the
     * current configuration would produce.
     *
     * <p>True for legacy (parameterless) hashes, for hashes whose stored cost parameters
     * differ from the configured ones, and for anything unparseable. Callers are expected
     * to rehash only after a successful {@link #verify(String, String)}, since that is the
     * only moment the plain text password is available.
     */
    public static boolean needsRehash(String storedHash) {
        try {
            StoredHash parsed = parse(storedHash);
            if (parsed == null || parsed.legacy()) {
                return true;
            }
            return parsed.memoryCost() != getMemoryCost()
                    || parsed.iterations() != getIterations()
                    || parsed.parallelism() != getParallelism();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Encode salt, digest and parameters as a PHC string. Base64 is unpadded, as the
     * PHC string format requires.
     */
    private static String encode(byte[] salt, byte[] hash, int memoryCost, int iterations, int parallelism) {
        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return "$" + PHC_ALGORITHM
                + "$v=" + PHC_VERSION
                + "$m=" + memoryCost + ",t=" + iterations + ",p=" + parallelism
                + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(hash);
    }

    /**
     * Parse a stored hash in either supported format, or return null if it is neither.
     *
     * <p>The formats are told apart unambiguously: a PHC string starts with '$', while a
     * legacy hash is {@code base64$base64} and base64 never contains '$'.
     *
     * @throws IllegalArgumentException if the base64 fields are not decodable
     */
    private static StoredHash parse(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return null;
        }

        return storedHash.charAt(0) == '$' ? parsePhc(storedHash) : parseLegacy(storedHash);
    }

    /**
     * Parse {@code $argon2id$v=19$m=<memory>,t=<iterations>,p=<parallelism>$<salt>$<hash>}.
     */
    private static StoredHash parsePhc(String storedHash) {
        String[] fields = storedHash.split("\\$", -1);
        if (fields.length != PHC_FIELD_COUNT) {
            return null;
        }
        if (!PHC_ALGORITHM.equals(fields[1])) {
            return null;
        }

        Integer version = parseLabelled(fields[2], "v=");
        if (version == null || version != PHC_VERSION) {
            return null;
        }

        String[] costs = fields[3].split(",", -1);
        if (costs.length != 3) {
            return null;
        }
        Integer memoryCost = parseLabelled(costs[0], "m=");
        Integer iterations = parseLabelled(costs[1], "t=");
        Integer parallelism = parseLabelled(costs[2], "p=");
        if (memoryCost == null || iterations == null || parallelism == null) {
            return null;
        }
        if (memoryCost < 1 || iterations < 1 || parallelism < 1) {
            return null;
        }

        byte[] salt = Base64.getDecoder().decode(fields[4]);
        byte[] hash = Base64.getDecoder().decode(fields[5]);
        if (salt.length == 0 || hash.length == 0) {
            return null;
        }

        return new StoredHash(salt, hash, memoryCost, iterations, parallelism, false);
    }

    /**
     * Parse the pre-PHC {@code base64(salt) + "$" + base64(hash)} format. It stores no
     * parameters, so the current configuration is used - the historical behaviour.
     */
    private static StoredHash parseLegacy(String storedHash) {
        String[] fields = storedHash.split("\\$", -1);
        if (fields.length != LEGACY_FIELD_COUNT) {
            return null;
        }

        byte[] salt = Base64.getDecoder().decode(fields[0]);
        byte[] hash = Base64.getDecoder().decode(fields[1]);
        if (salt.length == 0 || hash.length == 0) {
            return null;
        }

        return new StoredHash(salt, hash, getMemoryCost(), getIterations(), getParallelism(), true);
    }

    /**
     * Read the integer out of a {@code <label><digits>} PHC field, or null if the field
     * is not shaped that way.
     */
    private static Integer parseLabelled(String field, String label) {
        if (!field.startsWith(label)) {
            return null;
        }
        try {
            return Integer.parseInt(field.substring(label.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] computeHash(char[] password, byte[] salt, int memoryCost, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(PHC_VERSION)
                .withSalt(salt)
                .withMemoryAsKB(memoryCost)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] hash = new byte[HASH_LENGTH];
        generator.generateBytes(password, hash);

        return hash;
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
