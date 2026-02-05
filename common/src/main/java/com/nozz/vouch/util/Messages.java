package com.nozz.vouch.util;

import com.nozz.vouch.config.VouchConfigManager;
import net.minecraft.text.Text;

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
    private static Text prefixed(String key, Object... placeholders) {
        return LangManager.getInstance().getTextWithPrefix(key, placeholders);
    }

    /**
     * Create a text message without prefix from lang key.
     */
    private static Text text(String key, Object... placeholders) {
        return LangManager.getInstance().getText(key, placeholders);
    }

    public static Text welcomeUnregistered() {
        return prefixed("vouch.auth.welcome.unregistered");
    }

    public static Text welcomeRegistered() {
        return prefixed("vouch.auth.welcome.registered");
    }

    public static Text welcome2FAOnly() {
        return prefixed("vouch.auth.welcome.2fa_only");
    }

    public static Text welcome2FAOnlyRegistered() {
        return prefixed("vouch.auth.welcome.2fa_only.registered");
    }

    public static Text registerSuccess() {
        return prefixed("vouch.auth.register.success");
    }

    public static Text registerSuccess2FA() {
        return prefixed("vouch.auth.register.success_2fa");
    }

    public static Text loginSuccess() {
        return prefixed("vouch.auth.login.success");
    }

    public static Text alreadyLoggedIn() {
        return prefixed("vouch.auth.already_logged_in");
    }

    public static Text alreadyRegistered() {
        return prefixed("vouch.auth.register.already_registered");
    }

    public static Text notRegistered() {
        return prefixed("vouch.auth.not_registered");
    }

    public static Text passwordMismatch() {
        return prefixed("vouch.auth.register.password_mismatch");
    }

    public static Text wrongPassword() {
        return prefixed("vouch.auth.login.wrong_password");
    }

    public static Text tooManyAttempts() {
        return prefixed("vouch.auth.login.too_many_attempts");
    }

    public static Text lockedOut(int seconds) {
        return prefixed("vouch.auth.login.locked_out", "time", seconds);
    }

    public static Text passwordTooShort(int minLength) {
        return prefixed("vouch.auth.register.password_too_short", "min", minLength);
    }

    public static Text passwordTooLong(int maxLength) {
        return prefixed("vouch.auth.register.password_too_long", "max", maxLength);
    }

    public static Text authTimeout() {
        return text("vouch.auth.timeout");
    }

    public static Text chatBlocked() {
        return prefixed("vouch.jail.chat_blocked");
    }

    public static Text actionBlocked() {
        return prefixed("vouch.jail.action_blocked");
    }

    public static Text commandBlocked() {
        return prefixed("vouch.jail.command_blocked");
    }

    public static Text playerUnregistered(String playerName) {
        return prefixed("vouch.admin.player_unregistered", "player", playerName);
    }

    public static Text playerNotFound(String playerName) {
        return prefixed("vouch.admin.player_not_found", "player", playerName);
    }

    public static Text player2FAReset(String playerName) {
        return prefixed("vouch.admin.player_2fa_reset", "player", playerName);
    }

    public static Text configReloaded() {
        return prefixed("vouch.admin.config_reloaded");
    }

    public static Text langExported(String path) {
        return prefixed("vouch.admin.lang_exported", "path", path);
    }

    public static Text databaseError() {
        return prefixed("vouch.admin.database_error");
    }

    public static Text noPermission() {
        return prefixed("vouch.admin.no_permission");
    }

    public static Text twoFactorRequired() {
        return prefixed("vouch.2fa.required");
    }

    public static Text twoFactorSetupInstructions() {
        return prefixed("vouch.2fa.setup.instructions");
    }

    public static Text twoFactorManualSecret(String secret) {
        return prefixed("vouch.2fa.setup.manual_secret", "secret", secret);
    }

    public static Text twoFactorEnabled() {
        return prefixed("vouch.2fa.enabled");
    }

    public static Text twoFactorDisabled() {
        return prefixed("vouch.2fa.disabled");
    }

    public static Text twoFactorInvalidCode() {
        return prefixed("vouch.2fa.invalid_code");
    }

    public static Text twoFactorAlreadyEnabled() {
        return prefixed("vouch.2fa.already_enabled");
    }

    public static Text twoFactorNotEnabled() {
        return prefixed("vouch.2fa.not_enabled");
    }

    public static Text twoFactorSetupFailed() {
        return prefixed("vouch.2fa.setup.failed");
    }

    public static Text twoFactorNothingPending() {
        return prefixed("vouch.2fa.nothing_pending");
    }

    public static Text twoFactorStatusEnabled() {
        return prefixed("vouch.2fa.status.enabled");
    }

    public static Text twoFactorStatusDisabled() {
        return prefixed("vouch.2fa.status.disabled");
    }

    public static Text mustLoginFirst() {
        return prefixed("vouch.2fa.must_login_first");
    }

    public static Text twoFactorRequiredForOps() {
        return prefixed("vouch.2fa.required_for_ops");
    }

    public static Text sessionExpired() {
        return prefixed("vouch.auth.session_expired");
    }

    public static Text sessionRestored() {
        return prefixed("vouch.auth.session_restored");
    }

    public static Text logoutSuccess() {
        return prefixed("vouch.auth.logout.success");
    }

    public static Text logoutKick() {
        return text("vouch.auth.logout.kick");
    }

    public static Text processing() {
        return prefixed("vouch.jail.processing");
    }

    public static Text usageRegister() {
        return text("vouch.command.usage.register");
    }

    public static Text usageRegister2FA() {
        return text("vouch.command.usage.register_2fa");
    }

    public static Text usageLogin() {
        return text("vouch.command.usage.login");
    }

    public static Text usageLogin2FA() {
        return text("vouch.command.usage.login_2fa");
    }

    public static Text usage2FAVerify() {
        return text("vouch.command.usage.2fa_verify");
    }

    public static Text usage2FADisable() {
        return text("vouch.command.usage.2fa_disable");
    }

    public static Text disabled2FA() {
        return prefixed("vouch.command.disabled.2fa");
    }

    public static Text disabledPassword() {
        return prefixed("vouch.command.disabled.password");
    }

    public static Text internalError() {
        return prefixed("vouch.error.internal");
    }

    public static Text databaseConnectionError() {
        return prefixed("vouch.error.database");
    }
}

