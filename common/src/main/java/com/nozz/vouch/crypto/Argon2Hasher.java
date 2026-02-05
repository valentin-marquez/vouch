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
 * Default parameters tuned for Minecraft servers (configurable in vouch.toml):
 * - Memory: 15 MiB (15360 KiB) - crypto.argon2.memory_cost
 * - Iterations: 2 - crypto.argon2.iterations
 * - Parallelism: 1 - crypto.argon2.parallelism
 * 
 * All hashing operations are async to prevent TPS impact.
 */
public final class Argon2Hasher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Argon2");

    // Default Argon2id parameters (used if config unavailable)
    private static final int DEFAULT_MEMORY_COST = 15360;  // 15 MiB in KiB
    private static final int DEFAULT_ITERATIONS = 2;
    private static final int DEFAULT_PARALLELISM = 1;
    
    private static final int HASH_LENGTH = 32;     // 256 bits
    private static final int SALT_LENGTH = 16;     // 128 bits

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Argon2Hasher() {
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
     * @return CompletableFuture containing the encoded hash (salt$hash in base64)
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
     * Hash a password synchronously (use hashAsync for production)
     */
    public static String hash(String password) {
        long start = System.currentTimeMillis();

        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);

        byte[] hash = computeHash(password.toCharArray(), salt);

        String encoded = Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);

        LOGGER.debug("Password hashed in {}ms", System.currentTimeMillis() - start);
        return encoded;
    }

    /**
     * Verify a password synchronously (use verifyAsync for production)
     */
    public static boolean verify(String password, String storedHash) {
        long start = System.currentTimeMillis();

        try {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 2) {
                LOGGER.warn("Invalid hash format");
                return false;
            }

            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[1]);

            byte[] computedHash = computeHash(password.toCharArray(), salt);

            // Constant-time comparison to prevent timing attacks
            boolean matches = constantTimeEquals(expectedHash, computedHash);

            LOGGER.debug("Password verified in {}ms (match={})", System.currentTimeMillis() - start, matches);
            return matches;

        } catch (Exception e) {
            LOGGER.error("Error verifying password", e);
            return false;
        }
    }

    private static byte[] computeHash(char[] password, byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(getMemoryCost())
                .withIterations(getIterations())
                .withParallelism(getParallelism())
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
