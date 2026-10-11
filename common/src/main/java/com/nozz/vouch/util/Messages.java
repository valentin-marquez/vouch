package com.nozz.vouch.util;

import com.nozz.vouch.config.VouchConfigManager;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * Centralized message provider for Vouch.
 * 
 * All player-facing messages are retrieved from LangManager for localization.
 * This class provides convenience methods that delegate to LangManager.
 */
public final class Messages {

    private Messages() {
    }

    /**
     * Get prefix from config.
     */
    @SuppressWarnings("unused")
    private static String getPrefix() {
        try {
            return VouchConfigManager.getInstance().getBrandingPrefix();
        } catch (Exception e) {
            return "&8[&6Vouch&8]&r ";
        }
    }

    /**
     * Create a prefixed text message from lang key.
     */
    private static Component prefixed(String key, Object... placeholders) {
        return LangManager.getInstance().getTextWithPrefix(key, placeholders);
    }

    /**
     * Create a text message without prefix from lang key.
     */
    private static Component text(String key, Object... placeholders) {
        return LangManager.getInstance().getText(key, placeholders);
    }

    public static Component welcomeUnregistered() {
        return prefixed("vouch.auth.welcome.unregistered");
    }

    public static Component welcomeRegistered() {
        return prefixed("vouch.auth.welcome.registered");
    }

    public static Component welcome2FAOnly() {
        return prefixed("vouch.auth.welcome.2fa_only");
    }

    public static Component welcome2FAOnlyRegistered() {
        return prefixed("vouch.auth.welcome.2fa_only.registered");
    }

    public static Component registerSuccess() {
        return prefixed("vouch.auth.register.success");
    }

    public static Component registerSuccess2FA() {
        return prefixed("vouch.auth.register.success_2fa");
    }

    public static Component loginSuccess() {
        return prefixed("vouch.auth.login.success");
    }

    public static Component alreadyLoggedIn() {
        return prefixed("vouch.auth.already_logged_in");
    }

    public static Component alreadyRegistered() {
        return prefixed("vouch.auth.register.already_registered");
    }

    public static Component notRegistered() {
        return prefixed("vouch.auth.not_registered");
    }

    public static Component passwordMismatch() {
        return prefixed("vouch.auth.register.password_mismatch");
    }

    public static Component wrongPassword() {
        return prefixed("vouch.auth.login.wrong_password");
    }

    public static Component tooManyAttempts() {
        return prefixed("vouch.auth.login.too_many_attempts");
    }

    public static Component lockedOut(int seconds) {
        return prefixed("vouch.auth.login.locked_out", "time", seconds);
    }

    public static Component passwordTooShort(int minLength) {
        return prefixed("vouch.auth.register.password_too_short", "min", minLength);
    }

    public static Component passwordTooLong(int maxLength) {
        return prefixed("vouch.auth.register.password_too_long", "max", maxLength);
    }

    public static Component authTimeout() {
        return text("vouch.auth.timeout");
    }

    public static Component chatBlocked() {
        return prefixed("vouch.jail.chat_blocked");
    }

    public static Component actionBlocked() {
        return prefixed("vouch.jail.action_blocked");
    }

    public static Component commandBlocked() {
        return prefixed("vouch.jail.command_blocked");
    }

    public static Component playerUnregistered(String playerName) {
        return prefixed("vouch.admin.player_unregistered", "player", playerName);
    }

    public static Component playerNotFound(String playerName) {
        return prefixed("vouch.admin.player_not_found", "player", playerName);
    }

    public static Component player2FAReset(String playerName) {
        return prefixed("vouch.admin.player_2fa_reset", "player", playerName);
    }

    public static Component configReloaded() {
        return prefixed("vouch.admin.config_reloaded");
    }

    public static Component langExported(String path) {
        return prefixed("vouch.admin.lang_exported", "path", path);
    }

    public static Component databaseError() {
        return prefixed("vouch.admin.database_error");
    }

    public static Component noPermission() {
        return prefixed("vouch.admin.no_permission");
    }

    public static Component twoFactorRequired() {
        return prefixed("vouch.2fa.required");
    }

