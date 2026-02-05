package com.nozz.vouch.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Generates and hashes secure session tokens.
 * 
 * Uses SecureRandom for cryptographically secure token generation
 * and SHA-256 for hashing tokens before database storage.
 */
public final class SessionTokenGenerator {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_LENGTH = 32; // 32 bytes = 64 hex chars
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private SessionTokenGenerator() {

    }

    /**
     * Generate a cryptographically secure random token.
     * 
     * @return 64-character hexadecimal string
     */
    public static String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    /**
     * Hash a token using SHA-256 for secure storage.
     * 
     * The hash is used to verify session validity without storing
     * the raw token in the database.
     * 
     * @param token The raw session token
     * @return SHA-256 hash of the token as hex string
     */
    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Convert byte array to hexadecimal string.
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
