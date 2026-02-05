package com.nozz.vouch.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nozz.vouch.VouchMod;
import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.AuthMode;
import com.nozz.vouch.auth.PlayerSession;
import com.nozz.vouch.auth.RateLimiter;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.crypto.Argon2Hasher;
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
 * Command registration for Vouch authentication commands.
 * 
 * Commands:
 * - /register <password> <confirmPassword>
 * - /login <password>
 * - /vouch admin reload
 * - /vouch admin unregister <player>
 * - /vouch admin export-lang
 * - /auth (alias for /vouch)
 */
public final class VouchCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Commands");

    private VouchCommands() {
    }

    /**
     * Get minimum password length from config.
     */
    private static int getMinPasswordLength() {
        return VouchConfigManager.getInstance().getPasswordMinLength();
    }

    /**
     * Get maximum password length from config.
     */
    private static int getMaxPasswordLength() {
        return VouchConfigManager.getInstance().getPasswordMaxLength();
    }

    /**
     * Register all Vouch commands with the dispatcher
     */
    public static void registerAll(CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        LOGGER.debug("Registering Vouch commands...");

        AuthMode authMode = VouchConfigManager.getInstance().getAuthMode();

        // /register command - varies by auth mode
        if (authMode.is2FARequired()) {
            // 2FA_ONLY mode: /register (no args) starts 2FA setup
            dispatcher.register(
                    CommandManager.literal("register")
                            .executes(VouchCommands::executeRegister2FAOnly));
        } else {
            // PASSWORD modes: /register <password> <confirmPassword>
            dispatcher.register(
                    CommandManager.literal("register")
                            .then(CommandManager.argument("password", StringArgumentType.string())
                                    .then(CommandManager.argument("confirmPassword", StringArgumentType.string())
                                            .executes(VouchCommands::executeRegister))));
        }

        // /login command - varies by auth mode
        if (authMode.is2FARequired()) {
            // 2FA_ONLY mode: /login <code> expects 6-digit TOTP code
            dispatcher.register(
                    CommandManager.literal("login")
                            .then(CommandManager.argument("code", StringArgumentType.greedyString())
                                    .executes(VouchCommands::executeLogin2FAOnly)));
        } else {
            // PASSWORD modes: /login <password>
            dispatcher.register(
                    CommandManager.literal("login")
                            .then(CommandManager.argument("password", StringArgumentType.string())
                                    .executes(VouchCommands::executeLogin)));
        }

        // /vouch admin commands
        dispatcher.register(
                CommandManager.literal("vouch")
                        .then(CommandManager.literal("admin")
                                .then(CommandManager.literal("reload")
                                        .requires(source -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_RELOAD))
                                        .executes(VouchCommands::executeReload))
                                .then(CommandManager.literal("unregister")
                                        .requires(source -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_UNREGISTER))
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(VouchCommands::executeUnregister)))
                                .then(CommandManager.literal("export-lang")
                                        .requires(source -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_EXPORT_LANG))
                                        .executes(VouchCommands::executeExportLang))));

        // /auth - alias for /vouch
        dispatcher.register(
                CommandManager.literal("auth")
                        .then(CommandManager.literal("admin")
                                .then(CommandManager.literal("reload")
                                        .requires(source -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_RELOAD))
                                        .executes(VouchCommands::executeReload))
                                .then(CommandManager.literal("unregister")
                                        .requires(source -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_UNREGISTER))
                                        .then(CommandManager.argument("player", StringArgumentType.word())
                                                .executes(VouchCommands::executeUnregister)))
                                .then(CommandManager.literal("export-lang")
                                        .requires(source -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_EXPORT_LANG))
                                        .executes(VouchCommands::executeExportLang))));

        // /logout - Invalidate session and disconnect
        dispatcher.register(
                CommandManager.literal("logout")
                        .executes(VouchCommands::executeLogout));

        LOGGER.debug("Commands registered successfully (mode: {})", authMode.getDisplayName());
    }

    /**
     * Handle /register command (password mode)
     */
    private static int executeRegister(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.REGISTER)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.alreadyLoggedIn(), false);
            return 0;
        }

        String ip = getPlayerIP(player);
        if (!RateLimiter.getInstance().getBlockRemaining(ip).isZero()) {
            player.sendMessage(Messages.tooManyAttempts(), false);
            return 0;
        }

        PlayerSession session = authManager.getSession(player.getUuid());

        String password = StringArgumentType.getString(context, "password");
        String confirmPassword = StringArgumentType.getString(context, "confirmPassword");

        // Validate password match
        if (!password.equals(confirmPassword)) {
            player.sendMessage(Messages.passwordMismatch(), false);
            if (session != null)
                session.recordFailedAttempt();
            return 0;
        }

        int minLen = getMinPasswordLength();
        int maxLen = getMaxPasswordLength();

        if (password.length() < minLen) {
            player.sendMessage(Messages.passwordTooShort(minLen), false);
            return 0;
        }

        if (password.length() > maxLen) {
            player.sendMessage(Messages.passwordTooLong(maxLen), false);
            return 0;
        }

        // Check if already registered
        DatabaseManager db = DatabaseManager.getInstance();
        sendProcessingMessage(player);

        db.isRegistered(player.getUuid()).thenAccept(isRegistered -> {
            if (isRegistered) {
                // Must run on main thread for player interaction
                VouchMod.getInstance().runOnMainThread(() -> {
                    player.sendMessage(Messages.alreadyRegistered(), false);
                });
                return;
            }

            // Hash password asynchronously
            Argon2Hasher.hashAsync(password).thenAccept(hash -> {
                // Register player in database
                db.registerPlayer(player.getUuid(), player.getName().getString(), hash).thenAccept(success -> {
                    VouchMod.getInstance().runOnMainThread(() -> {
                        if (success) {
                            authManager.authenticatePlayer(player);
                            UXManager.getInstance().onRegisterSuccess(player);
                            LOGGER.info("Player {} registered successfully", player.getName().getString());
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
     * Handle /register command (2FA-only mode)
     * Generates TOTP secret and shows QR code immediately.
     */
    private static int executeRegister2FAOnly(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.REGISTER)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();
        DatabaseManager db = DatabaseManager.getInstance();

        // Check if already authenticated
        if (authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.alreadyLoggedIn(), false);
            return 0;
        }

        // Check global rate limiting
        String ip = getPlayerIP(player);
        if (!RateLimiter.getInstance().getBlockRemaining(ip).isZero()) {
            player.sendMessage(Messages.tooManyAttempts(), false);
            return 0;
        }

        sendProcessingMessage(player);

        // Check if already registered
        db.isRegistered(player.getUuid()).thenAccept(isRegistered -> {
            VouchMod.getInstance().runOnMainThread(() -> {
                if (isRegistered) {
                    player.sendMessage(Messages.alreadyRegistered(), false);
                    return;
                }

                // Generate TOTP secret
                String secret = TOTPEngine.generateSecret();
                String issuer = VouchConfigManager.getInstance().getServerName();
                String accountName = player.getName().getString();

                // Store the pending secret in the session
                PlayerSession session = authManager.getSession(player.getUuid());
                if (session != null) {
                    session.setPending2FASecret(secret);
                    session.markAs2FAOnlyRegistration(); 
                }

                String otpAuthUri = TOTPEngine.generateOtpAuthUri(secret, accountName, issuer);

                boolean qrSent = QRMapRenderer.sendQRCodeMap(player, otpAuthUri);

                if (qrSent) {
                    player.sendMessage(Messages.twoFactorSetupInstructions(), false);
                    player.sendMessage(Messages.twoFactorManualSecret(secret), false);
                    LOGGER.info("2FA-only registration initiated for player {}", accountName);
                } else {
                    player.sendMessage(Messages.twoFactorSetupFailed(), false);
                    LOGGER.error("Failed to send QR code to player {}", accountName);
                }
            });
        });

        return 1;
    }

    /**
     * Handle /login command (password mode)
     */
    private static int executeLogin(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGIN)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.alreadyLoggedIn(), false);
            return 0;
        }

        String ip = getPlayerIP(player);
        var remaining = RateLimiter.getInstance().getBlockRemaining(ip);
        if (!remaining.isZero()) {
            player.sendMessage(Messages.tooManyAttempts(), false);
            return 0;
        }

        String password = StringArgumentType.getString(context, "password");
        DatabaseManager db = DatabaseManager.getInstance();

        sendProcessingMessage(player);

        db.isRegistered(player.getUuid()).thenAccept(isRegistered -> {
            if (!isRegistered) {
                VouchMod.getInstance().runOnMainThread(() -> {
                    player.sendMessage(Messages.notRegistered(), false);
                });
                return;
            }

            db.getPasswordHash(player.getUuid()).thenAccept(hashOptional -> {
                if (hashOptional.isEmpty()) {
                    VouchMod.getInstance().runOnMainThread(() -> {
                        player.sendMessage(Messages.databaseError(), false);
                    });
                    return;
                }

                String storedHash = hashOptional.get();

                Argon2Hasher.verifyAsync(password, storedHash).thenAccept(valid -> {
                    VouchMod.getInstance().runOnMainThread(() -> {
                        if (valid) {
            
                            RateLimiter.getInstance().recordSuccess(ip);

                            db.has2FAEnabled(player.getUuid()).thenAccept(has2FA -> {
                                VouchMod.getInstance().runOnMainThread(() -> {
                                    if (has2FA) {

                                        authManager.require2FA(player);
                                        player.sendMessage(
                                                LangManager.getInstance().getTextWithPrefix("vouch.2fa.required"),
                                                false);
                                        LOGGER.debug("Player {} requires 2FA verification",
                                                player.getName().getString());
                                    } else {
                                        VouchConfigManager config = VouchConfigManager.getInstance();
                                        if (config.isRequire2FAForOps() && isPlayerOp(player)) {

                                            authManager.authenticatePlayer(player);
                                            player.sendMessage(LangManager.getInstance()
                                                    .getTextWithPrefix("vouch.2fa.required_for_ops"), false);
                                            LOGGER.info("OP {} logged in, 2FA setup required",
                                                    player.getName().getString());
                                        } else {

                                            authManager.authenticatePlayer(player);
                                            UXManager.getInstance().onLoginSuccess(player);
                                            LOGGER.info("Player {} logged in successfully",
                                                    player.getName().getString());
                                        }

                                        db.updateLastLogin(player.getUuid(), ip);
                                    }
                                });
                            });
                        } else {
                            RateLimiter.getInstance().recordFailure(ip);
                            UXManager.getInstance().onWrongPassword(player);
                            LOGGER.warn("Failed login attempt for player {}", player.getName().getString());
                        }
                    });
                });
            });
        });

        return 1;
    }

    /**
     * Handle /login <code> command (2FA-only mode)
     * Verifies TOTP code for login.
     */
    private static int executeLogin2FAOnly(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGIN)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.alreadyLoggedIn(), false);
            return 0;
        }

        String ip = getPlayerIP(player);
        var remaining = RateLimiter.getInstance().getBlockRemaining(ip);
        if (!remaining.isZero()) {
            player.sendMessage(Messages.tooManyAttempts(), false);
            return 0;
        }

        String rawCode = StringArgumentType.getString(context, "code");
        String code = rawCode.replaceAll("\\s+", "");

        if (!isValidTOTPCode(code)) {
            player.sendMessage(Messages.twoFactorInvalidCode(), false);
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        sendProcessingMessage(player);
        db.isRegistered(player.getUuid()).thenAccept(isRegistered -> {
            if (!isRegistered) {
                VouchMod.getInstance().runOnMainThread(() -> {
                    player.sendMessage(Messages.notRegistered(), false);
                });
                return;
            }

            db.getTOTPSecret(player.getUuid()).thenAccept(secretOpt -> {
                VouchMod.getInstance().runOnMainThread(() -> {
                    if (secretOpt.isEmpty()) {
                        player.sendMessage(Messages.twoFactorNotEnabled(), false);
                        return;
                    }

                    String secret = secretOpt.get();

                    if (TOTPEngine.verifyCode(secret, code)) {
                        RateLimiter.getInstance().recordSuccess(ip);
                        authManager.authenticatePlayer(player);
                        UXManager.getInstance().onLoginSuccess(player);
                        db.updateLastLogin(player.getUuid(), ip);
                        LOGGER.info("Player {} logged in with 2FA (2FA-only mode)", player.getName().getString());
                    } else {
                        RateLimiter.getInstance().recordFailure(ip);
                        UXManager.getInstance().onWrongPassword(player);
                        LOGGER.warn("Failed 2FA login attempt for player {}", player.getName().getString());
                    }
                });
            });
        });

        return 1;
    }

    /**
     * Validate TOTP code format (6 digits)
     */
    private static boolean isValidTOTPCode(String code) {
        if (code == null || code.length() != 6) {
            return false;
        }
        for (char c : code.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Send processing message if enabled in config
     */
    private static void sendProcessingMessage(ServerPlayerEntity player) {
        if (VouchConfigManager.getInstance().showProcessingMessage()) {
            player.sendMessage(Messages.processing(), false);
        }
    }

    /**
     * Handle /vouch admin reload
     */
    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        try {
            VouchConfigManager.getInstance().reload();
            LangManager.getInstance().reload();
            source.sendMessage(Messages.configReloaded());
            LOGGER.info("Configuration reloaded by {}", source.getName());
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to reload configuration", e);
            source.sendMessage(Messages.databaseError());
            return 0;
        }
    }

    /**
     * Handle /vouch admin export-lang
     * Exports current language file to config directory for customization.
     */
    private static int executeExportLang(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        try {
            String langCode = LangManager.getInstance().getCurrentLanguage();
            var langDir = VouchConfigManager.getInstance().getLangDir();
            var exportPath = langDir.resolve(langCode + ".json");

            LangManager.getInstance().exportToFile(exportPath);
            source.sendMessage(Messages.langExported(exportPath.toString()));
            LOGGER.info("Language file exported to {} by {}", exportPath, source.getName());
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to export language file", e);
            source.sendMessage(Messages.databaseError());
            return 0;
        }
    }

    /**
     * Handle /logout command
     * Invalidates the player's persistent session and disconnects them.
     */
    private static int executeLogout(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!source.isExecutedByPlayer()) {
            return 0;
        }

        // Check permission
        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGOUT)) {
            source.sendMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendMessage(Messages.mustLoginFirst(), false);
            return 0;
        }

        authManager.logout(player).thenAccept(v -> {
            VouchMod.getInstance().runOnMainThread(() -> {
                // Disconnect the player after logout
                player.networkHandler.disconnect(Messages.logoutKick());
                LOGGER.info("Player {} logged out and disconnected", player.getName().getString());
            });
        });

        return 1;
    }

    /**
     * Handle /vouch admin unregister <player>
     */
    private static int executeUnregister(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String playerName = StringArgumentType.getString(context, "player");

        var server = source.getServer();
        var userCache = server.getUserCache();

        if (userCache == null) {
            source.sendMessage(Messages.databaseError());
            return 0;
        }

        userCache.findByNameAsync(playerName).thenAccept(profileOptional -> {
            if (profileOptional.isEmpty()) {
                VouchMod.getInstance().runOnMainThread(() -> {
                    source.sendMessage(Messages.playerNotFound(playerName));
                });
                return;
            }

            var uuid = profileOptional.get().getId();
            DatabaseManager.getInstance().unregisterPlayer(uuid).thenAccept(success -> {
                VouchMod.getInstance().runOnMainThread(() -> {
                    if (success) {
                        source.sendMessage(Messages.playerUnregistered(playerName));
                        LOGGER.info("Player {} unregistered by admin {}", playerName, source.getName());

                        ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(uuid);
                        if (onlinePlayer != null) {
                            AuthManager.getInstance().removePlayer(uuid);
                            onlinePlayer.networkHandler.disconnect(Messages.notRegistered());
                        }
                    } else {
                        source.sendMessage(Messages.playerNotFound(playerName));
                    }
                });
            });
        });

        return 1;
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

    /**
     * Check if a player is a server operator.
     */
    private static boolean isPlayerOp(ServerPlayerEntity player) {
        try {
            var server = player.getServer();
            if (server != null) {
                return server.getPlayerManager().isOperator(player.getGameProfile());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not check OP status for player {}", player.getName().getString());
        }
        return false;
    }
}