    public static Component twoFactorSetupInstructions() {
        return prefixed("vouch.2fa.setup.instructions");
    }

    public static Component twoFactorManualSecret(String secret) {
        return prefixed("vouch.2fa.setup.manual_secret", "secret", secret);
    }

    public static Component twoFactorEnabled() {
        return prefixed("vouch.2fa.enabled");
    }

    public static Component twoFactorDisabled() {
        return prefixed("vouch.2fa.disabled");
    }

    public static Component twoFactorInvalidCode() {
        return prefixed("vouch.2fa.invalid_code");
    }

    public static Component twoFactorAlreadyEnabled() {
        return prefixed("vouch.2fa.already_enabled");
    }

    public static Component twoFactorNotEnabled() {
        return prefixed("vouch.2fa.not_enabled");
    }

    public static Component twoFactorSetupFailed() {
        return prefixed("vouch.2fa.setup.failed");
    }

    public static Component twoFactorNothingPending() {
        return prefixed("vouch.2fa.nothing_pending");
    }

    public static Component twoFactorStatusEnabled() {
        return prefixed("vouch.2fa.status.enabled");
    }

    public static Component twoFactorStatusDisabled() {
        return prefixed("vouch.2fa.status.disabled");
    }

    public static Component mustLoginFirst() {
        return prefixed("vouch.2fa.must_login_first");
    }

    public static Component twoFactorRequiredForOps() {
        return prefixed("vouch.2fa.required_for_ops");
    }

    public static Component sessionExpired() {
        return prefixed("vouch.auth.session_expired");
    }

    public static Component sessionRestored() {
        return prefixed("vouch.auth.session_restored");
    }

    public static Component logoutSuccess() {
        return prefixed("vouch.auth.logout.success");
    }

    public static Component logoutKick() {
        return text("vouch.auth.logout.kick");
    }

    public static Component processing() {
        return prefixed("vouch.jail.processing");
    }

    public static Component usageRegister() {
        return text("vouch.command.usage.register");
    }

    public static Component usageRegister2FA() {
        return text("vouch.command.usage.register_2fa");
    }

    public static Component usageLogin() {
        return text("vouch.command.usage.login");
    }

    public static Component usageLogin2FA() {
        return text("vouch.command.usage.login_2fa");
    }

    public static Component usage2FAVerify() {
        return text("vouch.command.usage.2fa_verify");
    }

    public static Component usage2FADisable() {
        return text("vouch.command.usage.2fa_disable");
    }

    public static Component disabled2FA() {
        return prefixed("vouch.command.disabled.2fa");
    }

    public static Component disabledPassword() {
        return prefixed("vouch.command.disabled.password");
    }

    public static Component internalError() {
        return prefixed("vouch.error.internal");
    }

    public static Component databaseConnectionError() {
        return prefixed("vouch.error.database");
    }

    public static Component premiumAutoLogin() {
        return prefixed("vouch.auth.premium.auto_login");
    }

    public static Component premiumRequire2FA() {
        return prefixed("vouch.auth.premium.require_2fa");
    }

    public static Component premiumForcedOffline(String playerName) {
        return prefixed("vouch.admin.premium.forced_offline", "player", playerName);
    }

    public static Component premiumForcedOnline(String playerName) {
        return prefixed("vouch.admin.premium.forced_online", "player", playerName);
    }

    public static Component premiumMarkedOnline() {
        return prefixed("vouch.command.premium.marked_online");
    }

    public static Component premiumMarkedOnlineNotPremium() {
        return prefixed("vouch.command.premium.not_premium");
    }

    public static Component premiumAlreadyMarkedOnline() {
        return prefixed("vouch.command.premium.already_marked");
    }

    // ==================== Clickable suggestions ====================

