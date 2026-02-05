package com.nozz.vouch.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nozz.vouch.VouchMod;
import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.AuthMode;
import com.nozz.vouch.auth.PlayerSession;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.crypto.TOTPEngine;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.util.LangManager;
import com.nozz.vouch.util.Messages;
import com.nozz.vouch.util.PermissionHelper;
import com.nozz.vouch.util.QRMapRenderer;
import com.nozz.vouch.util.UXManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-Factor Authentication commands for Vouch.
 * 
 * Commands:
 * - /2fa setup: Start 2FA setup (generates QR code)
 * - /2fa verify <code>: Verify TOTP code (for setup or login)
 * - /2fa disable: Disable 2FA (requires current code)
 * - /2fa status: Check 2FA status
 */
public final class TwoFactorCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/2FA");

    // TOTP code length (RFC 6238 standard = 6 digits)
    private static final int CODE_LENGTH = 6;

    private TwoFactorCommands() {
    }

    /**
     * Register all 2FA commands with the dispatcher
     */
    public static void registerAll(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        LOGGER.debug("Registering 2FA commands...");
        dispatcher.register(
            CommandManager.literal("2fa")
                // /2fa setup
                .then(CommandManager.literal("setup")
                    .executes(TwoFactorCommands::executeSetup)
                )
                // /2fa verify <code>
                .then(CommandManager.literal("verify")
                    .then(CommandManager.argument("code", StringArgumentType.greedyString())
                        .executes(TwoFactorCommands::executeVerify)
                    )
                )
                // /2fa disable <code>
                .then(CommandManager.literal("disable")
                    .then(CommandManager.argument("code", StringArgumentType.greedyString())
                        .executes(TwoFactorCommands::executeDisable)
                    )
                )
                // /2fa status
                .then(CommandManager.literal("status")
                    .executes(TwoFactorCommands::executeStatus)
                )
        );

        LOGGER.debug("2FA commands registered successfully");
    }

    /**
     * Handle /2fa setup command
     * 
     * Generates a TOTP secret, creates a QR code, and sends it to the player.
     * Player must be authenticated (logged in) to set up 2FA.
     * 
     * Note: In PASSWORD_ONLY mode, this command is disabled.
     */
    private static int executeSetup(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
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
            player.sendMessage(Messages.mustLoginFirst(), false);
            return 0;
        }

        db.has2FAEnabled(player.getUuid()).thenAccept(has2FA -> {
            VouchMod.getInstance().runOnMainThread(() -> {
                if (has2FA) {
                    player.sendMessage(Messages.twoFactorAlreadyEnabled(), false);
                    return;
                }

                String secret = TOTPEngine.generateSecret();
                String issuer = VouchConfigManager.getInstance().getServerName();
                String accountName = player.getName().getString();

                PlayerSession session = authManager.getSession(player.getUuid());
                if (session != null) {
                    session.setPending2FASecret(secret);
                }

                String otpAuthUri = TOTPEngine.generateOtpAuthUri(secret, accountName, issuer);

                boolean qrSent = QRMapRenderer.sendQRCodeMap(player, otpAuthUri);

                if (qrSent) {
                    player.sendMessage(Messages.twoFactorSetupInstructions(), false);
                    player.sendMessage(Messages.twoFactorManualSecret(secret), false);
                    LOGGER.info("2FA setup initiated for player {}", accountName);
                } else {
                    player.sendMessage(Messages.twoFactorSetupFailed(), false);
                    LOGGER.error("Failed to send QR code to player {}", accountName);
                }
            });
        });

        return 1;
    }

    /**
     * Handle /2fa verify <code> command
     * 
     * Verifies a TOTP code. Used for both:
     * 1. Completing 2FA setup (verifying the initial code)
     * 2. Logging in when 2FA is required
     */
    private static int executeVerify(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_VERIFY)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        String rawCode = StringArgumentType.getString(context, "code");
        String code = rawCode.replaceAll("\\s+", "");
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
            return verifySetup(player, session, pendingSecret, code);
        }

        if (session.isAwaiting2FA()) {
            return verifyLogin(player, session, code);
        }

        player.sendMessage(Messages.twoFactorNothingPending(), false);
        return 0;
    }

    /**
     * Verify TOTP code during 2FA setup
     */
    private static int verifySetup(ServerPlayerEntity player, PlayerSession session, String secret, String code) {
        if (!TOTPEngine.verifyCode(secret, code)) {
            player.sendMessage(Messages.twoFactorInvalidCode(), false);
            session.recordFailedAttempt();
            LOGGER.debug("Invalid 2FA setup code from player {}", player.getName().getString());
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        boolean is2FAOnlyRegistration = session.is2FAOnlyRegistration();

        if (is2FAOnlyRegistration) {
            db.registerPlayerWith2FA(player.getUuid(), player.getName().getString(), secret).thenAccept(success -> {
                VouchMod.getInstance().runOnMainThread(() -> {
                    if (success) {
                        session.clearPending2FASecret();
                        session.set2FAEnabled(true);
                        QRMapRenderer.removeQRMap(player);
                        AuthManager.getInstance().authenticatePlayer(player);
                        UXManager.getInstance().onRegisterSuccess(player);
                        LOGGER.info("Player {} registered with 2FA (2FA-only mode)", player.getName().getString());
                    } else {
                        player.sendMessage(Messages.databaseError(), false);
                        LOGGER.error("Failed to register player {} with 2FA", player.getName().getString());
                    }
                });
            });
        } else {
            db.storeTOTPSecret(player.getUuid(), secret).thenAccept(success -> {
                VouchMod.getInstance().runOnMainThread(() -> {
                    if (success) {
                        session.clearPending2FASecret();
                        session.set2FAEnabled(true);
                        QRMapRenderer.removeQRMap(player);

                        player.sendMessage(Messages.twoFactorEnabled(), false);
                        LOGGER.info("2FA enabled for player {}", player.getName().getString());
                    } else {
                        player.sendMessage(Messages.databaseError(), false);
                        LOGGER.error("Failed to store 2FA secret for player {}", player.getName().getString());
                    }
                });
            });
        }

        return 1;
    }

    /**
     * Verify TOTP code during login
     */
    private static int verifyLogin(ServerPlayerEntity player, PlayerSession session, String code) {
        DatabaseManager db = DatabaseManager.getInstance();

        db.getTOTPSecret(player.getUuid()).thenAccept(secretOpt -> {
            VouchMod.getInstance().runOnMainThread(() -> {
                if (secretOpt.isEmpty()) {
                    player.sendMessage(Messages.twoFactorNotEnabled(), false);
                    return;
                }

                String secret = secretOpt.get();

                if (!TOTPEngine.verifyCode(secret, code)) {
                    UXManager.getInstance().sendMessage(player, LangManager.getInstance().get("vouch.2fa.invalid_code"));
                    session.recordFailedAttempt();

                    if (session.isRateLimited()) {
                        player.sendMessage(Messages.tooManyAttempts(), false);
                    }

                    LOGGER.debug("Invalid 2FA login code from player {}", player.getName().getString());
                    return;
                }
                AuthManager.getInstance().complete2FAAuthentication(player);
                UXManager.getInstance().onLoginSuccess(player);

                String ip = getPlayerIP(player);
                db.updateLastLogin(player.getUuid(), ip);

                LOGGER.info("Player {} logged in with 2FA", player.getName().getString());
            });
        });

        return 1;
    }

    /**
     * Handle /2fa disable <code> command
     * 
     * Disables 2FA for the player after verifying their current code.
     */
    private static int executeDisable(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_DISABLE)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        String rawCode = StringArgumentType.getString(context, "code");
        String code = rawCode.replaceAll("\\s+", "");

        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.mustLoginFirst(), false);
            return 0;
        }

        if (!isValidCodeFormat(code)) {
            player.sendMessage(Messages.twoFactorInvalidCode(), false);
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        db.getTOTPSecret(player.getUuid()).thenAccept(secretOpt -> {
            VouchMod.getInstance().runOnMainThread(() -> {
                if (secretOpt.isEmpty()) {
                    player.sendMessage(Messages.twoFactorNotEnabled(), false);
                    return;
                }

                String secret = secretOpt.get();

                if (!TOTPEngine.verifyCode(secret, code)) {
                    player.sendMessage(Messages.twoFactorInvalidCode(), false);
                    LOGGER.debug("Invalid 2FA disable code from player {}", player.getName().getString());
                    return;
                }

                db.disable2FA(player.getUuid()).thenAccept(success -> {
                    VouchMod.getInstance().runOnMainThread(() -> {
                        if (success) {
                            PlayerSession session = authManager.getSession(player.getUuid());
                            if (session != null) {
                                session.set2FAEnabled(false);
                            }

                            player.sendMessage(Messages.twoFactorDisabled(), false);
                            LOGGER.info("2FA disabled for player {}", player.getName().getString());
                        } else {
                            player.sendMessage(Messages.databaseError(), false);
                        }
                    });
                });
            });
        });

        return 1;
    }

    /**
     * Handle /2fa status command
     * 
     * Shows the current 2FA status for the player.
     */
    private static int executeStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_STATUS)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.mustLoginFirst(), false);
            return 0;
        }

        DatabaseManager.getInstance().has2FAEnabled(player.getUuid()).thenAccept(has2FA -> {
            VouchMod.getInstance().runOnMainThread(() -> {
                if (has2FA) {
                    player.sendMessage(Messages.twoFactorStatusEnabled(), false);
                } else {
                    player.sendMessage(Messages.twoFactorStatusDisabled(), false);
                }
            });
        });

        return 1;
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

    private static String getPlayerIP(ServerPlayerEntity player) {
        try {
            var address = player.networkHandler.getConnectionAddress();
            if (address != null) {
                String ip = address.toString();
                if (ip.startsWith("/")) {
                    ip = ip.substring(1);
                }
                int colonIndex = ip.lastIndexOf(':');
                if (colonIndex > 0) {
                    ip = ip.substring(0, colonIndex);
                }
                return ip;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not get IP for player {}", player.getName().getString());
        }
        return "unknown";
    }
}
