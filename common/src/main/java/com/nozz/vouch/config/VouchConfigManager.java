package com.nozz.vouch.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.nozz.vouch.auth.AuthMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unified configuration manager for Vouch using TOML format.
 *
 * <p>Configuration is stored in: config/vouch/vouch.toml
 * Languages are stored in: config/vouch/lang/{lang}.json
 *
 * <h2>Handling of the config file</h2>
 * The file on disk belongs to the server administrator, not to this class:
 * <ul>
 *   <li>A file that cannot be parsed, or that holds a value of the wrong type, is
 *       <em>never</em> rewritten. Loading fails with a {@link ConfigException} and the
 *       mod refuses to start rather than booting on silently substituted defaults.</li>
 *   <li>Saving merges into the file that was loaded: existing values, existing comments,
 *       unknown keys and custom sections are all left untouched. Only keys that are
 *       missing are added, with their documentation comment.</li>
 *   <li>Because missing keys are added with their <em>default</em> value (never with the
 *       in-memory resolved value), a {@code ${ENV:...}} reference or a
 *       {@code VOUCH_*} environment override can never be written back to disk in
 *       cleartext.</li>
 *   <li>The previous file is copied to {@code vouch.toml.bak} before any write.</li>
 *   <li>Out-of-range values are clamped in memory and warned about; the file keeps
 *       whatever the administrator wrote.</li>
 * </ul>
 *
 * <h2>Environment variable support</h2>
 * <ul>
 *   <li>Explicit: {@code ${ENV:VARIABLE_NAME}} or {@code ${ENV:VARIABLE_NAME:default}}</li>
 *   <li>Auto-override: {@code VOUCH_DATABASE_PASSWORD} overrides {@code database.password}</li>
 * </ul>
 */