    /**
     * Attach "click to fill this command into your chat box" to a message.
     *
     * <p>Every prompt this mod sends is a command the player then has to retype, by
     * hand, while jailed and on a countdown. Brigadier has carried a suggest-command
     * click event for years; this is one place that uses it.
     *
     * <p><b>Translators never see this.</b> The lang files stay plain strings — the
     * command is passed in by the caller, and the hover text is its own ordinary key.
     * A translator who does not know what a click event is cannot break one, and a
     * language that has not been updated still gets a working, clickable message.
     *
     * <p>{@link ClickEvent.Action#SUGGEST_COMMAND} fills the chat box rather than
     * running anything, which is the only correct choice here: these commands take
     * passwords and one-time codes, and a click that ran them would submit an empty
     * one.
     *
     * @param message   the message to decorate
     * @param command   what to put in the player's chat box, leading slash included
     */
    public static Component suggesting(Component message, String command) {
        if (message == null || command == null || command.isBlank()) {
            return message;
        }
        Component hover = LangManager.getInstance().getText(ChatSuggestion.HOVER_KEY, "command", command);
        return message.copy().withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    /**
     * Render one entry of the {@link ChatSuggestion} table: its message, carrying its
     * command as a click-to-fill suggestion.
     */
    public static Component suggesting(ChatSuggestion suggestion) {
        return suggesting(prefixed(suggestion.langKey()), suggestion.command());
    }

    // ==================== Prompts that suggest their own next step ====================

    /** "You are not registered" — clicking offers {@code /register}. */
    public static Component notRegisteredClickable() {
        return suggesting(ChatSuggestion.NOT_REGISTERED);
    }

    /** "Log in first" — clicking offers {@code /login}. */
    public static Component mustLoginFirstClickable() {
        return suggesting(ChatSuggestion.MUST_LOGIN_FIRST);
    }

    /** "Second factor required" — clicking offers {@code /2fa verify}. */
    public static Component twoFactorRequiredClickable() {
        return suggesting(ChatSuggestion.TWO_FACTOR_REQUIRED);
    }

    /** "2FA is off" — clicking offers {@code /vouch 2fa setup}. */
    public static Component twoFactorStatusDisabledClickable() {
        return suggesting(ChatSuggestion.TWO_FACTOR_STATUS_DISABLED);
    }

    /** The setup instructions, with the verify command one click away. */
    public static Component twoFactorSetupInstructionsClickable() {
        return suggesting(ChatSuggestion.TWO_FACTOR_SETUP_INSTRUCTIONS);
    }

    /** Operators told to configure 2FA get the command that does it. */
    public static Component twoFactorRequiredForOpsClickable() {
        return suggesting(ChatSuggestion.TWO_FACTOR_REQUIRED_FOR_OPS);
    }

    // ==================== Password changes ====================

    public static Component passwordChanged() {
        return prefixed("vouch.auth.password.changed");
    }

    public static Component passwordWrongCurrent() {
        return prefixed("vouch.auth.password.wrong_current");
    }

    public static Component passwordUnchanged() {
        return prefixed("vouch.auth.password.unchanged");
    }

    public static Component passwordResetFor(String playerName) {
        return prefixed("vouch.admin.user.password_reset", "player", playerName);
    }

    // ==================== Account info ====================

    public static Component userInfoHeader(String playerName) {
        return prefixed("vouch.admin.user.info.header", "player", playerName);
    }

    public static Component userInfoUuid(String uuid) {
        return text("vouch.admin.user.info.uuid", "value", uuid);
    }

    public static Component userInfoTwoFactor(String value) {
        return text("vouch.admin.user.info.2fa", "value", value);
    }

    public static Component userInfoLastLogin(String value) {
        return text("vouch.admin.user.info.last_login", "value", value);
    }

    public static Component userInfoLastIp(String value) {
        return text("vouch.admin.user.info.last_ip", "value", value);
    }

    public static Component userInfoPremium(String value) {
        return text("vouch.admin.user.info.premium", "value", value);
    }

    /** Plain words for the info lines above, so they can be translated too. */
    public static String valueYes() {
        return LangManager.getInstance().get("vouch.value.yes");
    }

    public static String valueNo() {
        return LangManager.getInstance().get("vouch.value.no");
    }

    public static String valueNever() {
        return LangManager.getInstance().get("vouch.value.never");
    }
}

