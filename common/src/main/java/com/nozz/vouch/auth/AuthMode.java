package com.nozz.vouch.auth;

/**
 * Authentication mode configuration.
 * 
 * Determines how players authenticate on the server.
 */
public enum AuthMode {
    /**
     * Traditional password-only authentication.
     * - Players use /register and /login with passwords
     * - 2FA commands are disabled
     * - Simplest setup for small servers
     */
    PASSWORD_ONLY("password_only", "Password Only"),
    
    /**
     * TOTP-only authentication (no passwords).
     * - Players use /register to generate a TOTP secret
     * - Login requires /login <code> with authenticator code
     * - More secure but requires all players to have an authenticator app
     */
    TWOFA_ONLY("2fa_only", "2FA Only (TOTP)"),
    
    /**
     * Password with optional 2FA per user (default).
     * - Players use /register and /login with passwords
     * - Players can optionally enable 2FA via /2fa setup
     * - Best balance of security and accessibility
     */
    PASSWORD_OPTIONAL_2FA("password_optional_2fa", "Password + Optional 2FA");
    
    private final String configValue;
    private final String displayName;
    
    AuthMode(String configValue, String displayName) {
        this.configValue = configValue;
        this.displayName = displayName;
    }
    
    /**
     * Get the value used in configuration files.
     */
    public String getConfigValue() {
        return configValue;
    }
    
    /**
     * Get the human-readable display name.
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Check if this mode uses passwords.
     */
    public boolean usesPassword() {
        return this != TWOFA_ONLY;
    }
    
    /**
     * Check if this mode uses TOTP.
     */
    public boolean usesTotp() {
        return this != PASSWORD_ONLY;
    }
    
    /**
     * Check if 2FA is always required (not optional).
     */
    public boolean is2FARequired() {
        return this == TWOFA_ONLY;
    }
    
    /**
     * Check if 2FA is optional (user choice).
     */
    public boolean is2FAOptional() {
        return this == PASSWORD_OPTIONAL_2FA;
    }
    
    /**
     * Parse an AuthMode from a configuration string.
     * Falls back to PASSWORD_OPTIONAL_2FA if invalid.
     */
    public static AuthMode fromConfig(String value) {
        if (value == null || value.isEmpty()) {
            return PASSWORD_OPTIONAL_2FA;
        }
        
        String normalized = value.toLowerCase().trim();
        
        for (AuthMode mode : values()) {
            if (mode.configValue.equals(normalized)) {
                return mode;
            }
        }
        
        // Try matching by enum name
        try {
            return valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Fall through to default
        }
        
        return PASSWORD_OPTIONAL_2FA;
    }
    
    /**
     * Get a comma-separated list of valid config values.
     */
    public static String getValidValues() {
        StringBuilder sb = new StringBuilder();
        for (AuthMode mode : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(mode.configValue);
        }
        return sb.toString();
    }
}