public final class VouchConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Config");
    private static final String CONFIG_FILE = "vouch.toml";
    private static final String BACKUP_FILE = "vouch.toml.bak";
    private static final String LANG_DIR = "lang";

    /**
     * Thrown when the configuration file cannot be read or holds values Vouch refuses
     * to guess at. An authentication mod must not boot on a config it could not read.
     */
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // Schema: default value + documentation comment for every known key.
    // The value's runtime class is also the key's expected type.
    // Insertion order is the order keys are written to a fresh file.
    // ------------------------------------------------------------------

    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> COMMENTS = new LinkedHashMap<>();
    private static final Map<String, String> SECTION_COMMENTS = new LinkedHashMap<>();

    private static void def(String path, Object defaultValue, String comment) {
        DEFAULTS.put(path, defaultValue);
        if (comment != null) {
            COMMENTS.put(path, comment);
        }
    }

    private static void section(String path, String comment) {
        SECTION_COMMENTS.put(path, comment);
    }

    /**
     * File banner. TOML requires top-level scalars before the first table, so the
     * writer hoists {@code language} to the top of the file - which makes its comment
     * the only place a header can live.
     */
    private static final String FILE_HEADER = """
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

        WHAT VOUCH WRITES HERE
        Your values, comments and any extra keys are never overwritten: on startup
        only missing settings are appended, and the previous file is copied to
        vouch.toml.bak first. A file Vouch cannot read stops the server instead of
        being replaced.

        ============================================================
        """;

    static {
        def("language", "en_us",
                FILE_HEADER + "\nLanguage code (e.g., en_us, es_mx). Files in config/vouch/lang/");

        section("branding", "Branding - Customize how Vouch appears in messages");
        def("branding.mod_name", "Vouch", "Display name in messages");
        def("branding.prefix", "&8[&6Vouch&8]&r ", "Message prefix (supports color codes: &0-&f, &l, &o, etc.)");

        section("database", "Database configuration");
        def("database.type", "h2", "Database type: h2 (default), mysql, postgresql");
        def("database.host", "localhost", null);
        def("database.port", 3306, "TCP port of the database server (1-65535)");
        def("database.name", "vouch", null);
        def("database.user", "root", null);
        def("database.password", "", "Supports ${ENV:VARIABLE} syntax for security");
        section("database.pool", "Connection pool settings (for MySQL/PostgreSQL)");
        def("database.pool.max_size", 10, "Maximum pooled connections (1-200). Must be at least 1.");
        def("database.pool.min_idle", 2, "Idle connections kept open (0 - max_size)");

        section("auth", "Authentication settings");
        def("auth.mode", AuthMode.PASSWORD_OPTIONAL_2FA.getConfigValue(), "Mode: " + AuthMode.getValidValues());
        def("auth.login_timeout", 60, "Seconds before kicking unauthenticated players (5-3600)");
        def("auth.max_attempts", 5, "Failed login attempts before lockout (1-100)");
        def("auth.lockout_duration", 300, "Lockout duration in seconds (0-86400)");
        def("auth.password_min_length", 6, "Minimum password length (1-128)");
        def("auth.password_max_length", 64, "Maximum password length (must be >= password_min_length, up to 1024)");
        def("auth.allowed_commands", List.of("login", "l", "register", "reg"),
                """
                Command roots an unauthenticated player is allowed to run, without the
                leading '/'. Every other command is blocked until the player logs in.
                Add aliases here if you rename or proxy the login commands.""");
        def("auth.premium_auto_login", false,
                "Auto-authenticate players with valid Mojang/Microsoft premium accounts.\n"
                        + "Requires server to be in online-mode=false. Players verified against Mojang API.\n"
                        + "If using BungeeCord/Velocity with IP forwarding, UUIDs are already forwarded and this still works.");
        def("auth.premium_auto_login_require_2fa", true,
                "If true, premium players who have 2FA enabled must still verify their 2FA code.\n"
                        + "Recommended to keep enabled for maximum security.");
        def("auth.premium_offline_by_default", true,
                "If true, ALL players are treated as offline by default (no encryption challenge).\n"
                        + "Players must run /vouch markAsOnline to opt into premium auto-login on their next join.\n"
                        + "Prevents 'Invalid session' errors for cracked clients using premium usernames.\n"
                        + "If false, any username that exists in the Mojang API will receive an encryption challenge.");
        def("auth.premium_fetch_skins", true,
                "Fetch the player's signed profile properties (skin and cape) from the Mojang\n"
                        + "session server during premium verification.\n"
                        + "Disable only if you serve skins from another source.");

        section("session", "Session management");
        def("session.persistence", true, "Remember sessions across reconnects");
        def("session.duration", 3600, "Session validity in seconds (60-2592000, default: 1 hour)");
        def("session.bind_to_ip", true, null);
        def("session.bind_to_uuid", true, null);
        def("session.cleanup_interval", 300, "Seconds between expired-session sweeps (10-86400)");

        section("totp", "Two-Factor Authentication (TOTP) settings");
        def("totp.require_for_ops", false, "Require 2FA for server operators");
        def("totp.issuer", "Vouch", "Name shown in authenticator apps");
        def("totp.window_size", 1, "Time window tolerance (±1 = accept codes from 30s ago/ahead, 0-10)");
        def("totp.time_step", 30, "TOTP time step in seconds (standard: 30, allowed 1-300)");

        section("crypto", "Cryptography settings (Argon2id password hashing)");
        def("crypto.argon2.memory_cost", 15360, "Memory cost in KiB (default: 15360 = 15 MiB, allowed 1024-1048576)");
        def("crypto.argon2.iterations", 2, "Time cost / iterations (1-100)");
        def("crypto.argon2.parallelism", 1, "Parallel lanes (1-64)");

        section("ui", "User Interface settings - visual feedback for players");
        section("ui.titles", "Title screen messages");
        def("ui.titles.enabled", true, null);
        def("ui.titles.fade_in", 10, "Fade in duration (ticks, 20 = 1 second, 0-1200)");
        def("ui.titles.stay", 70, "Time on screen (ticks, 0-1200)");
        def("ui.titles.fade_out", 20, "Fade out duration (ticks, 0-1200)");
        def("ui.titles.errors_enabled", false, "Show error messages as titles (e.g., wrong password)");

        section("ui.actionbar", "Action bar (above hotbar) messages");
        def("ui.actionbar.enabled", true, null);

        section("ui.bossbar", "Boss bar countdown display");
        def("ui.bossbar.enabled", true, null);
        def("ui.bossbar.color", "YELLOW", "Colors: PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE");
        def("ui.bossbar.style", "PROGRESS", "Styles: PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20");

        section("ui.effects", "Pre-auth player effects");
        def("ui.effects.blindness_level", 1, "0 = disabled, 1-255 = effect level");
        def("ui.effects.slowness_level", 0, "0 = disabled, 1-255 = effect level");
        def("ui.effects.hide_from_others", true, "Make player invisible to others during pre-auth");
        def("ui.effects.hide_from_tab_list", true, null);
        def("ui.effects.freeze_position", true, null);
        def("ui.effects.freeze_camera", false, null);

        section("ui.sounds", "Sound effects");
        def("ui.sounds.enabled", true, null);
        def("ui.sounds.login_success", "minecraft:entity.player.levelup", null);
        def("ui.sounds.register_success", "minecraft:entity.experience_orb.pickup", null);
        def("ui.sounds.wrong_password", "minecraft:block.note_block.bass", null);
        def("ui.sounds.auth_timeout", "minecraft:entity.villager.no", null);
        def("ui.sounds.rate_limited", "minecraft:block.anvil.land", null);
        def("ui.sounds.volume", 1.0f, "Playback volume (0.0-10.0)");
        def("ui.sounds.pitch", 1.0f, "Playback pitch (0.5-2.0)");

        section("ui.colors", "Theme colors (used in messages). Use & color codes.");
        def("ui.colors.primary", "&6", "Primary highlight color (default: &6 gold)");
        def("ui.colors.success", "&a", "Success messages (default: &a green)");
        def("ui.colors.error", "&c", "Error messages (default: &c red)");
        def("ui.colors.info", "&e", "Info messages (default: &e yellow)");
        def("ui.colors.muted", "&7", "Secondary/muted text (default: &7 gray)");

        section("misc", "Miscellaneous settings");
        def("misc.show_processing_message", true, "Show 'Processing...' during async operations");
        def("misc.clear_chat_on_join", false, "Clear chat before showing welcome message");
        def("misc.welcome_message_padding", 2, "Empty lines before welcome message (0-20)");
    }

    /** Database types Vouch actually bundles a driver for. */
    private static final Set<String> SUPPORTED_DATABASE_TYPES =
            Set.of("h2", "mysql", "postgresql", "postgres");

    private static final Set<String> BOSS_BAR_COLORS =
            Set.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");

    private static final Set<String> BOSS_BAR_STYLES =
            Set.of("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20");

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private static VouchConfigManager instance;
    private final Path configDir;
    private final Path configPath;
    private final Path backupPath;
    private CommentedFileConfig config;

    private String brandingModName;
    private String brandingPrefix;

    private String databaseType;
    private String databaseHost;
    private int databasePort;
    private String databaseName;
    private String databaseUser;
    private String databasePassword;
    private int databasePoolMaxSize;
    private int databasePoolMinIdle;

    private AuthMode authMode;
    private int loginTimeout;
    private int maxLoginAttempts;
    private int lockoutDuration;
    private int passwordMinLength;
    private int passwordMaxLength;
    private List<String> allowedCommands;
    private Set<String> allowedCommandSet;
    private boolean premiumAutoLogin;
    private boolean premiumAutoLoginRequire2FA;
    private boolean premiumOfflineByDefault;
    private boolean premiumFetchSkins;

    private boolean sessionPersistence;
    private int sessionDuration;
    private boolean sessionBindToIp;
    private boolean sessionBindToUuid;
    private int sessionCleanupInterval;

    private boolean require2FAForOps;
    private String totpIssuer;
    private int totpWindowSize;
    private int totpTimeStep;

    private int argon2MemoryCost;
    private int argon2Iterations;
    private int argon2Parallelism;

    private String language;

    private boolean titlesEnabled;
    private int titleFadeIn;
    private int titleStay;
    private int titleFadeOut;
    private boolean errorTitlesEnabled;

    private boolean actionBarEnabled;

    private boolean bossBarEnabled;
    private String bossBarColor;
    private String bossBarStyle;

    private int blindnessLevel;
    private int slownessLevel;
    private boolean hideFromOthers;
    private boolean hideFromTabList;
    private boolean freezePosition;
    private boolean freezeCamera;

    private boolean soundsEnabled;
    private String soundLoginSuccess;
    private String soundRegisterSuccess;
    private String soundWrongPassword;
    private String soundAuthTimeout;
    private String soundRateLimited;
    private float soundVolume;
    private float soundPitch;

    private String colorPrimary;
    private String colorSuccess;
    private String colorError;
    private String colorInfo;
    private String colorMuted;

    private boolean showProcessingMessage;
    private boolean clearChatOnJoin;
    private int welcomeMessagePadding;

    private VouchConfigManager(Path configDir) {
        this.configDir = configDir;
        this.configPath = configDir.resolve(CONFIG_FILE);
        this.backupPath = configDir.resolve(BACKUP_FILE);
        resetToDefaults();
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
     *
     * @throws ConfigException if the configuration file exists but cannot be read.
     *                         The singleton is left uninitialized so nothing boots on
     *                         a configuration Vouch could not understand.
     */
    public static VouchConfigManager initialize(Path baseConfigDir) {
        if (instance != null) {
            return instance;
        }
        VouchConfigManager manager = new VouchConfigManager(baseConfigDir.resolve("vouch"));
        manager.ensureDirectoriesExist();
        manager.load();
        instance = manager;
        return instance;
    }

    /**
     * Drop the singleton so a fresh instance can be created. Test hook only.
     */
    static void resetForTesting() {
        if (instance != null) {
            instance.closeFileConfig();
            instance = null;
        }
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

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Load configuration from the TOML file.
     *
     * <p>If the file cannot be parsed, or holds a value of the wrong type, this throws
     * and the file is left exactly as it was. The previously loaded values (if any)
     * stay live, so a failed {@code /vouch admin reload} is a no-op rather than a
     * silent reset to defaults.
     */
    public void load() {
        ensureDirectoriesExist();

        boolean existedBefore = Files.exists(configPath);

        CommentedFileConfig loaded = buildFileConfig();
        try {
            loaded.load();
        } catch (Exception e) {
            closeQuietly(loaded);
            LOGGER.error("Could not parse {} - your file has NOT been modified. "
                            + "Fix the syntax error, or move the file aside to regenerate defaults.",
                    configPath, e);
            throw new ConfigException("Could not parse configuration file " + configPath, e);
        }

        List<String> errors = collectValueErrors(loaded);
        if (!errors.isEmpty()) {
            closeQuietly(loaded);
            for (String error : errors) {
                LOGGER.error("Invalid configuration: {}", error);
            }
            LOGGER.error("{} refuses to start on a configuration it cannot read. "
                    + "Your file at {} has NOT been modified.", "Vouch", configPath);
            throw new ConfigException("Invalid configuration in " + configPath + ": " + errors.get(0)
                    + (errors.size() > 1 ? " (and " + (errors.size() - 1) + " more)" : ""));
        }

        // Past this point nothing can fail, so the live configuration is replaced atomically.
        closeFileConfig();
        this.config = loaded;

        resetToDefaults();
        loadFromConfig();
        validate();

        if (existedBefore) {
            LOGGER.info("Configuration loaded from {}", configPath);
        } else {
            LOGGER.info("Config file not found, creating default config at {}", configPath);
        }

        writeMissingDefaults();
    }

    /**
     * Reload configuration from file.
     *
     * @throws ConfigException if the file is unreadable. The previously loaded values
     *                         remain in effect.
     */
    public void reload() {
        load();
        LOGGER.info("Configuration reloaded");
    }

    private CommentedFileConfig buildFileConfig() {
        return CommentedFileConfig.builder(configPath)
                .preserveInsertionOrder()
                // Synchronous: no background writer thread to leak, and save() is durable
                // by the time it returns.
                .sync()
                .build();
    }

    private void closeFileConfig() {
        if (config != null) {
            closeQuietly(config);
            config = null;
        }
    }

    private static void closeQuietly(CommentedFileConfig fileConfig) {
        try {
            fileConfig.close();
        } catch (Exception e) {
            LOGGER.debug("Failed to close config handle", e);
        }
    }

    // ------------------------------------------------------------------
    // Strict pre-validation: runs before a single field is touched
    // ------------------------------------------------------------------

    private List<String> collectValueErrors(CommentedFileConfig loaded) {
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            String path = entry.getKey();
            Object expected = entry.getValue();

            Object raw;
            try {
                raw = loaded.get(path);
            } catch (Exception e) {
                errors.add("'" + path + "' cannot be read (its parent is not a table): " + e.getMessage());
                continue;
            }
            if (raw != null) {
                checkValue(errors, path, raw, expected, "vouch.toml");
            }

            String env = EnvResolver.resolve(path, null);
            if (env != null) {
                checkValue(errors, path, env, expected, "environment variable");
            }
        }

        checkEnumValue(errors, loaded, "database.type", SUPPORTED_DATABASE_TYPES, true,
                "sqlite is no longer supported: the driver is not bundled with Vouch");
        checkAuthMode(errors, loaded);

        return errors;
    }

    private void checkValue(List<String> errors, String path, Object raw, Object expected, String source) {
        if (expected instanceof String) {
            if (!(raw instanceof String)) {
                errors.add("'" + path + "' in " + source + " must be a quoted string, but was "
                        + describe(raw) + ". Write it as " + path.substring(path.lastIndexOf('.') + 1)
                        + " = \"" + raw + "\"");
            }
        } else if (expected instanceof Integer) {
            if (!isIntegral(raw)) {
                errors.add("'" + path + "' in " + source + " must be a whole number, but was " + describe(raw));
            }
        } else if (expected instanceof Boolean) {
            if (!isBoolean(raw)) {
                errors.add("'" + path + "' in " + source + " must be true or false, but was " + describe(raw));
            }
        } else if (expected instanceof Float) {
            if (!isNumeric(raw)) {
                errors.add("'" + path + "' in " + source + " must be a number, but was " + describe(raw));
            }
        } else if (expected instanceof List) {
            if (raw instanceof String) {
                return; // environment variables arrive as a comma-separated string
            }
            if (!(raw instanceof List<?> list)) {
                errors.add("'" + path + "' in " + source + " must be a list of quoted strings, but was "
                        + describe(raw));
                return;
            }
            for (Object element : list) {
                if (!(element instanceof String)) {
                    errors.add("'" + path + "' in " + source + " must contain only quoted strings, but held "
                            + describe(element));
                    return;
                }
            }
        }
    }

    private void checkEnumValue(List<String> errors, CommentedFileConfig loaded, String path,
                                Set<String> allowed, boolean lowercase, String extraHint) {
        String value = rawStringOrNull(loaded, path);
        if (value == null) {
            return;
        }
        String normalized = lowercase ? value.trim().toLowerCase(Locale.ROOT) : value.trim().toUpperCase(Locale.ROOT);
        if (allowed.contains(normalized)) {
            return;
        }
        String message = "'" + path + "' is set to \"" + value + "\", which Vouch does not support. Valid values: "
                + String.join(", ", new java.util.TreeSet<>(allowed));
        if (extraHint != null) {
            message += ". " + extraHint;
        }
        errors.add(message);
    }

    private void checkAuthMode(List<String> errors, CommentedFileConfig loaded) {
        String value = rawStringOrNull(loaded, "auth.mode");
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AuthMode mode : AuthMode.values()) {
            if (mode.getConfigValue().equals(normalized) || mode.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return;
            }
        }
        errors.add("'auth.mode' is set to \"" + value + "\", which is not a known authentication mode. "
                + "Valid values: " + AuthMode.getValidValues()
                + ". Vouch will not silently fall back, because that would change how players authenticate");
    }

    /** Raw string for a path, honouring env overrides; null when unset or not a string. */
    private String rawStringOrNull(CommentedFileConfig loaded, String path) {
        String env = EnvResolver.resolve(path, null);
        if (env != null) {
            return env;
        }
        Object raw;
        try {
            raw = loaded.get(path);
        } catch (Exception e) {
            return null;
        }
        return raw instanceof String s ? s : null;
    }

    private static boolean isIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return true;
        }
        if (value instanceof String s) {
            try {
                Integer.parseInt(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String s) {
            try {
                Float.parseFloat(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isBoolean(Object value) {
        if (value instanceof Boolean) {
            return true;
        }
        if (value instanceof String s) {
            String t = s.trim();
            return t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false");
        }
        return false;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "empty";
        }
        String type = switch (value) {
            case String ignored -> "a string";
            case Boolean ignored -> "a boolean";
            case Number ignored -> "a number";
            case List<?> ignored -> "a list";
            default -> "a " + value.getClass().getSimpleName();
        };
        return type + " (" + value + ")";
    }

    // ------------------------------------------------------------------
    // Field population
    // ------------------------------------------------------------------

    private void resetToDefaults() {
        brandingModName = str("branding.mod_name");
        brandingPrefix = str("branding.prefix");

        databaseType = str("database.type");
        databaseHost = str("database.host");
        databasePort = num("database.port");
        databaseName = str("database.name");
        databaseUser = str("database.user");
        databasePassword = str("database.password");
        databasePoolMaxSize = num("database.pool.max_size");
        databasePoolMinIdle = num("database.pool.min_idle");

        authMode = AuthMode.fromConfig(str("auth.mode"));
        loginTimeout = num("auth.login_timeout");
        maxLoginAttempts = num("auth.max_attempts");
        lockoutDuration = num("auth.lockout_duration");
        passwordMinLength = num("auth.password_min_length");
        passwordMaxLength = num("auth.password_max_length");
        setAllowedCommands(defaultCommandList());
        premiumAutoLogin = flag("auth.premium_auto_login");
        premiumAutoLoginRequire2FA = flag("auth.premium_auto_login_require_2fa");
        premiumOfflineByDefault = flag("auth.premium_offline_by_default");
        premiumFetchSkins = flag("auth.premium_fetch_skins");

        sessionPersistence = flag("session.persistence");
        sessionDuration = num("session.duration");
        sessionBindToIp = flag("session.bind_to_ip");
        sessionBindToUuid = flag("session.bind_to_uuid");
        sessionCleanupInterval = num("session.cleanup_interval");

        require2FAForOps = flag("totp.require_for_ops");
        totpIssuer = str("totp.issuer");
        totpWindowSize = num("totp.window_size");
        totpTimeStep = num("totp.time_step");

        argon2MemoryCost = num("crypto.argon2.memory_cost");
        argon2Iterations = num("crypto.argon2.iterations");
        argon2Parallelism = num("crypto.argon2.parallelism");

        language = str("language");

        titlesEnabled = flag("ui.titles.enabled");
        titleFadeIn = num("ui.titles.fade_in");
        titleStay = num("ui.titles.stay");
        titleFadeOut = num("ui.titles.fade_out");
        errorTitlesEnabled = flag("ui.titles.errors_enabled");

        actionBarEnabled = flag("ui.actionbar.enabled");

        bossBarEnabled = flag("ui.bossbar.enabled");
        bossBarColor = str("ui.bossbar.color");
        bossBarStyle = str("ui.bossbar.style");

        blindnessLevel = num("ui.effects.blindness_level");
        slownessLevel = num("ui.effects.slowness_level");
        hideFromOthers = flag("ui.effects.hide_from_others");
        hideFromTabList = flag("ui.effects.hide_from_tab_list");
        freezePosition = flag("ui.effects.freeze_position");
        freezeCamera = flag("ui.effects.freeze_camera");

        soundsEnabled = flag("ui.sounds.enabled");
        soundLoginSuccess = str("ui.sounds.login_success");
        soundRegisterSuccess = str("ui.sounds.register_success");
        soundWrongPassword = str("ui.sounds.wrong_password");
        soundAuthTimeout = str("ui.sounds.auth_timeout");
        soundRateLimited = str("ui.sounds.rate_limited");
        soundVolume = dec("ui.sounds.volume");
        soundPitch = dec("ui.sounds.pitch");

        colorPrimary = str("ui.colors.primary");
        colorSuccess = str("ui.colors.success");
        colorError = str("ui.colors.error");
        colorInfo = str("ui.colors.info");
        colorMuted = str("ui.colors.muted");

        showProcessingMessage = flag("misc.show_processing_message");
        clearChatOnJoin = flag("misc.clear_chat_on_join");
        welcomeMessagePadding = num("misc.welcome_message_padding");
    }

    private void loadFromConfig() {
        brandingModName = resolveString("branding.mod_name");
        brandingPrefix = resolveString("branding.prefix");

        databaseType = resolveString("database.type");
        databaseHost = resolveString("database.host");
        databasePort = resolveInt("database.port");
        databaseName = resolveString("database.name");
        databaseUser = resolveString("database.user");
        databasePassword = resolveString("database.password");
        databasePoolMaxSize = resolveInt("database.pool.max_size");
        databasePoolMinIdle = resolveInt("database.pool.min_idle");

        authMode = AuthMode.fromConfig(resolveString("auth.mode"));
        loginTimeout = resolveInt("auth.login_timeout");
        maxLoginAttempts = resolveInt("auth.max_attempts");
        lockoutDuration = resolveInt("auth.lockout_duration");
        passwordMinLength = resolveInt("auth.password_min_length");
        passwordMaxLength = resolveInt("auth.password_max_length");
        setAllowedCommands(resolveStringList("auth.allowed_commands"));
        premiumAutoLogin = resolveBool("auth.premium_auto_login");
        premiumAutoLoginRequire2FA = resolveBool("auth.premium_auto_login_require_2fa");
        premiumOfflineByDefault = resolveBool("auth.premium_offline_by_default");
        premiumFetchSkins = resolveBool("auth.premium_fetch_skins");

        sessionPersistence = resolveBool("session.persistence");
        sessionDuration = resolveInt("session.duration");
        sessionBindToIp = resolveBool("session.bind_to_ip");
        sessionBindToUuid = resolveBool("session.bind_to_uuid");
        sessionCleanupInterval = resolveInt("session.cleanup_interval");

        require2FAForOps = resolveBool("totp.require_for_ops");
        totpIssuer = resolveString("totp.issuer");
        totpWindowSize = resolveInt("totp.window_size");
        totpTimeStep = resolveInt("totp.time_step");

        argon2MemoryCost = resolveInt("crypto.argon2.memory_cost");
        argon2Iterations = resolveInt("crypto.argon2.iterations");
        argon2Parallelism = resolveInt("crypto.argon2.parallelism");

        language = resolveString("language");

        titlesEnabled = resolveBool("ui.titles.enabled");
        titleFadeIn = resolveInt("ui.titles.fade_in");
        titleStay = resolveInt("ui.titles.stay");
        titleFadeOut = resolveInt("ui.titles.fade_out");
        errorTitlesEnabled = resolveBool("ui.titles.errors_enabled");

        actionBarEnabled = resolveBool("ui.actionbar.enabled");

        bossBarEnabled = resolveBool("ui.bossbar.enabled");
        bossBarColor = resolveString("ui.bossbar.color");
        bossBarStyle = resolveString("ui.bossbar.style");

        blindnessLevel = resolveInt("ui.effects.blindness_level");
        slownessLevel = resolveInt("ui.effects.slowness_level");
        hideFromOthers = resolveBool("ui.effects.hide_from_others");
        hideFromTabList = resolveBool("ui.effects.hide_from_tab_list");
        freezePosition = resolveBool("ui.effects.freeze_position");
        freezeCamera = resolveBool("ui.effects.freeze_camera");

        soundsEnabled = resolveBool("ui.sounds.enabled");
        soundLoginSuccess = resolveString("ui.sounds.login_success");
        soundRegisterSuccess = resolveString("ui.sounds.register_success");
        soundWrongPassword = resolveString("ui.sounds.wrong_password");
        soundAuthTimeout = resolveString("ui.sounds.auth_timeout");
        soundRateLimited = resolveString("ui.sounds.rate_limited");
        soundVolume = resolveFloat("ui.sounds.volume");
        soundPitch = resolveFloat("ui.sounds.pitch");

        colorPrimary = resolveString("ui.colors.primary");
        colorSuccess = resolveString("ui.colors.success");
        colorError = resolveString("ui.colors.error");
        colorInfo = resolveString("ui.colors.info");
        colorMuted = resolveString("ui.colors.muted");

        showProcessingMessage = resolveBool("misc.show_processing_message");
        clearChatOnJoin = resolveBool("misc.clear_chat_on_join");
        welcomeMessagePadding = resolveInt("misc.welcome_message_padding");
    }

    // ------------------------------------------------------------------
    // Validation: clamp to documented ranges, naming every value we corrected
    // ------------------------------------------------------------------

    private void validate() {
        databasePort = clampInt("database.port", databasePort, 1, 65535);
        databasePoolMaxSize = clampInt("database.pool.max_size", databasePoolMaxSize, 1, 200);
        databasePoolMinIdle = clampInt("database.pool.min_idle", databasePoolMinIdle, 0, databasePoolMaxSize);

        loginTimeout = clampInt("auth.login_timeout", loginTimeout, 5, 3600);
        maxLoginAttempts = clampInt("auth.max_attempts", maxLoginAttempts, 1, 100);
        lockoutDuration = clampInt("auth.lockout_duration", lockoutDuration, 0, 86400);
        passwordMinLength = clampInt("auth.password_min_length", passwordMinLength, 1, 128);
        passwordMaxLength = clampInt("auth.password_max_length", passwordMaxLength, 1, 1024);
        if (passwordMaxLength < passwordMinLength) {
            LOGGER.warn("Config 'auth.password_max_length' is {} but 'auth.password_min_length' is {}; "
                            + "nobody could register. Raising password_max_length to {}.",
                    passwordMaxLength, passwordMinLength, passwordMinLength);
            passwordMaxLength = passwordMinLength;
        }

        sessionDuration = clampInt("session.duration", sessionDuration, 60, 2592000);
        sessionCleanupInterval = clampInt("session.cleanup_interval", sessionCleanupInterval, 10, 86400);

        totpWindowSize = clampInt("totp.window_size", totpWindowSize, 0, 10);
        totpTimeStep = clampInt("totp.time_step", totpTimeStep, 1, 300);

        argon2MemoryCost = clampInt("crypto.argon2.memory_cost", argon2MemoryCost, 1024, 1048576);
        argon2Iterations = clampInt("crypto.argon2.iterations", argon2Iterations, 1, 100);
        argon2Parallelism = clampInt("crypto.argon2.parallelism", argon2Parallelism, 1, 64);

        titleFadeIn = clampInt("ui.titles.fade_in", titleFadeIn, 0, 1200);
        titleStay = clampInt("ui.titles.stay", titleStay, 0, 1200);
        titleFadeOut = clampInt("ui.titles.fade_out", titleFadeOut, 0, 1200);

        blindnessLevel = clampInt("ui.effects.blindness_level", blindnessLevel, 0, 255);
        slownessLevel = clampInt("ui.effects.slowness_level", slownessLevel, 0, 255);

        soundVolume = clampFloat("ui.sounds.volume", soundVolume, 0.0f, 10.0f);
        soundPitch = clampFloat("ui.sounds.pitch", soundPitch, 0.5f, 2.0f);

        welcomeMessagePadding = clampInt("misc.welcome_message_padding", welcomeMessagePadding, 0, 20);

        bossBarColor = validateChoice("ui.bossbar.color", bossBarColor.toUpperCase(Locale.ROOT),
                BOSS_BAR_COLORS, str("ui.bossbar.color"));
        bossBarStyle = validateChoice("ui.bossbar.style", bossBarStyle.toUpperCase(Locale.ROOT),
                BOSS_BAR_STYLES, str("ui.bossbar.style"));

        if (language == null || language.isBlank()) {
            LOGGER.warn("Config 'language' is empty; using '{}'.", str("language"));
            language = str("language");
        }

        if (allowedCommands.isEmpty()) {
            LOGGER.warn("Config 'auth.allowed_commands' resolved to an empty list; unauthenticated players "
                    + "would have no way to log in. Using the defaults {} instead.", defaultCommandList());
            setAllowedCommands(defaultCommandList());
        }
    }

    private int clampInt(String path, int value, int min, int max) {
        if (value < min) {
            LOGGER.warn("Config '{}' is {}, below the minimum of {}. Using {}.", path, value, min, min);
            return min;
        }
        if (value > max) {
            LOGGER.warn("Config '{}' is {}, above the maximum of {}. Using {}.", path, value, max, max);
            return max;
        }
        return value;
    }

    private float clampFloat(String path, float value, float min, float max) {
        if (Float.isNaN(value)) {
            LOGGER.warn("Config '{}' is not a number. Using {}.", path, min);
            return min;
        }
        if (value < min) {
            LOGGER.warn("Config '{}' is {}, below the minimum of {}. Using {}.", path, value, min, min);
            return min;
        }
        if (value > max) {
            LOGGER.warn("Config '{}' is {}, above the maximum of {}. Using {}.", path, value, max, max);
            return max;
        }
        return value;
    }

    private String validateChoice(String path, String value, Set<String> allowed, String fallback) {
        if (allowed.contains(value)) {
            return value;
        }
        LOGGER.warn("Config '{}' is '{}', which is not one of {}. Using '{}'.",
                path, value, new java.util.TreeSet<>(allowed), fallback);
        return fallback;
    }

    // ------------------------------------------------------------------
    // Saving: merge, never replace
    // ------------------------------------------------------------------

    /**
     * Write any missing keys (and their documentation) into the config file.
     *
     * <p>Values already present in the file - including comments, unknown keys and
     * whole custom sections - are left exactly as the administrator wrote them.
     * Missing keys are added with their <em>default</em> value, so a resolved
     * {@code ${ENV:...}} secret is never written to disk. The previous file is copied
     * to {@code vouch.toml.bak} first.
     */
    public void save() {
        if (config == null) {
            throw new IllegalStateException("Configuration has not been loaded; refusing to write "
                    + configPath + " from an unknown state.");
        }
        writeMissingDefaults();
    }

    private void writeMissingDefaults() {
        int added = applyMissingDefaults();
        if (added == 0 && Files.exists(configPath)) {
            LOGGER.debug("Configuration file is complete, nothing to write");
            return;
        }

        backupExistingFile();
        try {
            config.save();
        } catch (Exception e) {
            LOGGER.error("Failed to write {} (a copy of the previous file is at {})", configPath, backupPath, e);
            return;
        }
        if (added > 0) {
            LOGGER.info("Added {} missing setting(s) to {}", added, configPath);
        }
        LOGGER.info("Configuration saved to {}", configPath);
    }

    /**
     * @return how many entries or comments were added.
     */
    private int applyMissingDefaults() {
        int added = 0;
        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            String path = entry.getKey();
            if (!config.contains(path)) {
                config.set(path, entry.getValue());
                added++;
            }
            String comment = COMMENTS.get(path);
            if (comment != null && config.getComment(path) == null) {
                config.setComment(path, comment);
                added++;
            }
        }
        // Section comments last: the sub-tables must exist before they can be commented.
        for (Map.Entry<String, String> entry : SECTION_COMMENTS.entrySet()) {
            String path = entry.getKey();
            if (config.contains(path) && config.getComment(path) == null) {
                config.setComment(path, entry.getValue());
                added++;
            }
        }
        return added;
    }

    private void backupExistingFile() {
        try {
            if (Files.exists(configPath) && Files.size(configPath) > 0) {
                Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not back up {} to {}", configPath, backupPath, e);
        }
    }

    // ------------------------------------------------------------------
    // Value resolution (types already verified by collectValueErrors)
    // ------------------------------------------------------------------

    private static String str(String path) {
        return (String) DEFAULTS.get(path);
    }

    private static int num(String path) {
        return (Integer) DEFAULTS.get(path);
    }

    private static boolean flag(String path) {
        return (Boolean) DEFAULTS.get(path);
    }

    private static float dec(String path) {
        return (Float) DEFAULTS.get(path);
    }

    @SuppressWarnings("unchecked")
    private static List<String> defaultCommandList() {
        return (List<String>) DEFAULTS.get("auth.allowed_commands");
    }

    private String resolveString(String path) {
        Object raw = config.get(path);
        String value = raw instanceof String s ? s : str(path);
        return EnvResolver.resolve(path, value);
    }

    private int resolveInt(String path) {
        String env = EnvResolver.resolve(path, null);
        if (env != null) {
            return Integer.parseInt(env.trim());
        }
        Object value = config.get(path);
        if (value == null) {
            return num(path);
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }

    private boolean resolveBool(String path) {
        String env = EnvResolver.resolve(path, null);
        if (env != null) {
            return Boolean.parseBoolean(env.trim());
        }
        Object value = config.get(path);
        if (value == null) {
            return flag(path);
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    private float resolveFloat(String path) {
        String env = EnvResolver.resolve(path, null);
        if (env != null) {
            return Float.parseFloat(env.trim());
        }
        Object value = config.get(path);
        if (value == null) {
            return dec(path);
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return Float.parseFloat(value.toString().trim());
    }

    private List<String> resolveStringList(String path) {
        String env = EnvResolver.resolve(path, null);
        if (env != null) {
            return Arrays.asList(env.split(","));
        }
        Object value = config.get(path);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add((String) element);
            }
            return result;
        }
        return defaultCommandList();
    }

    /** Normalize (lowercase, no leading slash, no blanks) and index for lookup. */
    private void setAllowedCommands(List<String> raw) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String entry : raw) {
            String command = normalizeCommandRoot(entry);
            if (!command.isEmpty()) {
                normalized.add(command);
            }
        }
        this.allowedCommands = List.copyOf(normalized);
        this.allowedCommandSet = Collections.unmodifiableSet(normalized);
    }

    private static String normalizeCommandRoot(String value) {
        if (value == null) {
            return "";
        }
        String command = value.trim().toLowerCase(Locale.ROOT);
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        return command.trim();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

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
    public boolean isPremiumAutoLogin() { return premiumAutoLogin; }
    public boolean isPremiumAutoLoginRequire2FA() { return premiumAutoLoginRequire2FA; }
    public boolean isPremiumOfflineByDefault() { return premiumOfflineByDefault; }
    public boolean isPremiumFetchSkins() { return premiumFetchSkins; }

    /**
     * Command roots (no leading slash, lowercase) an unauthenticated player may run.
     */
    public List<String> getAllowedCommands() { return allowedCommands; }

    /**
     * @param command a command root or full command, with or without a leading slash
     * @return true if an unauthenticated player is allowed to run it
     */
    public boolean isCommandAllowedPreAuth(String command) {
        return allowedCommandSet.contains(normalizeCommandRoot(command));
    }

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
        return switch (databaseType.toLowerCase(Locale.ROOT)) {
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    databaseHost, databasePort, databaseName);
            case "postgresql", "postgres" -> String.format("jdbc:postgresql://%s:%d/%s?sslmode=require&channel_binding=disable",
                    databaseHost, databasePort, databaseName);
            default -> String.format("jdbc:h2:%s/vouch;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", dataDir);
        };
    }
}
