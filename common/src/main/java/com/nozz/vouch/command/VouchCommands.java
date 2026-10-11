package com.nozz.vouch.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nozz.vouch.VouchMod;
import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.AuthMode;
import com.nozz.vouch.auth.LoginFacts;
import com.nozz.vouch.auth.LoginOutcome;
import com.nozz.vouch.auth.LoginPolicy;
import com.nozz.vouch.auth.PlayerSession;
import com.nozz.vouch.auth.RateLimiter;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.crypto.Argon2Hasher;
import com.nozz.vouch.crypto.TOTPEngine;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.db.PlayerRecord;
import com.nozz.vouch.db.PremiumOverrideStatus;
import com.nozz.vouch.util.LangManager;
import com.nozz.vouch.util.Messages;
import com.nozz.vouch.util.PermissionHelper;
import com.nozz.vouch.util.PlayerIps;
import com.nozz.vouch.util.PremiumVerifier;
import com.nozz.vouch.util.QRMapRenderer;
import com.nozz.vouch.util.UXManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import static com.nozz.vouch.command.VouchCommandTree.ARG_CODE;
import static com.nozz.vouch.command.VouchCommandTree.ARG_CONFIRM_PASSWORD;
import static com.nozz.vouch.command.VouchCommandTree.ARG_NEW_PASSWORD;
import static com.nozz.vouch.command.VouchCommandTree.ARG_OLD_PASSWORD;
import static com.nozz.vouch.command.VouchCommandTree.ARG_PASSWORD;
import static com.nozz.vouch.command.VouchCommandTree.ARG_PLAYER;

/**
 * The executors behind Vouch's commands.
 *
 * <p>The <em>shape</em> of the command surface lives in {@link VouchCommandTree};
 * this class supplies what a running server has to bring to it — the permission and
 * auth-state predicates, the player-name completion source, and the code that
 * actually does the work. Splitting the two is what lets the tree be tested.
 *
 * <p>Every asynchronous chain here goes through {@link VouchMod#onMain}, so a
 * database failure ends with a log line and a message rather than silence.
 */
