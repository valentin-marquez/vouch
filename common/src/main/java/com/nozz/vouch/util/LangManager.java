package com.nozz.vouch.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nozz.vouch.config.VouchConfigManager;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language/localization manager for Vouch.
 * 
 * Loads messages from JSON files with fallback chain:
 * 1. User override file: config/vouch/lang/{lang}.json
 * 2. Mod resource: assets/vouch/lang/{lang}.json
 * 3. Fallback language (en_us)
 * 4. Key itself (if all else fails)
 * 
 * Supports:
 * - Minecraft color codes (&a, &c, etc.)
 * - Placeholder substitution ({player}, {time}, etc.)
 * - Custom placeholders from config ({mod_name})
 */
public final class LangManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Lang");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    
    // Color code pattern: & followed by 0-9, a-f, k-o, r
    @SuppressWarnings("unused")
    private static final Pattern COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])");
    
    // Placeholder pattern: {name}
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-z_]+)\\}");
    
    private static LangManager instance;
    
    private final Map<String, String> messages = new HashMap<>();
    private final Map<String, String> fallbackMessages = new HashMap<>();
    private String currentLanguage = "en_us";
    private Path configDir;
    private final Map<String, String> globalPlaceholders = new HashMap<>();
    
    private LangManager() {
        globalPlaceholders.put("mod_name", "Vouch");
    }
    
    public static LangManager getInstance() {
        if (instance == null) {
            instance = new LangManager();
        }
        return instance;
    }
    
    /**
     * Initialize the language manager with the lang directory.
     * Should be called after VouchConfigManager is initialized.
     */
    public void initialize(Path langDir) {
        this.configDir = langDir;

        loadFallbackLanguage();
        reload();
        autoGenerateLangFileIfMissing();
    }
    
    /**
     * Auto-generate all available language files if they don't exist.
     * This allows users to customize messages without running /vouch admin export-lang.
     */
    private void autoGenerateLangFileIfMissing() {
        if (configDir == null) return;
        
        // List of all available languages bundled with the mod
        String[] availableLanguages = {"en_us", "es_mx"};
        
        for (String lang : availableLanguages) {
            Path langFile = configDir.resolve(lang + ".json");
            
            if (!Files.exists(langFile)) {
                try {
                    // Load messages for this specific language
                    Map<String, String> langMessages = new TreeMap<>();
                    loadLanguage(lang, langMessages);
                    
                    if (!langMessages.isEmpty()) {
                        exportLanguageToFile(langFile, langMessages);
                        LOGGER.info("Generated language file for customization: {}", langFile);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to auto-generate language file: {}", langFile, e);
                }
            }
        }
    }
    
    /**
     * Export a specific language map to a file.
     */
    private void exportLanguageToFile(Path path, Map<String, String> langMessages) throws IOException {
        Files.createDirectories(path.getParent());
        
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            Map<String, String> sorted = new TreeMap<>(langMessages);
            GSON.toJson(sorted, writer);
        }
    }
    
    /**
     * Reload language files based on current config.
     */
    public void reload() {
        VouchConfigManager config = VouchConfigManager.getInstance();
        currentLanguage = config.getLanguage();
        
        // Update global placeholders from config
        globalPlaceholders.put("mod_name", config.getBrandingModName());
        
        // Clear and reload messages
        messages.clear();
        messages.putAll(fallbackMessages);  // Start with fallback
        
        if (!currentLanguage.equals("en_us")) {
            // Load language-specific overrides
            loadLanguage(currentLanguage, messages);
        }
        
        // Load user overrides (highest priority)
        loadUserOverrides();
        
        LOGGER.info("Language loaded: {} ({} messages)", currentLanguage, messages.size());
    }
    
    /**
     * Load the fallback language (en_us).
     */
    private void loadFallbackLanguage() {
        fallbackMessages.clear();
        loadLanguage("en_us", fallbackMessages);
        
        // If resource loading failed, use hardcoded defaults
        if (fallbackMessages.isEmpty()) {
            loadHardcodedDefaults();
        }
    }
    
    /**
     * Load language from mod resources.
     */
    private void loadLanguage(String lang, Map<String, String> target) {
        String resourcePath = "/assets/vouch/lang/" + lang + ".json";
        
        try (InputStream is = LangManager.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
                    if (loaded != null) {
                        target.putAll(loaded);
                        LOGGER.debug("Loaded {} messages from resource: {}", loaded.size(), resourcePath);
                    }
                }
            } else {
                LOGGER.debug("Language resource not found: {}", resourcePath);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load language resource: {}", resourcePath, e);
        }
    }
    
    /**
     * Load user override file from lang directory.
     * New location: config/vouch/lang/{lang}.json
     */
    private void loadUserOverrides() {
        if (configDir == null) return;
        
        // New path: config/vouch/lang/{lang}.json
        Path overridePath = configDir.resolve(currentLanguage + ".json");
        
        if (Files.exists(overridePath)) {
            try (Reader reader = Files.newBufferedReader(overridePath, StandardCharsets.UTF_8)) {
                Map<String, String> overrides = GSON.fromJson(reader, MAP_TYPE);
                if (overrides != null) {
                    messages.putAll(overrides);
                    LOGGER.info("Loaded {} user message overrides from {}", overrides.size(), overridePath);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load user language overrides: {}", overridePath, e);
            }
        }
    }
    
    /**
     * Hardcoded default messages (fallback if resources fail to load).
     */
    private void loadHardcodedDefaults() {
        // Authentication
        fallbackMessages.put("vouch.auth.welcome.unregistered", "&6Welcome! Please register with: &f/register <password> <password>");
        fallbackMessages.put("vouch.auth.welcome.registered", "&6Welcome back! Please login with: &f/login <password>");
        fallbackMessages.put("vouch.auth.welcome.2fa_only.unregistered", "&6Welcome! Please use &f/register &6to set up 2FA");
        fallbackMessages.put("vouch.auth.welcome.2fa_only.registered", "&6Welcome back! Please login with: &f/login <code>");
        fallbackMessages.put("vouch.ui.subtitle.2fa_only.unregistered", "&ePlease use &f/register &eto set up 2FA");
        fallbackMessages.put("vouch.ui.subtitle.2fa_only.registered", "&eEnter your 6-digit authenticator code");
        fallbackMessages.put("vouch.auth.mode.2fa_disabled", "&c2FA commands are disabled in password-only mode.");
        fallbackMessages.put("vouch.auth.mode.password_disabled", "&cThis server uses 2FA-only authentication.");
        fallbackMessages.put("vouch.auth.login.success", "&aLogin successful! Welcome back.");
        fallbackMessages.put("vouch.auth.login.wrong_password", "&cWrong password!");
        fallbackMessages.put("vouch.auth.login.too_many_attempts", "&cToo many failed attempts! Please wait before trying again.");
        fallbackMessages.put("vouch.auth.register.success", "&aRegistration successful! You are now logged in.");
        fallbackMessages.put("vouch.auth.register.password_mismatch", "&cPasswords do not match! Try again.");
        fallbackMessages.put("vouch.auth.register.password_too_short", "&cPassword too short! Minimum {min} characters.");
        fallbackMessages.put("vouch.auth.register.password_too_long", "&cPassword too long! Maximum {max} characters.");
        fallbackMessages.put("vouch.auth.register.already_registered", "&cYou are already registered! Use /login instead.");
        fallbackMessages.put("vouch.auth.not_registered", "&cYou are not registered! Use /register first.");
        fallbackMessages.put("vouch.auth.already_logged_in", "&eYou are already logged in!");
        fallbackMessages.put("vouch.auth.timeout", "Authentication timeout. Please reconnect and try again.");
        fallbackMessages.put("vouch.auth.session_restored", "&aSession restored! Welcome back.");
        fallbackMessages.put("vouch.auth.session_expired", "&cYour session has expired. Please reconnect.");
        fallbackMessages.put("vouch.auth.logout.success", "&eYou have been logged out. Session invalidated.");
        fallbackMessages.put("vouch.auth.logout.kick", "You have been logged out. Please reconnect to authenticate.");
        
        // Pre-auth jail
        fallbackMessages.put("vouch.jail.chat_blocked", "&cYou cannot chat until you authenticate!");
        fallbackMessages.put("vouch.jail.action_blocked", "&cYou cannot do that until you authenticate!");
        fallbackMessages.put("vouch.jail.processing", "&7Processing...");
        
        // 2FA
        fallbackMessages.put("vouch.2fa.required", "&e2FA is enabled. Use: &f/2fa verify <code>");
        fallbackMessages.put("vouch.2fa.setup.instructions", "&eScan the QR code with your authenticator app, then use: &f/2fa verify <code>");
        fallbackMessages.put("vouch.2fa.setup.manual_secret", "&7Manual entry: &b{secret}");
        fallbackMessages.put("vouch.2fa.setup.failed", "&cFailed to generate QR code. Please try again.");
        fallbackMessages.put("vouch.2fa.enabled", "&a2FA has been enabled successfully!");
        fallbackMessages.put("vouch.2fa.disabled", "&e2FA has been disabled.");
        fallbackMessages.put("vouch.2fa.invalid_code", "&cInvalid 2FA code. Please try again.");
        fallbackMessages.put("vouch.2fa.already_enabled", "&e2FA is already enabled! Use &f/2fa disable <code>&e to disable it first.");
        fallbackMessages.put("vouch.2fa.not_enabled", "&e2FA is not enabled. Use &f/2fa setup&e to enable it.");
        fallbackMessages.put("vouch.2fa.nothing_pending", "&eNo 2FA action pending. Use &f/2fa setup&e to set up 2FA.");        fallbackMessages.put("vouch.2fa.required_for_ops", "&cAs a server operator, you must enable 2FA. Use &f/2fa setup &cto continue.");        fallbackMessages.put("vouch.2fa.status.enabled", "&72FA Status: &aENABLED");
        fallbackMessages.put("vouch.2fa.status.disabled", "&72FA Status: &cDISABLED &7- Use /2fa setup to enable");
        fallbackMessages.put("vouch.2fa.must_login_first", "&cYou must be logged in first!");
        
        // Admin
        fallbackMessages.put("vouch.admin.player_unregistered", "&aPlayer &6{player}&a has been unregistered.");
        fallbackMessages.put("vouch.admin.player_not_found", "&cPlayer &6{player}&c not found or not registered.");
        fallbackMessages.put("vouch.admin.config_reloaded", "&aConfiguration reloaded successfully!");
        fallbackMessages.put("vouch.admin.database_error", "&cA database error occurred. Please check the server logs.");
        
        // UI Elements
        fallbackMessages.put("vouch.ui.title.welcome", "&6Authentication Required");
        fallbackMessages.put("vouch.ui.title.login_success", "&a✓ Welcome Back!");
        fallbackMessages.put("vouch.ui.title.register_success", "&a✓ Registered!");
        fallbackMessages.put("vouch.ui.subtitle.unregistered", "&ePlease use &f/register &eto get started");
        fallbackMessages.put("vouch.ui.subtitle.registered", "&ePlease use &f/login &eto continue");
        fallbackMessages.put("vouch.ui.subtitle.login_success", "&7You are now logged in");
        fallbackMessages.put("vouch.ui.subtitle.register_success", "&7Your account has been created");
        fallbackMessages.put("vouch.ui.actionbar.pre_auth", "&e⏳ Time remaining: &f{time}s &8| &e/login or /register");
        fallbackMessages.put("vouch.ui.actionbar.awaiting_2fa", "&6🔐 Enter your 2FA code: &f/2fa verify <code>");
        fallbackMessages.put("vouch.ui.actionbar.rate_limited", "&c⏰ Too many attempts. Wait &f{time}s");
        fallbackMessages.put("vouch.ui.bossbar.text", "&e🔒 Authentication Required - {time}s remaining");
        
        LOGGER.info("Loaded {} hardcoded default messages", fallbackMessages.size());
    }
    
    /**
     * Get a raw message by key (no parsing).
     */
    public String getRaw(String key) {
        return messages.getOrDefault(key, fallbackMessages.getOrDefault(key, key));
    }
    
    /**
     * Get a message with placeholders resolved.
     * 
     * @param key The message key
     * @param placeholders Key-value pairs for substitution (e.g., "player", "Steve", "time", "30")
     * @return The formatted string (still contains color codes)
     */
    public String get(String key, Object... placeholders) {
        String message = getRaw(key);
        
        // Build placeholder map
        Map<String, String> placeholderMap = new HashMap<>(globalPlaceholders);
        for (int i = 0; i < placeholders.length - 1; i += 2) {
            placeholderMap.put(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1]));
        }
        
        // Resolve placeholders
        return resolvePlaceholders(message, placeholderMap);
    }
    
    /**
     * Get a message as Minecraft Text component.
     * 
     * @param key The message key
     * @param placeholders Key-value pairs for substitution
     * @return Formatted Text component with colors
     */
    public Text getText(String key, Object... placeholders) {
        String message = get(key, placeholders);
        return parseColorCodes(message);
    }
    
    /**
     * Get a message with prefix prepended.
     */
    public Text getTextWithPrefix(String key, Object... placeholders) {
        String prefix = VouchConfigManager.getInstance().getBrandingPrefix();
        String message = get(key, placeholders);
        return parseColorCodes(prefix + message);
    }
    
    /**
     * Resolve placeholder patterns in a message.
     */
    private String resolvePlaceholders(String message, Map<String, String> placeholders) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(message);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = placeholders.getOrDefault(placeholder, matcher.group(0));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Parse Minecraft color codes (&a, &c, etc.) into a Text component.
     */
    public static Text parseColorCodes(String message) {
        if (message == null || message.isEmpty()) {
            return Text.empty();
        }
        
        MutableText result = Text.empty();
        String[] parts = message.split("(?=&[0-9a-fk-or])|(?<=&[0-9a-fk-or])");
        
        Formatting currentFormat = null;
        boolean bold = false, italic = false, underline = false, strikethrough = false, obfuscated = false;
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            
            if (part.length() == 2 && part.charAt(0) == '&') {
                char code = part.charAt(1);
                
                switch (code) {
                    case '0' -> currentFormat = Formatting.BLACK;
                    case '1' -> currentFormat = Formatting.DARK_BLUE;
                    case '2' -> currentFormat = Formatting.DARK_GREEN;
                    case '3' -> currentFormat = Formatting.DARK_AQUA;
                    case '4' -> currentFormat = Formatting.DARK_RED;
                    case '5' -> currentFormat = Formatting.DARK_PURPLE;
                    case '6' -> currentFormat = Formatting.GOLD;
                    case '7' -> currentFormat = Formatting.GRAY;
                    case '8' -> currentFormat = Formatting.DARK_GRAY;
                    case '9' -> currentFormat = Formatting.BLUE;
                    case 'a' -> currentFormat = Formatting.GREEN;
                    case 'b' -> currentFormat = Formatting.AQUA;
                    case 'c' -> currentFormat = Formatting.RED;
                    case 'd' -> currentFormat = Formatting.LIGHT_PURPLE;
                    case 'e' -> currentFormat = Formatting.YELLOW;
                    case 'f' -> currentFormat = Formatting.WHITE;
                    case 'k' -> obfuscated = true;
                    case 'l' -> bold = true;
                    case 'm' -> strikethrough = true;
                    case 'n' -> underline = true;
                    case 'o' -> italic = true;
                    case 'r' -> {
                        currentFormat = null;
                        bold = italic = underline = strikethrough = obfuscated = false;
                    }
                }
            } else if (!part.isEmpty()) {
                MutableText textPart = Text.literal(part);
                
                Style style = Style.EMPTY;
                if (currentFormat != null) {
                    style = style.withColor(currentFormat);
                }
                if (bold) style = style.withBold(true);
                if (italic) style = style.withItalic(true);
                if (underline) style = style.withUnderline(true);
                if (strikethrough) style = style.withStrikethrough(true);
                if (obfuscated) style = style.withObfuscated(true);
                
                textPart.setStyle(style);
                result.append(textPart);
            }
        }
        
        return result;
    }
    
    /**
     * Export current messages to a file for user customization.
     */
    public void exportToFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // Sort keys for better readability
            Map<String, String> sorted = new TreeMap<>(messages);
            GSON.toJson(sorted, writer);
        }
        
        LOGGER.info("Exported {} messages to {}", messages.size(), path);
    }
    
    /**
     * Get the current language code.
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }
    
    /**
     * Get all message keys.
     */
    public Set<String> getKeys() {
        return Collections.unmodifiableSet(messages.keySet());
    }
    
    /**
     * Check if a message key exists.
     */
    public boolean hasKey(String key) {
        return messages.containsKey(key) || fallbackMessages.containsKey(key);
    }
}
