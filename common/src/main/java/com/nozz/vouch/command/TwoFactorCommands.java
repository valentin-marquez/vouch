package com.nozz.vouch.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nozz.vouch.VouchMod;
import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.AuthMode;
import com.nozz.vouch.auth.PlayerSession;
import com.nozz.vouch.auth.RateLimiter;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.crypto.TOTPEngine;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.util.LangManager;
import com.nozz.vouch.util.Messages;
import com.nozz.vouch.util.PermissionHelper;
import com.nozz.vouch.util.PlayerIps;
import com.nozz.vouch.util.QRMapRenderer;
import com.nozz.vouch.util.UXManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static com.nozz.vouch.command.VouchCommandTree.ARG_CODE;

/**
 * Two-factor commands, reached as {@code /vouch 2fa …} and still as {@code /2fa …}.
 *
 * <p>They are no longer registered here: {@link VouchCommandTree} owns the shape and
 * {@link VouchCommands} wires these executors into it, so {@code /2fa} is a redirect
 * into the same nodes rather than a second tree.
 *
 * <h2>Rate limiting</h2>
 * {@code verify} and {@code disable} previously consulted no limiter at all. A TOTP
 * code is six digits — a million possibilities, and roughly thirty seconds of
 * validity either side — so an unlimited guess rate is the difference between a
 * second factor and a formality. Both now go through {@link RateLimitDecision},
 * checking the address before the attempt and recording the result after it.
 */
