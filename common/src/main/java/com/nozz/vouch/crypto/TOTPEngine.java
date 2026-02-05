package com.nozz.vouch.crypto;

import com.nozz.vouch.config.VouchConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * TOTP (Time-based One-Time Password) engine implementing RFC 6238.
 * 
 * Generates and validates 6-digit codes for 2FA authentication.
 * Compatible with Google Authenticator, Authy, Aegis, etc.
 * 
 * Configuration values from vouch.toml:
 * - totp.window_size: Time window tolerance (default: 1)
 * - totp.time_step: Time step in seconds (default: 30)
 */
public final class TOTPEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/TOTP");

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int SECRET_LENGTH = 20;  // 160 bits for SHA1
    private static final int CODE_DIGITS = 6;
    
    // Default values (used if config not available)
    private static final int DEFAULT_TIME_STEP = 30;
    private static final int DEFAULT_WINDOW_SIZE = 1;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TOTPEngine() {
    }

    /**
     * Get the time step from configuration (or default)
     */
    private static int getTimeStep() {
        try {
            return VouchConfigManager.getInstance().getTotpTimeStep();
        } catch (Exception e) {
            return DEFAULT_TIME_STEP;
        }
    }

    /**
     * Get the window size from configuration (or default)
     */
    private static int getWindowSize() {
        try {
            return VouchConfigManager.getInstance().getTotpWindowSize();
        } catch (Exception e) {
            return DEFAULT_WINDOW_SIZE;
        }
    }

    /**
     * Generate a new random TOTP secret
     * 
     * @return Base32-encoded secret (20 bytes raw = 32 chars base32)
     */
    public static String generateSecret() {
        byte[] secret = new byte[SECRET_LENGTH];
        SECURE_RANDOM.nextBytes(secret);
        return base32Encode(secret);
    }

    /**
     * Generate the otpauth:// URI for QR code generation
     * 
     * @param secret Base32-encoded secret
     * @param accountName Player name or identifier
     * @param issuer "Vouch" or server name
     * @return otpauth:// URI string
     */
    public static String generateOtpAuthUri(String secret, String accountName, String issuer) {
        int timeStep = getTimeStep();
        return String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
                urlEncode(issuer),
                urlEncode(accountName),
                secret,
                urlEncode(issuer),
                CODE_DIGITS,
                timeStep
        );
    }

    /**
     * Generate the current TOTP code
     * 
     * @param secret Base32-encoded secret
     * @return 6-digit code as string (zero-padded)
     */
    public static String generateCode(String secret) {
        return generateCode(secret, System.currentTimeMillis() / 1000);
    }

    /**
     * Generate a TOTP code for a specific time
     */
    public static String generateCode(String secret, long timeSeconds) {
        int timeStep = getTimeStep();
        long counter = timeSeconds / timeStep;
        byte[] secretBytes = base32Decode(secret);
        int code = generateOTP(secretBytes, counter);
        return String.format("%0" + CODE_DIGITS + "d", code);
    }

    /**
     * Verify a TOTP code with time window tolerance
     * 
     * @param secret Base32-encoded secret
     * @param code The 6-digit code to verify
     * @return true if code is valid within the time window
     */
    public static boolean verifyCode(String secret, String code) {
        if (code == null || code.length() != CODE_DIGITS) {
            return false;
        }

        try {
            int inputCode = Integer.parseInt(code);
            byte[] secretBytes = base32Decode(secret);
            long currentTime = System.currentTimeMillis() / 1000;
            int timeStep = getTimeStep();
            int windowSize = getWindowSize();
            long currentCounter = currentTime / timeStep;

            // Check current time step and ±windowSize steps
            for (int i = -windowSize; i <= windowSize; i++) {
                int expectedCode = generateOTP(secretBytes, currentCounter + i);
                if (expectedCode == inputCode) {
                    LOGGER.debug("TOTP code verified (offset={})", i);
                    return true;
                }
            }

            LOGGER.debug("TOTP code verification failed");
            return false;

        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid TOTP code format: {}", code);
            return false;
        }
    }

    private static int generateOTP(byte[] secret, long counter) {
        try {
            byte[] counterBytes = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counterBytes[i] = (byte) (counter & 0xff);
                counter >>= 8;
            }

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(counterBytes);

            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);

            return binary % (int) Math.pow(10, CODE_DIGITS);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            LOGGER.error("Failed to generate OTP", e);
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    // Base32 encoding/decoding (RFC 4648)
    private static String base32Encode(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32_CHARS.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }

        if (bitsLeft > 0) {
            result.append(BASE32_CHARS.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }

        return result.toString();
    }

    private static byte[] base32Decode(String encoded) {
        encoded = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] result = new byte[encoded.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;

        for (char c : encoded.toCharArray()) {
            buffer = (buffer << 5) | BASE32_CHARS.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }

        return result;
    }

    private static String urlEncode(String s) {
        return s.replace(" ", "%20")
                .replace(":", "%3A")
                .replace("/", "%2F")
                .replace("?", "%3F")
                .replace("&", "%26")
                .replace("=", "%3D");
    }
}
