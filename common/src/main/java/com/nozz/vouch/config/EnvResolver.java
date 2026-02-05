package com.nozz.vouch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Environment variable resolver for secure configuration.
 * 
 * Supports two methods for environment variable resolution:
 * 
 * 1. Explicit syntax: ${ENV:VARIABLE_NAME}
 *    Example: database.password=${ENV:DB_PASSWORD}
 * 
 * 2. Auto-override with VOUCH_ prefix:
 *    Setting VOUCH_DATABASE_PASSWORD will override database.password
 *    The mapping converts: database.password -> VOUCH_DATABASE_PASSWORD
 * 
 * Security features:
 * - Values are never logged (only presence/absence)
 * - Warnings for missing sensitive variables
 * - Support for default values: ${ENV:VAR_NAME:default}
 */
public final class EnvResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/EnvResolver");
    
    // Pattern to match ${ENV:VAR_NAME} or ${ENV:VAR_NAME:default}
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{ENV:([A-Za-z0-9_]+)(?::([^}]*))?\\}");
    
    // Prefix for auto-override environment variables
    private static final String VOUCH_PREFIX = "VOUCH_";
    
    // Sensitive keys that should warn if using plain values
    private static final String[] SENSITIVE_KEYS = {
        "database.password",
        "database.user"
    };
    
    private EnvResolver() {
    }
    
    /**
     * Resolve a property value, checking for environment variable references.
     * 
     * @param key The property key (used for auto-override lookup)
     * @param value The raw value from properties file
     * @return The resolved value (with env vars substituted)
     */
    public static String resolve(String key, String value) {
        if (value == null) {
            return checkAutoOverride(key, null);
        }
        
        // First, check for auto-override
        String autoOverride = checkAutoOverride(key, value);
        if (autoOverride != null && !autoOverride.equals(value)) {
            return autoOverride;
        }
        
        // Then, resolve explicit ${ENV:...} patterns
        return resolveEnvPatterns(key, value);
    }
    
    /**
     * Check if an environment variable with VOUCH_ prefix exists for this key.
     * 
     * Mapping: database.password -> VOUCH_DATABASE_PASSWORD
     *          auth.session_duration -> VOUCH_AUTH_SESSION_DURATION
     */
    private static String checkAutoOverride(String key, String currentValue) {
        String envKey = keyToEnvName(key);
        String envValue = getEnvVar(envKey);
        
        if (envValue != null) {
            LOGGER.debug("Config '{}' overridden by environment variable {}", key, envKey);
            return envValue;
        }
        
        return currentValue;
    }
    
    /**
     * Convert a property key to environment variable name.
     * database.password -> VOUCH_DATABASE_PASSWORD
     */
    private static String keyToEnvName(String key) {
        return VOUCH_PREFIX + key.toUpperCase().replace('.', '_').replace('-', '_');
    }
    
    /**
     * Resolve explicit ${ENV:VAR_NAME} or ${ENV:VAR_NAME:default} patterns in a value.
     */
    private static String resolveEnvPatterns(String key, String value) {
        Matcher matcher = ENV_PATTERN.matcher(value);
        
        if (!matcher.find()) {
            // No env patterns found - check for sensitive plain values
            warnIfSensitivePlainValue(key, value);
            return value;
        }
        
        // Reset matcher and do full replacement
        matcher.reset();
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2); // May be null
            
            String envValue = getEnvVar(varName);
            
            if (envValue != null) {
                LOGGER.debug("Resolved ${ENV:{}} for config '{}'", varName, key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(envValue));
            } else if (defaultValue != null) {
                LOGGER.debug("Using default value for ${ENV:{}} in config '{}'", varName, key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(defaultValue));
            } else {
                // Keep original pattern if no value and no default
                LOGGER.warn("Environment variable {} not set for config '{}' (no default provided)", varName, key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Get an environment variable value.
     */
    private static String getEnvVar(String name) {
        String value = System.getenv(name);
        if (value != null && value.isEmpty()) {
            return null; // Treat empty as unset
        }
        return value;
    }
    
    /**
     * Warn if a sensitive config key has a plain-text value that's not empty.
     */
    private static void warnIfSensitivePlainValue(String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        
        for (String sensitiveKey : SENSITIVE_KEYS) {
            if (key.equals(sensitiveKey)) {
                LOGGER.warn("Config '{}' contains a plain-text value. Consider using ${ENV:...} or VOUCH_{} environment variable for security.",
                    key, key.toUpperCase().replace('.', '_'));
                return;
            }
        }
    }
    
    /**
     * Check if a value contains env variable references that weren't resolved.
     */
    public static boolean hasUnresolvedEnvVars(String value) {
        if (value == null) {
            return false;
        }
        return ENV_PATTERN.matcher(value).find();
    }
    
    /**
     * Get documentation comments for a config file explaining env var usage.
     */
    public static String getEnvVarDocumentation() {
        return """
            # ============================================================
            # ENVIRONMENT VARIABLE SUPPORT
            # ============================================================
            # Vouch supports secure configuration via environment variables:
            #
            # Method 1 - Explicit syntax in values:
            #   database.password=${ENV:MY_DB_PASSWORD}
            #   database.password=${ENV:MY_DB_PASSWORD:default_value}
            #
            # Method 2 - Auto-override with VOUCH_ prefix:
            #   Set VOUCH_DATABASE_PASSWORD to override database.password
            #   Set VOUCH_AUTH_SESSION_DURATION to override auth.session_duration
            #
            # The VOUCH_ prefix method takes priority over file values.
            # ============================================================
            """;
    }
    
    /**
     * Resolve all values in a map of properties.
     */
    public static void resolveAll(Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String resolved = resolve(entry.getKey(), entry.getValue());
            entry.setValue(resolved);
        }
    }
}