public final class VouchCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Commands");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

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
     * Register every Vouch command, including the {@code /auth} and {@code /2fa}
     * redirects and the {@code /l} and {@code /reg} aliases.
     */
    public static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        AuthMode authMode = VouchConfigManager.getInstance().getAuthMode();

        VouchCommandTree.register(dispatcher, authMode, new ServerHooks());

        LOGGER.debug("Commands registered successfully (mode: {})", authMode.getDisplayName());
    }

    /**
     * What the tree needs from a live server.
     *
     * <p>The executor map is built once per registration because
     * {@link VouchCommandTree.Hooks#action} must hand back the same object for a leaf
     * every time it is asked: a leaf reachable from several paths — a legacy alias, or
     * through the {@code /auth} redirect — has to be one behaviour.
     */
    private static final class ServerHooks implements VouchCommandTree.Hooks<CommandSourceStack> {

        private final Map<VouchCommandTree.Leaf, Command<CommandSourceStack>> actions =
                new EnumMap<>(VouchCommandTree.Leaf.class);
        private final SuggestionProvider<CommandSourceStack> players =
                RegisteredNameSuggestions.from(RegisteredNames.getInstance());

        ServerHooks() {
            actions.put(VouchCommandTree.Leaf.LOGIN, VouchCommands::executeLogin);
            actions.put(VouchCommandTree.Leaf.LOGIN_2FA_ONLY, VouchCommands::executeLogin2FAOnly);
            actions.put(VouchCommandTree.Leaf.REGISTER, VouchCommands::executeRegister);
            actions.put(VouchCommandTree.Leaf.REGISTER_2FA_ONLY, VouchCommands::executeRegister2FAOnly);
            actions.put(VouchCommandTree.Leaf.LOGOUT, VouchCommands::executeLogout);

            actions.put(VouchCommandTree.Leaf.TWO_FACTOR_SETUP, TwoFactorCommands::executeSetup);
            actions.put(VouchCommandTree.Leaf.TWO_FACTOR_VERIFY, TwoFactorCommands::executeVerify);
            actions.put(VouchCommandTree.Leaf.TWO_FACTOR_DISABLE, TwoFactorCommands::executeDisable);
            actions.put(VouchCommandTree.Leaf.TWO_FACTOR_STATUS, TwoFactorCommands::executeStatus);

            actions.put(VouchCommandTree.Leaf.PASSWORD, VouchCommands::executeChangePassword);
            actions.put(VouchCommandTree.Leaf.PREMIUM, VouchCommands::executeMarkAsOnline);

            actions.put(VouchCommandTree.Leaf.ADMIN_RELOAD, VouchCommands::executeReload);
            actions.put(VouchCommandTree.Leaf.ADMIN_EXPORT_LANG, VouchCommands::executeExportLang);
            actions.put(VouchCommandTree.Leaf.ADMIN_USER_INFO, VouchCommands::executeUserInfo);
            actions.put(VouchCommandTree.Leaf.ADMIN_USER_UNREGISTER, VouchCommands::executeUnregister);
            actions.put(VouchCommandTree.Leaf.ADMIN_USER_RESETPW, VouchCommands::executeResetPassword);
            actions.put(VouchCommandTree.Leaf.ADMIN_PREMIUM_FORCE_OFFLINE, VouchCommands::executeForceOffline);
            actions.put(VouchCommandTree.Leaf.ADMIN_PREMIUM_FORCE_ONLINE, VouchCommands::executeForceOnline);
        }

        @Override
        public Predicate<CommandSourceStack> requirement(VouchCommand command) {
            return CommandVisibility.requirementFor(command);
        }

        @Override
        public Command<CommandSourceStack> action(VouchCommandTree.Leaf leaf) {
            Command<CommandSourceStack> action = actions.get(leaf);
            if (action == null) {
                throw new IllegalStateException("No executor wired for command leaf " + leaf);
            }
            return action;
        }

        @Override
        public SuggestionProvider<CommandSourceStack> registeredPlayerNames() {
            return players;
        }
    }

    // ==================== Registration ====================

    /**
     * Handle /register command (password mode)
     */
    private static int executeRegister(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.REGISTER)) {
            source.sendSystemMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Messages.alreadyLoggedIn());
            return 0;
        }

        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        PlayerSession session = authManager.getSession(player.getUUID());

        String password = StringArgumentType.getString(context, ARG_PASSWORD);
        String confirmPassword = StringArgumentType.getString(context, ARG_CONFIRM_PASSWORD);

        // Validate password match
        if (!password.equals(confirmPassword)) {
            player.sendSystemMessage(Messages.passwordMismatch());
            if (session != null)
                session.recordFailedAttempt();
            return 0;
        }

        if (!isPasswordLengthAcceptable(player, password)) {
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        String username = player.getName().getString();
        sendProcessingMessage(player);

        VouchMod mod = VouchMod.getInstance();
        mod.onMain(db.isRegistered(player.getUUID()), "registration check for " + username, player, isRegistered -> {
            RegisteredNames.getInstance().setPresent(username, isRegistered);
            if (isRegistered) {
                player.sendSystemMessage(Messages.alreadyRegistered());
                CommandVisibility.resend(player);
                return;
            }

            mod.onMain(Argon2Hasher.hashAsync(password)
                            .thenCompose(hash -> db.registerPlayer(player.getUUID(), username, hash)),
                    "registration of " + username, player, success -> {
                        if (!success) {
                            player.sendSystemMessage(Messages.databaseError());
                            return;
                        }
                        RegisteredNames.getInstance().add(username);
                        authManager.authenticatePlayer(player);
                        UXManager.getInstance().onRegisterSuccess(player);
                        CommandVisibility.resend(player);
                        LOGGER.info("Player {} registered successfully", username);
                    });
        });

        return 1;
    }

    /**
     * Handle /register command (2FA-only mode)
     * Generates TOTP secret and shows QR code immediately.
     */
    private static int executeRegister2FAOnly(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.REGISTER)) {
            source.sendSystemMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();
        DatabaseManager db = DatabaseManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Messages.alreadyLoggedIn());
            return 0;
        }

        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        String username = player.getName().getString();
        sendProcessingMessage(player);

        VouchMod.getInstance().onMain(db.isRegistered(player.getUUID()),
                "registration check for " + username, player, isRegistered -> {
                    RegisteredNames.getInstance().setPresent(username, isRegistered);
                    if (isRegistered) {
                        player.sendSystemMessage(Messages.alreadyRegistered());
                        CommandVisibility.resend(player);
                        return;
                    }

                    String secret = TOTPEngine.generateSecret();
                    String issuer = VouchConfigManager.getInstance().getServerName();

                    // Store the pending secret in the session
                    PlayerSession session = authManager.getSession(player.getUUID());
                    if (session != null) {
                        session.setPending2FASecret(secret);
                        session.markAs2FAOnlyRegistration();
                    }

                    String otpAuthUri = TOTPEngine.generateOtpAuthUri(secret, username, issuer);

                    if (QRMapRenderer.sendQRCodeMap(player, otpAuthUri)) {
                        player.sendSystemMessage(Messages.twoFactorSetupInstructionsClickable());
                        player.sendSystemMessage(Messages.twoFactorManualSecret(secret));
                        CommandVisibility.resend(player);
                        LOGGER.info("2FA-only registration initiated for player {}", username);
                    } else {
                        player.sendSystemMessage(Messages.twoFactorSetupFailed());
                        LOGGER.error("Failed to send QR code to player {}", username);
                    }
                });

        return 1;
    }

    // ==================== Login ====================

    /**
     * Handle /login command (password mode).
     *
     * <p>The nested conditionals this replaced are now {@link LoginPolicy}: facts are
     * gathered here, the judgement is made there, and {@link #applyLoginOutcome}
     * carries it out. The rate limiter is touched only through
     * {@link RateLimitDecision}, which is what stopped a correct password from
     * clearing the counter before the second factor had been checked.
     */
    private static int executeLogin(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGIN)) {
            source.sendSystemMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Messages.alreadyLoggedIn());
            return 0;
        }

        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        String password = StringArgumentType.getString(context, ARG_PASSWORD);
        String username = player.getName().getString();
        DatabaseManager db = DatabaseManager.getInstance();
        VouchMod mod = VouchMod.getInstance();

        sendProcessingMessage(player);

        mod.onMain(db.isRegistered(player.getUUID()), "login lookup for " + username, player, isRegistered -> {
            RegisteredNames.getInstance().setPresent(username, isRegistered);
            if (!isRegistered) {
                applyLoginOutcome(player, ip, LoginOutcome.NOT_REGISTERED);
                return;
            }

            mod.onMain(db.getPasswordHash(player.getUUID()), "password lookup for " + username, player, hashOptional -> {
                if (hashOptional.isEmpty()) {
                    applyLoginOutcome(player, ip, LoginOutcome.CREDENTIALS_UNAVAILABLE);
                    return;
                }

                String storedHash = hashOptional.get();

                mod.onMain(Argon2Hasher.verifyAsync(password, storedHash),
                        "password verification for " + username, player, valid -> {
                            if (!valid) {
                                applyLoginOutcome(player, ip, LoginOutcome.WRONG_PASSWORD);
                                return;
                            }

                            // The one moment the plain text exists and is known correct.
                            upgradeStoredHashIfStale(player, password, storedHash);

                            // has2FAEnabled fails closed: an unreachable database must not
                            // be read as "this player has no second factor".
                            mod.onMain(db.has2FAEnabled(player.getUUID()),
                                    "2FA status for " + username, player, has2FA -> {
                                        PlayerSession session = authManager.getSession(player.getUUID());
                                        if (session != null) {
                                            session.set2FAEnabled(has2FA);
                                        }

                                        LoginFacts facts = LoginFacts.builder()
                                                .registered(true)
                                                .passwordHashPresent(true)
                                                .passwordValid(true)
                                                .twoFactorEnabled(has2FA)
                                                .operator(isPlayerOp(player))
                                                .require2faForOps(VouchConfigManager.getInstance().isRequire2FAForOps())
                                                .build();

                                        applyLoginOutcome(player, ip, LoginPolicy.decide(facts));
                                    },
                                    () -> {
                                        // Fail closed: no access on an unresolvable 2FA state.
                                        player.sendSystemMessage(Messages.databaseConnectionError());
                                        LOGGER.error("Refusing to authenticate {} - 2FA status unknown", username);
                                    });
                        });
            });
        });

        return 1;
    }

    /** Carry out a login decision: rate limiter, player feedback, auth state, tree. */
    private static void applyLoginOutcome(ServerPlayer player, Optional<String> ip, LoginOutcome outcome) {
        if (player.hasDisconnected()) {
            return;
        }

        RateLimitDecision.forLogin(outcome).applyTo(RateLimiter.getInstance(), ip);

        AuthManager authManager = AuthManager.getInstance();
        DatabaseManager db = DatabaseManager.getInstance();
        String username = player.getName().getString();

        switch (outcome) {
            case NOT_REGISTERED -> player.sendSystemMessage(Messages.notRegisteredClickable());

            case CREDENTIALS_UNAVAILABLE -> {
                player.sendSystemMessage(Messages.databaseError());
                LOGGER.error("Account {} exists but its password hash could not be read", username);
            }

            case WRONG_PASSWORD -> {
                UXManager.getInstance().onWrongPassword(player);
                LOGGER.warn("Failed login attempt for player {}", username);
            }

            case NEEDS_2FA -> {
                authManager.require2FA(player);
                player.sendSystemMessage(Messages.twoFactorRequiredClickable());
                CommandVisibility.resend(player);
                LOGGER.debug("Player {} requires 2FA verification", username);
            }

            case AUTHENTICATED_MUST_SETUP_2FA -> {
                authManager.authenticatePlayer(player);
                player.sendSystemMessage(Messages.twoFactorRequiredForOpsClickable());
                recordLastLogin(db, player, ip);
                CommandVisibility.resend(player);
                LOGGER.info("OP {} logged in, 2FA setup required", username);
            }

            case AUTHENTICATED -> {
                authManager.authenticatePlayer(player);
                UXManager.getInstance().onLoginSuccess(player);
                recordLastLogin(db, player, ip);
                CommandVisibility.resend(player);
                LOGGER.info("Player {} logged in successfully", username);
            }
        }
    }

    /**
     * Transparently re-hash a password whose stored form no longer matches the
     * configured Argon2 parameters — a legacy parameterless hash, or one written
     * before the cost settings were raised.
     *
     * <p>This is the only place it can happen: a hash can only be recomputed from the
     * plain text, and the plain text only exists for the instant a login is verified.
     * It runs alongside the rest of the login rather than in front of it, because a
     * failed upgrade must never keep a player with the right password out.
     */
    private static void upgradeStoredHashIfStale(ServerPlayer player, String password, String storedHash) {
        if (!Argon2Hasher.needsRehash(storedHash)) {
            return;
        }
        UUID uuid = player.getUUID();
        String username = player.getName().getString();
        Argon2Hasher.hashAsync(password)
                .thenCompose(upgraded -> DatabaseManager.getInstance().updatePassword(uuid, upgraded))
                .whenComplete((stored, error) -> {
                    if (error != null) {
                        LOGGER.error("Could not upgrade the stored password hash for {}", username, error);
                    } else if (Boolean.TRUE.equals(stored)) {
                        LOGGER.info("Upgraded the stored password hash for {} to current parameters", username);
                    }
                });
    }

    /**
     * Handle /login <code> command (2FA-only mode)
     * Verifies TOTP code for login.
     */
    private static int executeLogin2FAOnly(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGIN)) {
            source.sendSystemMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Messages.alreadyLoggedIn());
            return 0;
        }

        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        String code = StringArgumentType.getString(context, ARG_CODE).replaceAll("\\s+", "");

        if (!isValidTOTPCode(code)) {
            player.sendSystemMessage(Messages.twoFactorInvalidCode());
            return 0;
        }

        String username = player.getName().getString();
        DatabaseManager db = DatabaseManager.getInstance();
        VouchMod mod = VouchMod.getInstance();
        sendProcessingMessage(player);

        mod.onMain(db.isRegistered(player.getUUID()), "login lookup for " + username, player, isRegistered -> {
            RegisteredNames.getInstance().setPresent(username, isRegistered);
            if (!isRegistered) {
                player.sendSystemMessage(Messages.notRegisteredClickable());
                RateLimitDecision.forLogin(LoginOutcome.NOT_REGISTERED)
                        .applyTo(RateLimiter.getInstance(), ip);
                return;
            }

            mod.onMain(db.getTOTPSecret(player.getUUID()), "2FA secret for " + username, player, secretOpt -> {
                if (secretOpt.isEmpty()) {
                    player.sendSystemMessage(Messages.twoFactorNotEnabled());
                    return;
                }

                boolean valid = TOTPEngine.verifyCode(secretOpt.get(), code);
                RateLimitDecision.forTwoFactorCode(valid).applyTo(RateLimiter.getInstance(), ip);

                if (!valid) {
                    UXManager.getInstance().onWrongPassword(player);
                    LOGGER.warn("Failed 2FA login attempt for player {}", username);
                    return;
                }

                PlayerSession session = authManager.getSession(player.getUUID());
                if (session != null) {
                    session.set2FAEnabled(true);
                }
                authManager.authenticatePlayer(player);
                UXManager.getInstance().onLoginSuccess(player);
                recordLastLogin(db, player, ip);
                CommandVisibility.resend(player);
                LOGGER.info("Player {} logged in with 2FA (2FA-only mode)", username);
            });
        });

        return 1;
    }

    // ==================== Password change ====================

    /**
     * Handle {@code /vouch password <old> <new>}.
     *
     * <p>New in this restructure: there was previously no way for a player to rotate
     * their own credential without an administrator resetting it.
     */
    private static int executeChangePassword(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Messages.mustLoginFirstClickable());
            return 0;
        }

        AuthMode mode = VouchConfigManager.getInstance().getAuthMode();
        if (!mode.usesPassword()) {
            player.sendSystemMessage(Messages.disabledPassword());
            return 0;
        }

        Optional<String> ip = PlayerIps.of(player);
        if (isRateLimited(player, ip)) {
            return 0;
        }

        String oldPassword = StringArgumentType.getString(context, ARG_OLD_PASSWORD);
        String newPassword = StringArgumentType.getString(context, ARG_NEW_PASSWORD);

        if (oldPassword.equals(newPassword)) {
            player.sendSystemMessage(Messages.passwordUnchanged());
            return 0;
        }

        if (!isPasswordLengthAcceptable(player, newPassword)) {
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        VouchMod mod = VouchMod.getInstance();
        String username = player.getName().getString();
        sendProcessingMessage(player);

        mod.onMain(db.getPasswordHash(player.getUUID()), "password lookup for " + username, player, hashOptional -> {
            if (hashOptional.isEmpty()) {
                player.sendSystemMessage(Messages.databaseError());
                return;
            }

            mod.onMain(Argon2Hasher.verifyAsync(oldPassword, hashOptional.get()),
                    "password verification for " + username, player, valid -> {
                        RateLimitDecision.forPasswordChange(valid).applyTo(RateLimiter.getInstance(), ip);
                        if (!valid) {
                            player.sendSystemMessage(Messages.passwordWrongCurrent());
                            LOGGER.warn("Failed password change for {} - wrong current password", username);
                            return;
                        }

                        mod.onMain(Argon2Hasher.hashAsync(newPassword)
                                        .thenCompose(hash -> db.updatePassword(player.getUUID(), hash)),
                                "password change for " + username, player, stored -> {
                                    if (stored) {
                                        player.sendSystemMessage(Messages.passwordChanged());
                                        LOGGER.info("Player {} changed their password", username);
                                    } else {
                                        player.sendSystemMessage(Messages.databaseError());
                                    }
                                });
                    });
        });

        return 1;
    }

    // ==================== Logout ====================

    /**
     * Handle /logout command
     * Invalidates the player's persistent session and disconnects them.
     */
    private static int executeLogout(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        if (!PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGOUT)) {
            source.sendSystemMessage(Messages.noPermission());
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Messages.mustLoginFirstClickable());
            return 0;
        }

        String username = player.getName().getString();

        // The in-memory session is already gone by the time this future settles, so
        // the tree the player is holding is stale even though they are about to be
        // disconnected — resend before the kick so a failed disconnect cannot leave
        // them looking at commands they can no longer run.
        CommandVisibility.resend(player);

        VouchMod.getInstance().onMain(authManager.logout(player), "logout of " + username, player, ignored -> {
            player.connection.disconnect(Messages.logoutKick());
            LOGGER.info("Player {} logged out and disconnected", username);
        }, () -> {
            // The in-memory session is gone regardless; disconnect anyway rather than
            // leaving a half-logged-out player in the world.
            player.connection.disconnect(Messages.logoutKick());
            LOGGER.warn("Player {} logged out but persistent sessions may remain", username);
        });

        return 1;
    }

    // ==================== Administration ====================

    /**
     * Handle /vouch admin reload
     */
    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            VouchConfigManager.getInstance().reload();
            LangManager.getInstance().reload();
            source.sendSystemMessage(Messages.configReloaded());
            LOGGER.info("Configuration reloaded by {}", source.getTextName());
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to reload configuration", e);
            source.sendSystemMessage(Messages.databaseError());
            return 0;
        }
    }

    /**
     * Handle /vouch admin export-lang
     * Exports current language file to config directory for customization.
     */
    private static int executeExportLang(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            String langCode = LangManager.getInstance().getCurrentLanguage();
            var langDir = VouchConfigManager.getInstance().getLangDir();
            var exportPath = langDir.resolve(langCode + ".json");

            LangManager.getInstance().exportToFile(exportPath);
            source.sendSystemMessage(Messages.langExported(exportPath.toString()));
            LOGGER.info("Language file exported to {} by {}", exportPath, source.getTextName());
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to export language file", e);
            source.sendSystemMessage(Messages.databaseError());
            return 0;
        }
    }

    /** Handle {@code /vouch admin user <player> info}. */
    private static int executeUserInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerName = StringArgumentType.getString(context, ARG_PLAYER);

        withRegisteredAccount(source, playerName, record -> {
            source.sendSystemMessage(Messages.userInfoHeader(record.username()));
            source.sendSystemMessage(Messages.userInfoUuid(record.uuid().toString()));
            source.sendSystemMessage(Messages.userInfoTwoFactor(
                    record.twoFactorEnabled() ? Messages.valueYes() : Messages.valueNo()));
            source.sendSystemMessage(Messages.userInfoLastLogin(
                    record.lastLogin().map(VouchCommands::formatInstant).orElseGet(Messages::valueNever)));
            source.sendSystemMessage(Messages.userInfoLastIp(
                    record.lastIp().orElseGet(Messages::valueNever)));

            VouchMod.getInstance().onMain(
                    DatabaseManager.getInstance().getPremiumOverrideStatus(record.username()),
                    "premium override lookup for " + record.username(), source,
                    status -> source.sendSystemMessage(Messages.userInfoPremium(
                            status == PremiumOverrideStatus.FORCED_OFFLINE
                                    ? Messages.valueNo()
                                    : Messages.valueYes())));
        });

        return 1;
    }

    /**
     * Handle {@code /vouch admin user <player> unregister}, and its pre-restructure
     * spelling {@code /vouch admin unregister <player>}.
     */
    private static int executeUnregister(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerName = StringArgumentType.getString(context, ARG_PLAYER);

        withRegisteredAccount(source, playerName, record -> VouchMod.getInstance().onMain(
                DatabaseManager.getInstance().unregisterPlayer(record.uuid()),
                "unregistration of " + record.username(), source,
                success -> {
                    if (!success) {
                        source.sendSystemMessage(Messages.playerNotFound(playerName));
                        return;
                    }

                    RegisteredNames.getInstance().remove(record.username());
                    source.sendSystemMessage(Messages.playerUnregistered(record.username()));
                    LOGGER.info("Player {} unregistered by admin {}", record.username(), source.getTextName());

                    var server = source.getServer();
                    ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(record.uuid());
                    if (onlinePlayer != null) {
                        AuthManager.getInstance().removePlayer(record.uuid());
                        onlinePlayer.connection.disconnect(Messages.notRegistered());
                    }
                }));

        return 1;
    }

    /**
     * Handle {@code /vouch admin user <player> resetpw <new>}.
     *
     * <p>Existing persistent sessions are dropped as well: a password reset that left
     * a stolen session token working would not be a reset.
     */
    private static int executeResetPassword(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String playerName = StringArgumentType.getString(context, ARG_PLAYER);
        String newPassword = StringArgumentType.getString(context, ARG_NEW_PASSWORD);

        int minLen = getMinPasswordLength();
        int maxLen = getMaxPasswordLength();
        if (newPassword.length() < minLen) {
            source.sendFailure(Messages.passwordTooShort(minLen));
            return 0;
        }
        if (newPassword.length() > maxLen) {
            source.sendFailure(Messages.passwordTooLong(maxLen));
            return 0;
        }

        DatabaseManager db = DatabaseManager.getInstance();
        VouchMod mod = VouchMod.getInstance();

        withRegisteredAccount(source, playerName, record -> mod.onMain(
                Argon2Hasher.hashAsync(newPassword)
                        .thenCompose(hash -> db.updatePassword(record.uuid(), hash)),
                "password reset for " + record.username(), source,
                stored -> {
                    if (!stored) {
                        source.sendFailure(Messages.databaseError());
                        return;
                    }
                    RegisteredNames.getInstance().add(record.username());
                    source.sendSystemMessage(Messages.passwordResetFor(record.username()));
                    LOGGER.info("Password for {} reset by admin {}", record.username(), source.getTextName());

                    mod.onMain(db.deleteAllSessions(record.uuid()),
                            "session invalidation for " + record.username(), source,
                            deleted -> LOGGER.info("Invalidated {} persistent session(s) for {}",
                                    deleted, record.username()));
                }));

        return 1;
    }

    /**
     * Handle /vouch admin premium <player> force-offline
     * Marks a player username as forced-offline, skipping premium auto-login.
     */
    private static int executeForceOffline(CommandContext<CommandSourceStack> context) {
        return setForcedOffline(context, true);
    }

    /**
     * Handle /vouch admin premium <player> force-online
     * Removes forced-offline status, re-enabling premium auto-login for a player.
     */
    private static int executeForceOnline(CommandContext<CommandSourceStack> context) {
        return setForcedOffline(context, false);
    }

    /**
     * The premium override is keyed by username in its own table, and is deliberately
     * allowed for a name with no account yet: an operator may want to pin a name
     * before its owner ever joins.
     */
    private static int setForcedOffline(CommandContext<CommandSourceStack> context, boolean forced) {
        CommandSourceStack source = context.getSource();
        String playerName = StringArgumentType.getString(context, ARG_PLAYER);

        VouchMod.getInstance().onMain(
                DatabaseManager.getInstance().setForcedOffline(playerName, forced, source.getTextName()),
                "premium override for " + playerName, source,
                success -> {
                    if (!success) {
                        source.sendFailure(Messages.databaseError());
                        return;
                    }
                    source.sendSystemMessage(forced
                            ? Messages.premiumForcedOffline(playerName)
                            : Messages.premiumForcedOnline(playerName));
                    LOGGER.info("Player {} marked as forced-{} by {}",
                            playerName, forced ? "offline" : "online", source.getTextName());
                });

        return 1;
    }

    // ==================== Premium opt-in ====================

    /**
     * Handle {@code /vouch premium} (formerly {@code /vouch markAsOnline}).
     * Player command to opt into premium auto-login. Verifies username exists
     * in Mojang API, then marks as online in DB for next login.
     */
    private static int executeMarkAsOnline(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!source.isPlayer()) {
            return 0;
        }

        ServerPlayer player = source.getPlayer();

        if (!AuthManager.getInstance().isAuthenticated(player)) {
            player.sendSystemMessage(Messages.mustLoginFirstClickable());
            return 0;
        }

        if (!VouchConfigManager.getInstance().isPremiumAutoLogin()) {
            return 0;
        }

        String username = player.getName().getString();
        DatabaseManager db = DatabaseManager.getInstance();
        VouchMod mod = VouchMod.getInstance();

        mod.onMain(db.getPremiumOverrideStatus(username),
                "premium override lookup for " + username, player, status -> {
                    if (status == PremiumOverrideStatus.MARKED_ONLINE) {
                        player.sendSystemMessage(Messages.premiumAlreadyMarkedOnline());
                        return;
                    }

                    mod.onMain(PremiumVerifier.getInstance().usernameExistsInMojangAPI(username),
                            "Mojang lookup for " + username, player, isPremium -> {
                                if (player.hasDisconnected()) return;

                                if (!isPremium) {
                                    player.sendSystemMessage(Messages.premiumMarkedOnlineNotPremium());
                                    return;
                                }

                                mod.onMain(db.setForcedOffline(username, false, player.getUUID().toString()),
                                        "premium opt-in for " + username, player, success -> {
                                            if (player.hasDisconnected()) return;
                                            if (success) {
                                                player.sendSystemMessage(Messages.premiumMarkedOnline());
                                                LOGGER.info("Player {} opted into premium auto-login", username);
                                            } else {
                                                player.sendSystemMessage(Messages.databaseError());
                                            }
                                        });
                            });
                });

        return 1;
    }

    // ==================== Shared helpers ====================

    /**
     * Resolve an admin's typed player name to a registered account.
     *
     * <p>Looks the name up in Vouch's own table rather than the server's user cache:
     * the cache does not know about accounts that have never joined this session, and
     * it happily invents an offline UUID for a name that was never registered at all,
     * which turned "player not found" into a silent no-op against a made-up UUID.
     */
    private static void withRegisteredAccount(CommandSourceStack source, String playerName,
                                              java.util.function.Consumer<PlayerRecord> action) {
        VouchMod.getInstance().onMain(
                DatabaseManager.getInstance().findPlayerByName(playerName),
                "account lookup for " + playerName, source,
                found -> {
                    if (found.isEmpty()) {
                        RegisteredNames.getInstance().remove(playerName);
                        source.sendFailure(Messages.playerNotFound(playerName));
                        return;
                    }
                    RegisteredNames.getInstance().add(found.get().username());
                    action.accept(found.get());
                });
    }

    /**
     * Whether this attempt is refused outright because the address is locked out.
     *
     * <p>An address that could not be resolved is not rate limited, because the
     * alternative — bucketing every such player under one placeholder string — lets
     * any one of them lock out all the others.
     */
    private static boolean isRateLimited(ServerPlayer player, Optional<String> ip) {
        if (ip.isEmpty()) {
            LOGGER.warn("Could not resolve an address for {}; proceeding without rate limiting",
                    player.getName().getString());
            return false;
        }
        if (RateLimiter.getInstance().getBlockRemaining(ip.get()).isZero()) {
            return false;
        }
        player.sendSystemMessage(Messages.tooManyAttempts());
        return true;
    }

    private static boolean isPasswordLengthAcceptable(ServerPlayer player, String password) {
        int minLen = getMinPasswordLength();
        int maxLen = getMaxPasswordLength();

        if (password.length() < minLen) {
            player.sendSystemMessage(Messages.passwordTooShort(minLen));
            return false;
        }
        if (password.length() > maxLen) {
            player.sendSystemMessage(Messages.passwordTooLong(maxLen));
            return false;
        }
        return true;
    }

    private static void recordLastLogin(DatabaseManager db, ServerPlayer player, Optional<String> ip) {
        ip.ifPresent(address -> db.updateLastLogin(player.getUUID(), address)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        LOGGER.error("Could not record the last login for {}",
                                player.getName().getString(), error);
                    }
                }));
    }

    private static String formatInstant(Instant instant) {
        return TIMESTAMP.format(instant);
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
    static void sendProcessingMessage(ServerPlayer player) {
        if (VouchConfigManager.getInstance().showProcessingMessage()) {
            player.sendSystemMessage(Messages.processing());
        }
    }

    /**
     * Check if a player is a server operator.
     */
    private static boolean isPlayerOp(ServerPlayer player) {
        try {
            var server = player.level().getServer();
            if (server != null) {
                return server.getPlayerList().isOp(player.nameAndId());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not check OP status for player {}", player.getName().getString());
        }
        return false;
    }
}
