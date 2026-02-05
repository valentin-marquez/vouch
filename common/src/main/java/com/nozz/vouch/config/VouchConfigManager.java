package com.nozz.vouch.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.nozz.vouch.auth.AuthMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unified configuration manager for Vouch using TOML format.
 * 
 * Configuration is stored in: config/vouch/vouch.toml
 * Languages are stored in: config/vouch/lang/{lang}.json
 * 
 * This replaces the previous VouchConfig (vouch.properties) and 
 * UXConfig (vouch-ux.properties) with a single, well-organized TOML file.
 * 
 * Environment variable support:
 * - Explicit: ${ENV:VARIABLE_NAME} or ${ENV:VARIABLE_NAME:default}
 * - Auto-override: VOUCH_DATABASE_PASSWORD overrides database.password
 */
public final class VouchConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Config");
    private static final String CONFIG_FILE = "vouch.toml";
    private static final String LANG_DIR = "lang";

    private static VouchConfigManager instance;
    private final Path configDir;
    private final Path configPath;
    private CommentedFileConfig config;

    private String brandingModName = "Vouch";
    private String brandingPrefix = "&8[&6Vouch&8]&r ";

    private String databaseType = "h2";
    private String databaseHost = "localhost";
    private int databasePort = 3306;
    private String databaseName = "vouch";
    private String databaseUser = "root";
    private String databasePassword = "";
    private int databasePoolMaxSize = 10;
    private int databasePoolMinIdle = 2;

    private AuthMode authMode = AuthMode.PASSWORD_OPTIONAL_2FA;
    private int loginTimeout = 60;
    private int maxLoginAttempts = 5;
    private int lockoutDuration = 300;
    private int passwordMinLength = 6;
    private int passwordMaxLength = 64;

    private boolean sessionPersistence = true;
    private int sessionDuration = 3600;
    private boolean sessionBindToIp = true;
    private boolean sessionBindToUuid = true;
    private int sessionCleanupInterval = 300;

    private boolean require2FAForOps = false;
    private String totpIssuer = "Vouch";
    private int totpWindowSize = 1;
    private int totpTimeStep = 30;

    private int argon2MemoryCost = 15360;  // 15 MiB
    private int argon2Iterations = 2;
    private int argon2Parallelism = 1;

    private String language = "en_us";

    private boolean titlesEnabled = true;
    private int titleFadeIn = 10;
    private int titleStay = 70;
    private int titleFadeOut = 20;
    private boolean errorTitlesEnabled = false;

    private boolean actionBarEnabled = true;

    private boolean bossBarEnabled = true;
    private String bossBarColor = "YELLOW";
    private String bossBarStyle = "PROGRESS";

    private int blindnessLevel = 1;
    private int slownessLevel = 0;
    private boolean hideFromOthers = true;
    private boolean hideFromTabList = true;
    private boolean freezePosition = true;
    private boolean freezeCamera = false;

    private boolean soundsEnabled = true;
    private String soundLoginSuccess = "minecraft:entity.player.levelup";
    private String soundRegisterSuccess = "minecraft:entity.experience_orb.pickup";
    private String soundWrongPassword = "minecraft:block.note_block.bass";
    private String soundAuthTimeout = "minecraft:entity.villager.no";
    private String soundRateLimited = "minecraft:block.anvil.land";
    private float soundVolume = 1.0f;
    private float soundPitch = 1.0f;

    private String colorPrimary = "&6";
    private String colorSuccess = "&a";
    private String colorError = "&c";
    private String colorInfo = "&e";
    private String colorMuted = "&7";

    private boolean showProcessingMessage = true;
    private boolean clearChatOnJoin = false;
    private int welcomeMessagePadding = 2;

    private VouchConfigManager(Path configDir) {
        this.configDir = configDir;
        this.configPath = configDir.resolve(CONFIG_FILE);
    }

    public static VouchConfigManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("VouchConfigManager not initialized! Call initialize() first.");
        }
        return instance;
    }

    /**
     * Initialize the config manager with the base config directory.
     * Creates config/vouch/ directory structure.
     */
    public static VouchConfigManager initialize(Path baseConfigDir) {
        if (instance == null) {
            Path vouchDir = baseConfigDir.resolve("vouch");
            instance = new VouchConfigManager(vouchDir);
            instance.ensureDirectoriesExist();
            instance.load();
        }
        return instance;
    }

    /**
     * Ensure config directories exist.
     */
    private void ensureDirectoriesExist() {
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(getLangDir());
        } catch (IOException e) {
            LOGGER.error("Failed to create config directories", e);
        }
    }


    /**
     * Load configuration from TOML file.
     */
    public void load() {
        if (Files.exists(configPath)) {
            try {
                config = CommentedFileConfig.builder(configPath)
                        .preserveInsertionOrder()
                        .build();
                config.load();
                loadFromConfig();
                LOGGER.info("Configuration loaded from {}", configPath);
            } catch (Exception e) {
                LOGGER.error("Failed to load config, using defaults", e);
                save();
            }
        } else {
            LOGGER.info("Config file not found, creating default config...");
            save();
        }
    }

    /**
     * Save configuration to TOML file with documentation.
     */
    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            
            config = CommentedFileConfig.builder(configPath)
                    .preserveInsertionOrder()
                    .build();
            
            saveToConfig();
            config.save();
            
            LOGGER.info("Configuration saved to {}", configPath);
        } catch (Exception e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * Reload configuration from file.
     */
    public void reload() {
        load();
        LOGGER.info("Configuration reloaded");
    }

    private void loadFromConfig() {
        // Branding
        brandingModName = resolveString("branding.mod_name", brandingModName);
        brandingPrefix = resolveString("branding.prefix", brandingPrefix);
        
        // Database
        databaseType = resolveString("database.type", databaseType);
        databaseHost = resolveString("database.host", databaseHost);
        databasePort = resolveInt("database.port", databasePort);
        databaseName = resolveString("database.name", databaseName);
        databaseUser = resolveString("database.user", databaseUser);
        databasePassword = resolveString("database.password", databasePassword);
        databasePoolMaxSize = resolveInt("database.pool.max_size", databasePoolMaxSize);
        databasePoolMinIdle = resolveInt("database.pool.min_idle", databasePoolMinIdle);
        
        // Authentication
        authMode = AuthMode.fromConfig(resolveString("auth.mode", authMode.getConfigValue()));
        loginTimeout = resolveInt("auth.login_timeout", loginTimeout);
        maxLoginAttempts = resolveInt("auth.max_attempts", maxLoginAttempts);
        lockoutDuration = resolveInt("auth.lockout_duration", lockoutDuration);
        passwordMinLength = resolveInt("auth.password_min_length", passwordMinLength);
        passwordMaxLength = resolveInt("auth.password_max_length", passwordMaxLength);
        
        // Session
        sessionPersistence = resolveBool("session.persistence", sessionPersistence);
        sessionDuration = resolveInt("session.duration", sessionDuration);
        sessionBindToIp = resolveBool("session.bind_to_ip", sessionBindToIp);
        sessionBindToUuid = resolveBool("session.bind_to_uuid", sessionBindToUuid);
        sessionCleanupInterval = resolveInt("session.cleanup_interval", sessionCleanupInterval);
        
        // 2FA
        require2FAForOps = resolveBool("totp.require_for_ops", require2FAForOps);
        totpIssuer = resolveString("totp.issuer", totpIssuer);
        totpWindowSize = resolveInt("totp.window_size", totpWindowSize);
        totpTimeStep = resolveInt("totp.time_step", totpTimeStep);
        
        // Cryptography
        argon2MemoryCost = resolveInt("crypto.argon2.memory_cost", argon2MemoryCost);
        argon2Iterations = resolveInt("crypto.argon2.iterations", argon2Iterations);
        argon2Parallelism = resolveInt("crypto.argon2.parallelism", argon2Parallelism);
        
        // Language
        language = resolveString("language", language);
        
        // UI - Titles
        titlesEnabled = resolveBool("ui.titles.enabled", titlesEnabled);
        titleFadeIn = resolveInt("ui.titles.fade_in", titleFadeIn);
        titleStay = resolveInt("ui.titles.stay", titleStay);
        titleFadeOut = resolveInt("ui.titles.fade_out", titleFadeOut);
        errorTitlesEnabled = resolveBool("ui.titles.errors_enabled", errorTitlesEnabled);
        
        // UI - ActionBar
        actionBarEnabled = resolveBool("ui.actionbar.enabled", actionBarEnabled);
        
        // UI - BossBar
        bossBarEnabled = resolveBool("ui.bossbar.enabled", bossBarEnabled);
        bossBarColor = resolveString("ui.bossbar.color", bossBarColor);
        bossBarStyle = resolveString("ui.bossbar.style", bossBarStyle);
        
        // UI - Effects
        blindnessLevel = resolveInt("ui.effects.blindness_level", blindnessLevel);
        slownessLevel = resolveInt("ui.effects.slowness_level", slownessLevel);
        hideFromOthers = resolveBool("ui.effects.hide_from_others", hideFromOthers);
        hideFromTabList = resolveBool("ui.effects.hide_from_tab_list", hideFromTabList);
        freezePosition = resolveBool("ui.effects.freeze_position", freezePosition);
        freezeCamera = resolveBool("ui.effects.freeze_camera", freezeCamera);
        
        // UI - Sounds
        soundsEnabled = resolveBool("ui.sounds.enabled", soundsEnabled);
        soundLoginSuccess = resolveString("ui.sounds.login_success", soundLoginSuccess);
        soundRegisterSuccess = resolveString("ui.sounds.register_success", soundRegisterSuccess);
        soundWrongPassword = resolveString("ui.sounds.wrong_password", soundWrongPassword);
        soundAuthTimeout = resolveString("ui.sounds.auth_timeout", soundAuthTimeout);
        soundRateLimited = resolveString("ui.sounds.rate_limited", soundRateLimited);
        soundVolume = resolveFloat("ui.sounds.volume", soundVolume);
        soundPitch = resolveFloat("ui.sounds.pitch", soundPitch);
        
        // UI - Colors
        colorPrimary = resolveString("ui.colors.primary", colorPrimary);
        colorSuccess = resolveString("ui.colors.success", colorSuccess);
        colorError = resolveString("ui.colors.error", colorError);
        colorInfo = resolveString("ui.colors.info", colorInfo);
        colorMuted = resolveString("ui.colors.muted", colorMuted);
        
        // Misc
        showProcessingMessage = resolveBool("misc.show_processing_message", showProcessingMessage);
        clearChatOnJoin = resolveBool("misc.clear_chat_on_join", clearChatOnJoin);
        welcomeMessagePadding = resolveInt("misc.welcome_message_padding", welcomeMessagePadding);
    }

    private void saveToConfig() {
        // Header comment
        config.setComment("branding", """
            ============================================================
            Vouch Authentication Mod Configuration
            ============================================================
            
            ENVIRONMENT VARIABLE SUPPORT
            For sensitive values like database.password, you can use:
            
              Method 1 - Explicit syntax:
                password = "${ENV:MY_DB_PASSWORD}"
                password = "${ENV:MY_DB_PASSWORD:default_value}"
            
              Method 2 - Auto-override with VOUCH_ prefix:
                Set VOUCH_DATABASE_PASSWORD env var to override database.password
                Set VOUCH_DATABASE_USER env var to override database.user
            
            ============================================================
            
            Branding - Customize how Vouch appears in messages""");
        
        config.set("branding.mod_name", brandingModName);
        config.setComment("branding.mod_name", "Display name in messages");
        config.set("branding.prefix", brandingPrefix);
        config.setComment("branding.prefix", "Message prefix (supports color codes: &0-&f, &l, &o, etc.)");
        
        // Database
        config.setComment("database", "Database configuration");
        config.set("database.type", databaseType);
        config.setComment("database.type", "Database type: h2 (default), sqlite, mysql, postgresql");
        config.set("database.host", databaseHost);
        config.set("database.port", databasePort);
        config.set("database.name", databaseName);
        config.set("database.user", databaseUser);
        config.set("database.password", databasePassword);
        config.setComment("database.password", "Supports ${ENV:VARIABLE} syntax for security");
        config.set("database.pool.max_size", databasePoolMaxSize);
        config.setComment("database.pool", "Connection pool settings (for MySQL/PostgreSQL)");
        config.set("database.pool.min_idle", databasePoolMinIdle);
        
        // Authentication
        config.setComment("auth", "Authentication settings");
        config.set("auth.mode", authMode.getConfigValue());
        config.setComment("auth.mode", "Mode: " + AuthMode.getValidValues());
        config.set("auth.login_timeout", loginTimeout);
        config.setComment("auth.login_timeout", "Seconds before kicking unauthenticated players");
        config.set("auth.max_attempts", maxLoginAttempts);
        config.setComment("auth.max_attempts", "Failed login attempts before lockout");
        config.set("auth.lockout_duration", lockoutDuration);
        config.setComment("auth.lockout_duration", "Lockout duration in seconds");
        config.set("auth.password_min_length", passwordMinLength);
        config.set("auth.password_max_length", passwordMaxLength);
        
        // Session
        config.setComment("session", "Session management");
        config.set("session.persistence", sessionPersistence);
        config.setComment("session.persistence", "Remember sessions across reconnects");
        config.set("session.duration", sessionDuration);
        config.setComment("session.duration", "Session validity in seconds (default: 1 hour)");
        config.set("session.bind_to_ip", sessionBindToIp);
        config.set("session.bind_to_uuid", sessionBindToUuid);
        config.set("session.cleanup_interval", sessionCleanupInterval);
        
        // TOTP (2FA)
        config.setComment("totp", "Two-Factor Authentication (TOTP) settings");
        config.set("totp.require_for_ops", require2FAForOps);
        config.setComment("totp.require_for_ops", "Require 2FA for server operators");
        config.set("totp.issuer", totpIssuer);
        config.setComment("totp.issuer", "Name shown in authenticator apps");
        config.set("totp.window_size", totpWindowSize);
        config.setComment("totp.window_size", "Time window tolerance (±1 = accept codes from 30s ago/ahead)");
        config.set("totp.time_step", totpTimeStep);
        config.setComment("totp.time_step", "TOTP time step in seconds (standard: 30)");
        
        // Cryptography
        config.setComment("crypto", "Cryptography settings (Argon2id password hashing)");
        config.set("crypto.argon2.memory_cost", argon2MemoryCost);
        config.setComment("crypto.argon2.memory_cost", "Memory cost in KiB (default: 15360 = 15 MiB)");
        config.set("crypto.argon2.iterations", argon2Iterations);
        config.setComment("crypto.argon2.iterations", "Time cost / iterations");
        config.set("crypto.argon2.parallelism", argon2Parallelism);
        
        // Language
        config.set("language", language);
        config.setComment("language", "Language code (e.g., en_us, es_mx). Files in config/vouch/lang/");
        
        // UI - Titles
        config.setComment("ui", "User Interface settings - visual feedback for players");
        config.setComment("ui.titles", "Title screen messages");
        config.set("ui.titles.enabled", titlesEnabled);
        config.set("ui.titles.fade_in", titleFadeIn);
        config.setComment("ui.titles.fade_in", "Fade in duration (ticks, 20 = 1 second)");
        config.set("ui.titles.stay", titleStay);
        config.set("ui.titles.fade_out", titleFadeOut);
        config.set("ui.titles.errors_enabled", errorTitlesEnabled);
        config.setComment("ui.titles.errors_enabled", "Show error messages as titles (e.g., wrong password)");
        
        // UI - ActionBar
        config.setComment("ui.actionbar", "Action bar (above hotbar) messages");
        config.set("ui.actionbar.enabled", actionBarEnabled);
        
        // UI - BossBar
        config.setComment("ui.bossbar", "Boss bar countdown display");
        config.set("ui.bossbar.enabled", bossBarEnabled);
        config.set("ui.bossbar.color", bossBarColor);
        config.setComment("ui.bossbar.color", "Colors: PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE");
        config.set("ui.bossbar.style", bossBarStyle);
        config.setComment("ui.bossbar.style", "Styles: PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20");
        
        // UI - Effects
        config.setComment("ui.effects", "Pre-auth player effects");
        config.set("ui.effects.blindness_level", blindnessLevel);
        config.setComment("ui.effects.blindness_level", "0 = disabled, 1-255 = effect level");
        config.set("ui.effects.slowness_level", slownessLevel);
        config.set("ui.effects.hide_from_others", hideFromOthers);
        config.setComment("ui.effects.hide_from_others", "Make player invisible to others during pre-auth");
        config.set("ui.effects.hide_from_tab_list", hideFromTabList);
        config.set("ui.effects.freeze_position", freezePosition);
        config.set("ui.effects.freeze_camera", freezeCamera);
        
        // UI - Sounds
        config.setComment("ui.sounds", "Sound effects");
        config.set("ui.sounds.enabled", soundsEnabled);
        config.set("ui.sounds.login_success", soundLoginSuccess);
        config.set("ui.sounds.register_success", soundRegisterSuccess);
        config.set("ui.sounds.wrong_password", soundWrongPassword);
        config.set("ui.sounds.auth_timeout", soundAuthTimeout);
        config.set("ui.sounds.rate_limited", soundRateLimited);
        config.set("ui.sounds.volume", soundVolume);
        config.set("ui.sounds.pitch", soundPitch);
        
        // UI - Colors
        config.setComment("ui.colors", "Theme colors (used in messages). Use & color codes.");
        config.set("ui.colors.primary", colorPrimary);
        config.setComment("ui.colors.primary", "Primary highlight color (default: &6 gold)");
        config.set("ui.colors.success", colorSuccess);
        config.setComment("ui.colors.success", "Success messages (default: &a green)");
        config.set("ui.colors.error", colorError);
        config.setComment("ui.colors.error", "Error messages (default: &c red)");
        config.set("ui.colors.info", colorInfo);
        config.setComment("ui.colors.info", "Info messages (default: &e yellow)");
        config.set("ui.colors.muted", colorMuted);
        config.setComment("ui.colors.muted", "Secondary/muted text (default: &7 gray)");
        
        // Misc
        config.setComment("misc", "Miscellaneous settings");
        config.set("misc.show_processing_message", showProcessingMessage);
        config.setComment("misc.show_processing_message", "Show 'Processing...' during async operations");
        config.set("misc.clear_chat_on_join", clearChatOnJoin);
        config.setComment("misc.clear_chat_on_join", "Clear chat before showing welcome message");
        config.set("misc.welcome_message_padding", welcomeMessagePadding);
        config.setComment("misc.welcome_message_padding", "Empty lines before welcome message");
    }

    private String resolveString(String path, String defaultValue) {
        String value = config.getOrElse(path, defaultValue);
        return EnvResolver.resolve(path.replace(".", "_"), value);
    }

    private int resolveInt(String path, int defaultValue) {
        try {
            Object value = config.get(path);
            if (value == null) return defaultValue;
            if (value instanceof Number num) return num.intValue();
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean resolveBool(String path, boolean defaultValue) {
        try {
            Object value = config.get(path);
            if (value == null) return defaultValue;
            if (value instanceof Boolean bool) return bool;
            return Boolean.parseBoolean(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private float resolveFloat(String path, float defaultValue) {
        try {
            Object value = config.get(path);
            if (value == null) return defaultValue;
            if (value instanceof Number num) return num.floatValue();
            return Float.parseFloat(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public Path getConfigDir() { return configDir; }
    public Path getLangDir() { return configDir.resolve(LANG_DIR); }

    // Branding
    public String getBrandingModName() { return brandingModName; }
    public String getBrandingPrefix() { return brandingPrefix; }

    // Database
    public String getDatabaseType() { return databaseType; }
    public String getDatabaseHost() { return databaseHost; }
    public int getDatabasePort() { return databasePort; }
    public String getDatabaseName() { return databaseName; }
    public String getDatabaseUser() { return databaseUser; }
    public String getDatabasePassword() { return databasePassword; }
    public int getDatabasePoolMaxSize() { return databasePoolMaxSize; }
    public int getDatabasePoolMinIdle() { return databasePoolMinIdle; }

    // Authentication
    public AuthMode getAuthMode() { return authMode; }
    public int getLoginTimeout() { return loginTimeout; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }
    public int getLockoutDuration() { return lockoutDuration; }
    public int getPasswordMinLength() { return passwordMinLength; }
    public int getPasswordMaxLength() { return passwordMaxLength; }

    // Session
    public boolean isSessionPersistenceEnabled() { return sessionPersistence; }
    public int getSessionDuration() { return sessionDuration; }
    public boolean isSessionBindToIp() { return sessionBindToIp; }
    public boolean isSessionBindToUuid() { return sessionBindToUuid; }
    public int getSessionCleanupInterval() { return sessionCleanupInterval; }

    // 2FA
    public boolean isRequire2FAForOps() { return require2FAForOps; }
    public String getTotpIssuer() { return totpIssuer; }
    public int getTotpWindowSize() { return totpWindowSize; }
    public int getTotpTimeStep() { return totpTimeStep; }
    public String getServerName() { return totpIssuer; }

    // Cryptography
    public int getArgon2MemoryCost() { return argon2MemoryCost; }
    public int getArgon2Iterations() { return argon2Iterations; }
    public int getArgon2Parallelism() { return argon2Parallelism; }

    // Language
    public String getLanguage() { return language; }

    // UI - Titles
    public boolean useTitles() { return titlesEnabled; }
    public int getTitleFadeIn() { return titleFadeIn; }
    public int getTitleStay() { return titleStay; }
    public int getTitleFadeOut() { return titleFadeOut; }
    public boolean useErrorTitles() { return errorTitlesEnabled; }

    // UI - ActionBar
    public boolean useActionBar() { return actionBarEnabled; }

    // UI - BossBar
    public boolean useBossBar() { return bossBarEnabled; }
    public String getBossBarColor() { return bossBarColor; }
    public String getBossBarStyle() { return bossBarStyle; }

    // UI - Effects
    public int getBlindnessLevel() { return blindnessLevel; }
    public int getSlownessLevel() { return slownessLevel; }
    public boolean hideFromOthers() { return hideFromOthers; }
    public boolean hideFromTabList() { return hideFromTabList; }
    public boolean freezePosition() { return freezePosition; }
    public boolean freezeCamera() { return freezeCamera; }

    // UI - Sounds
    public boolean useSounds() { return soundsEnabled; }
    public String getSoundLoginSuccess() { return soundLoginSuccess; }
    public String getSoundRegisterSuccess() { return soundRegisterSuccess; }
    public String getSoundWrongPassword() { return soundWrongPassword; }
    public String getSoundAuthTimeout() { return soundAuthTimeout; }
    public String getSoundRateLimited() { return soundRateLimited; }
    public float getSoundVolume() { return soundVolume; }
    public float getSoundPitch() { return soundPitch; }

    // UI - Colors
    public String getColorPrimary() { return colorPrimary; }
    public String getColorSuccess() { return colorSuccess; }
    public String getColorError() { return colorError; }
    public String getColorInfo() { return colorInfo; }
    public String getColorMuted() { return colorMuted; }

    // Misc
    public boolean showProcessingMessage() { return showProcessingMessage; }
    public boolean clearChatOnJoin() { return clearChatOnJoin; }
    public int getWelcomeMessagePadding() { return welcomeMessagePadding; }

    /**
     * Build JDBC URL based on database type.
     */
    public String getJdbcUrl(String dataDir) {
        return switch (databaseType.toLowerCase()) {
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    databaseHost, databasePort, databaseName);
            case "postgresql", "postgres" -> String.format("jdbc:postgresql://%s:%d/%s?sslmode=require",
                    databaseHost, databasePort, databaseName);
            case "sqlite" -> String.format("jdbc:sqlite:%s/vouch.db", dataDir);
            default -> String.format("jdbc:h2:%s/vouch;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", dataDir);
        };
    }
}