public final class TwoFactorCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/2FA");

    // TOTP code length (RFC 6238 standard = 6 digits)
    private static final int CODE_LENGTH = 6;

    private TwoFactorCommands() {
    }

    /**
     * Handle /2fa setup command
     *
     * Generates a TOTP secret, creates a QR code, and sends it to the player.
     * Player must be authenticated (logged in) to set up 2FA.
     *
     * Note: In PASSWORD_ONLY mode, this command is disabled.
     */
    static int executeSetup(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_SETUP)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();
        DatabaseManager db = DatabaseManager.getInstance();

        AuthMode authMode = VouchConfigManager.getInstance().getAuthMode();
        if (!authMode.usesTotp()) {
            player.sendMessage(LangManager.getInstance().getTextWithPrefix("vouch.auth.mode.2fa_disabled"), false);
            return 0;
        }

        if (!authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.mustLoginFirstClickable(), false);
            return 0;
        }

        String accountName = player.getName().getString();

        VouchMod.getInstance().onMain(db.has2FAEnabled(player.getUuid()),
                "2FA status for " + accountName, player, has2FA -> {
                    PlayerSession session = authManager.getSession(player.getUuid());
                    if (session != null) {
                        session.set2FAEnabled(has2FA);
                    }

                    if (has2FA) {
                        player.sendMessage(Messages.twoFactorAlreadyEnabled(), false);
                        CommandVisibility.resend(player);
                        return;
                    }

                    String secret = TOTPEngine.generateSecret();
                    String issuer = VouchConfigManager.getInstance().getServerName();

                    if (session != null) {
                        session.setPending2FASecret(secret);
                    }

                    String otpAuthUri = TOTPEngine.generateOtpAuthUri(secret, accountName, issuer);

                    if (QRMapRenderer.sendQRCodeMap(player, otpAuthUri)) {
                        player.sendMessage(Messages.twoFactorSetupInstructionsClickable(), false);
                        player.sendMessage(Messages.twoFactorManualSecret(secret), false);
                        LOGGER.info("2FA setup initiated for player {}", accountName);
                    } else {
                        player.sendMessage(Messages.twoFactorSetupFailed(), false);
                        LOGGER.error("Failed to send QR code to player {}", accountName);
                    }
                },
                // has2FAEnabled fails closed. Starting a setup without knowing whether one
                // already exists would overwrite a working secret with a pending one.
                () -> player.sendMessage(Messages.databaseConnectionError(), false));

        return 1;
    }

    /**
     * Handle /2fa verify <code> command
     *
     * Verifies a TOTP code. Used for both:
     * 1. Completing 2FA setup (verifying the initial code)
     * 2. Logging in when 2FA is required
     */
    static int executeVerify(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_VERIFY)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();

        // Checked before the code is even looked at: this is the guess-rate limit that
        // makes six digits worth something.
        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        String code = StringArgumentType.getString(context, ARG_CODE).replaceAll("\\s+", "");
        if (!isValidCodeFormat(code)) {
            player.sendMessage(Messages.twoFactorInvalidCode(), false);
            return 0;
        }

        AuthManager authManager = AuthManager.getInstance();
        PlayerSession session = authManager.getSession(player.getUuid());

        if (session == null) {
            player.sendMessage(Messages.sessionExpired(), false);
            return 0;
        }

        String pendingSecret = session.getPending2FASecret();
        if (pendingSecret != null) {
            return verifySetup(player, session, pendingSecret, code, ip);
        }

        if (session.isAwaiting2FA()) {
            return verifyLogin(player, session, code, ip);
        }

        player.sendMessage(Messages.twoFactorNothingPending(), false);
        return 0;
    }

    /**
     * Verify TOTP code during 2FA setup
     */
    private static int verifySetup(ServerPlayerEntity player, PlayerSession session,
                                   String secret, String code, Optional<String> ip) {
        boolean valid = TOTPEngine.verifyCode(secret, code);
        RateLimitDecision.forTwoFactorCode(valid).applyTo(RateLimiter.getInstance(), ip);

        if (!valid) {
            player.sendMessage(Messages.twoFactorInvalidCode(), false);
            session.recordFailedAttempt();
            LOGGER.debug("Invalid 2FA setup code from player {}", player.getName().getString());
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        String username = player.getName().getString();
        boolean is2FAOnlyRegistration = session.is2FAOnlyRegistration();

        if (is2FAOnlyRegistration) {
            VouchMod.getInstance().onMain(
                    db.registerPlayerWith2FA(player.getUuid(), username, secret),
                    "2FA-only registration of " + username, player, success -> {
                        if (!success) {
                            player.sendMessage(Messages.databaseError(), false);
                            LOGGER.error("Failed to register player {} with 2FA", username);
                            return;
                        }
                        session.clearPending2FASecret();
                        session.set2FAEnabled(true);
                        QRMapRenderer.removeQRMap(player);
                        RegisteredNames.getInstance().add(username);
                        AuthManager.getInstance().authenticatePlayer(player);
                        UXManager.getInstance().onRegisterSuccess(player);
                        CommandVisibility.resend(player);
                        LOGGER.info("Player {} registered with 2FA (2FA-only mode)", username);
                    });
        } else {
            VouchMod.getInstance().onMain(db.storeTOTPSecret(player.getUuid(), secret),
                    "2FA enrolment of " + username, player, success -> {
                        if (!success) {
                            player.sendMessage(Messages.databaseError(), false);
                            LOGGER.error("Failed to store 2FA secret for player {}", username);
                            return;
                        }
                        session.clearPending2FASecret();
                        session.set2FAEnabled(true);
                        QRMapRenderer.removeQRMap(player);

                        player.sendMessage(Messages.twoFactorEnabled(), false);
                        // Enabling 2FA swaps `setup` for `disable` in the player's tree.
                        CommandVisibility.resend(player);
                        LOGGER.info("2FA enabled for player {}", username);
                    });
        }

        return 1;
    }

    /**
     * Verify TOTP code during login
     */
    private static int verifyLogin(ServerPlayerEntity player, PlayerSession session,
                                   String code, Optional<String> ip) {
        DatabaseManager db = DatabaseManager.getInstance();
        String username = player.getName().getString();

        VouchMod.getInstance().onMain(db.getTOTPSecret(player.getUuid()),
                "2FA secret for " + username, player, secretOpt -> {
                    if (secretOpt.isEmpty()) {
                        player.sendMessage(Messages.twoFactorNotEnabled(), false);
                        return;
                    }

                    boolean valid = TOTPEngine.verifyCode(secretOpt.get(), code);
                    RateLimitDecision.forTwoFactorCode(valid).applyTo(RateLimiter.getInstance(), ip);

                    if (!valid) {
                        UXManager.getInstance().sendMessage(player,
                                LangManager.getInstance().get("vouch.2fa.invalid_code"));
                        session.recordFailedAttempt();

                        if (session.isRateLimited()) {
                            player.sendMessage(Messages.tooManyAttempts(), false);
                        }

                        LOGGER.debug("Invalid 2FA login code from player {}", username);
                        return;
                    }

                    session.set2FAEnabled(true);
                    AuthManager.getInstance().complete2FAAuthentication(player);
                    UXManager.getInstance().onLoginSuccess(player);
                    CommandVisibility.resend(player);

                    ip.ifPresent(address -> db.updateLastLogin(player.getUuid(), address)
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    LOGGER.error("Could not record the last login for {}", username, error);
                                }
                            }));

                    LOGGER.info("Player {} logged in with 2FA", username);
                });

        return 1;
    }

    /**
     * Handle /2fa disable <code> command
     *
     * Disables 2FA for the player after verifying their current code.
     */
    static int executeDisable(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_DISABLE)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.mustLoginFirstClickable(), false);
            return 0;
        }

        // Turning the second factor off is exactly as sensitive as getting past it,
        // and was equally unlimited before.
        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        String code = StringArgumentType.getString(context, ARG_CODE).replaceAll("\\s+", "");
        if (!isValidCodeFormat(code)) {
            player.sendMessage(Messages.twoFactorInvalidCode(), false);
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        String username = player.getName().getString();
        VouchMod mod = VouchMod.getInstance();

        mod.onMain(db.getTOTPSecret(player.getUuid()), "2FA secret for " + username, player, secretOpt -> {
            if (secretOpt.isEmpty()) {
                player.sendMessage(Messages.twoFactorNotEnabled(), false);
                return;
            }

            boolean valid = TOTPEngine.verifyCode(secretOpt.get(), code);
            RateLimitDecision.forTwoFactorCode(valid).applyTo(RateLimiter.getInstance(), ip);

            if (!valid) {
                player.sendMessage(Messages.twoFactorInvalidCode(), false);
                LOGGER.debug("Invalid 2FA disable code from player {}", username);
                return;
            }

            mod.onMain(db.disable2FA(player.getUuid()), "2FA removal for " + username, player, success -> {
                if (!success) {
                    player.sendMessage(Messages.databaseError(), false);
                    return;
                }
                PlayerSession session = authManager.getSession(player.getUuid());
                if (session != null) {
                    session.set2FAEnabled(false);
                }

                player.sendMessage(Messages.twoFactorDisabled(), false);
                // Disabling swaps `disable` back for `setup` in the player's tree.
                CommandVisibility.resend(player);
                LOGGER.info("2FA disabled for player {}", username);
            });
        });

        return 1;
    }

    /**
     * Handle /2fa status command
     *
     * Shows the current 2FA status for the player.
     */
    static int executeStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_STATUS)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.mustLoginFirstClickable(), false);
            return 0;
        }

        String username = player.getName().getString();

        VouchMod.getInstance().onMain(DatabaseManager.getInstance().has2FAEnabled(player.getUuid()),
                "2FA status for " + username, player, has2FA -> {
                    PlayerSession session = authManager.getSession(player.getUuid());
                    if (session != null && session.has2FAEnabled() != has2FA) {
                        // The authoritative answer just arrived; the tree may be showing
                        // the wrong one of setup/disable.
                        session.set2FAEnabled(has2FA);
                        CommandVisibility.resend(player);
                    }

                    player.sendMessage(has2FA
                            ? Messages.twoFactorStatusEnabled()
                            : Messages.twoFactorStatusDisabledClickable(), false);
                },
                () -> player.sendMessage(Messages.databaseConnectionError(), false));

        return 1;
    }

    /**
     * Refuse the attempt outright while the address is locked out.
     *
     * <p>An address that could not be resolved is not limited rather than being
     * bucketed under a placeholder, where one player could lock out every other
     * unresolvable one.
     */
    private static boolean isRateLimited(ServerPlayerEntity player, Optional<String> ip) {
        if (ip.isEmpty()) {
            LOGGER.warn("Could not resolve an address for {}; proceeding without rate limiting",
                    player.getName().getString());
            return false;
        }
        if (RateLimiter.getInstance().getBlockRemaining(ip.get()).isZero()) {
            return false;
        }
        player.sendMessage(Messages.tooManyAttempts(), false);
        return true;
    }

    /**
     * Validate TOTP code format (6 digits)
     */
    private static boolean isValidCodeFormat(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }

        for (char c : code.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }

        return true;
    }
}
