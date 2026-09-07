package com.nozz.vouch;

import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.MixedModeLoginHandler;
import com.nozz.vouch.auth.PlayerSession;
import com.nozz.vouch.auth.SessionRestorePolicy;
import com.nozz.vouch.auth.SessionRestoreFacts;
import com.nozz.vouch.auth.SessionRestoreDecision;
import com.nozz.vouch.auth.PreAuthManager;
import com.nozz.vouch.command.CommandVisibility;
import com.nozz.vouch.command.CommandTypoCorrector;
import com.nozz.vouch.command.RegisteredNames;
import com.nozz.vouch.command.VouchCommands;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.db.ConnectionFactory;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.util.LangManager;
import com.nozz.vouch.util.Messages;
import com.nozz.vouch.util.PermissionHelper;
import com.nozz.vouch.util.PlayerIps;
import com.nozz.vouch.util.PreAuthCommandGate;
import com.nozz.vouch.util.PremiumVerifier;
import com.nozz.vouch.util.UXManager;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Vouch - Server-side authentication mod for Minecraft
 * 
 * Secure authentication with Argon2id hashing, TOTP 2FA,
 * and multi-database support (H2/SQLite/MySQL/PostgreSQL).
 * 
 * @version 0.2.0
 */
public final class VouchMod {
    public static final String MOD_ID = "vouch";
    public static final String MOD_NAME = "Vouch";
    public static final String VERSION = "0.2.0";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static VouchMod instance;

    private final ExecutorService asyncExecutor;

    private final ScheduledExecutorService scheduler;

    private MinecraftServer server;

    private boolean initialized = false;

    private VouchMod() {

        this.asyncExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "Vouch-Async");
            thread.setDaemon(true);
            return thread;
        });

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Vouch-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Get the singleton instance of VouchMod
     */
    public static VouchMod getInstance() {
        if (instance == null) {
            instance = new VouchMod();
        }
        return instance;
    }

    /**
     * Initialize the mod - called from platform-specific entrypoints
     * @param configDir The base configuration directory
     */
    public static void init(Path configDir) {
        VouchMod mod = getInstance();
        if (mod.initialized) {
            LOGGER.warn("Vouch already initialized, skipping...");
            return;
        }

        LOGGER.info("Initializing {} v{}", MOD_NAME, VERSION);

        VouchConfigManager config = VouchConfigManager.initialize(configDir);
        LangManager.getInstance().initialize(config.getLangDir());

        // "Did you mean /login?" for jailed players. The gate only consults this for
        // commands it has already blocked, and only ever offers what the player can
        // already see — the pre-auth allowlist it passes in.
        PreAuthCommandGate.setCorrector(new CommandTypoCorrector());

        mod.registerEvents();
        mod.registerLifecycleEvents();

        mod.initialized = true;
        LOGGER.info("{} initialized successfully!", MOD_NAME);
    }

    /**
     * Register server lifecycle events
     */
    private void registerLifecycleEvents() {
        // One tree, registered once. /auth and /2fa are redirects into it rather than
        // separate registrations, so they cannot drift out of step again.
        CommandRegistrationEvent.EVENT.register(VouchCommands::registerAll);

        LifecycleEvent.SERVER_STARTING.register(server -> {
            this.server = server;
            LOGGER.info("Server starting, initializing database...");

            try {
                ConnectionFactory.getInstance().initialize(server.getRunDirectory());

                DatabaseManager.getInstance().initializeSchema();

                startSessionCleanupScheduler();
                seedRegisteredNames();

                LOGGER.info("Database initialized successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to initialize database!", e);
            }
        });

        // Server stopping - cleanup
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, cleaning up...");
            shutdown();
        });
    }

    /**
     * Register all Architectury events for pre-auth restrictions
     */
    private void registerEvents() {
        LOGGER.debug("Registering Architectury events...");

        AuthManager authManager = AuthManager.getInstance();
        DatabaseManager dbManager = DatabaseManager.getInstance();

        PlayerEvent.PLAYER_JOIN.register(player -> {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            LOGGER.debug("Player joined: {}", serverPlayer.getName().getString());

            // Check if player has bypass permission (for bots, service accounts, etc.)
            if (PermissionHelper.canBypassAuth(serverPlayer.getCommandSource())) {
                authManager.authenticateFromSession(serverPlayer);
                CommandVisibility.resend(serverPlayer);
                LOGGER.info("Player {} bypassed authentication via permission", serverPlayer.getName().getString());
                return;
            }

            VouchConfigManager config = VouchConfigManager.getInstance();
            Optional<String> ip = PlayerIps.of(serverPlayer);

            // Asked exactly once per join, because the record is consumed on read.
            // Both the session decision and the premium path need the answer.
            boolean premiumVerified = MixedModeLoginHandler.getInstance()
                    .wasVerifiedAtLogin(serverPlayer.getUuid(), serverPlayer.getName().getString());

            java.util.function.Function<Boolean, SessionRestoreFacts> facts = twoFactorEnabled ->
                    SessionRestoreFacts.builder()
                            .persistenceEnabled(config.isSessionPersistenceEnabled())
                            .addressKnown(ip.isPresent())
                            .liveSessionForThisAddress(true)
                            .premiumVerifiedThisLogin(premiumVerified)
                            .twoFactorEnabled(twoFactorEnabled)
                            .require2faOnRestore(config.isSessionRequire2faOnRestore())
                            .build();

            // Only hit the database when a session could possibly be used at all.
            boolean sessionWorthChecking = SessionRestorePolicy.decide(
                    SessionRestoreFacts.builder()
                            .persistenceEnabled(config.isSessionPersistenceEnabled())
                            .addressKnown(ip.isPresent())
                            .liveSessionForThisAddress(true)
                            .premiumVerifiedThisLogin(premiumVerified)
                            .build()) != SessionRestoreDecision.NO_SESSION;

            CompletableFuture<Boolean> sessionCheck = sessionWorthChecking
                    ? authManager.hasValidSession(serverPlayer.getUuid(), ip.orElseThrow())
                    : CompletableFuture.completedFuture(false);

            Runnable withoutSession = () -> {
                if (config.isPremiumAutoLogin()) {
                    handlePremiumAutoLogin(serverPlayer, authManager, dbManager, premiumVerified);
                } else {
                    jailPlayer(serverPlayer, authManager, dbManager);
                }
            };

            onMain(sessionCheck, "session check for " + serverPlayer.getName().getString(), hasValidSession -> {
                if (serverPlayer.isDisconnected()) {
                    return;
                }
                if (!hasValidSession) {
                    withoutSession.run();
                    return;
                }

                // There is a live session. Whether it is enough depends on the
                // account's second factor, which a session cannot satisfy.
                onMain(dbManager.has2FAEnabled(serverPlayer.getUuid()),
                        "2FA lookup for " + serverPlayer.getName().getString(),
                        twoFactorEnabled -> {
                            if (serverPlayer.isDisconnected()) {
                                return;
                            }
                            if (SessionRestorePolicy.decide(facts.apply(twoFactorEnabled))
                                    == SessionRestoreDecision.RESTORE_NEEDS_2FA) {
                                authManager.addPendingForSession2FA(serverPlayer);
                                CommandVisibility.resend(serverPlayer);
                                LOGGER.info("Player {} restored a session and still owes a 2FA code",
                                        serverPlayer.getName().getString());
                                return;
                            }
                            authManager.authenticateFromSession(serverPlayer);
                            UXManager.getInstance().onSessionRestored(serverPlayer);
                            CommandVisibility.resend(serverPlayer);
                            LOGGER.info("Player {} authenticated via persistent session",
                                    serverPlayer.getName().getString());
                        },
                        () -> jailPlayer(serverPlayer, authManager, dbManager));
            }, () -> jailPlayer(serverPlayer, authManager, dbManager));
        });

        PlayerEvent.PLAYER_QUIT.register(player -> {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            LOGGER.debug("Player quit: {}", serverPlayer.getName().getString());
            authManager.removePlayer(serverPlayer);
        });
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, direction) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                if (!authManager.isAuthenticated(serverPlayer)) {
                    return dev.architectury.event.EventResult.interruptFalse();
                }
            }
            return dev.architectury.event.EventResult.pass();
        });

        InteractionEvent.LEFT_CLICK_BLOCK.register((player, hand, pos, direction) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                if (!authManager.isAuthenticated(serverPlayer)) {
                    return dev.architectury.event.EventResult.interruptFalse();
                }
            }
            return dev.architectury.event.EventResult.pass();
        });

        InteractionEvent.INTERACT_ENTITY.register((player, entity, hand) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                if (!authManager.isAuthenticated(serverPlayer)) {
                    return dev.architectury.event.EventResult.interruptFalse();
                }
            }
            return dev.architectury.event.EventResult.pass();
        });

        TickEvent.PLAYER_POST.register(player -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
            if (authManager.isPendingAuth(serverPlayer)) {
                PreAuthManager.getInstance().enforcePositionFreeze(serverPlayer);
            }
        });

        LOGGER.debug("Events registered successfully");
    }

    /**
     * Start the periodic session cleanup scheduler.
     * Runs at the interval specified in config (session.cleanup_interval).
     */
    private void startSessionCleanupScheduler() {
        int intervalSeconds = VouchConfigManager.getInstance().getSessionCleanupInterval();
        int intervalMinutes = Math.max(1, intervalSeconds / 60); // At least 1 minute
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                DatabaseManager.getInstance().cleanupExpiredSessions()
                        .whenComplete((deleted, error) -> {
                            if (error != null) {
                                LOGGER.error("Session cleanup failed", error);
                            } else if (deleted > 0) {
                                LOGGER.debug("Session cleanup: removed {} expired sessions", deleted);
                            }
                        });
            } catch (Exception e) {
                LOGGER.error("Error during session cleanup", e);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

        LOGGER.info("Session cleanup scheduler started (interval: {} minutes)", intervalMinutes);
    }

    /**
     * Handle premium auto-login flow for a player.
     * 
     * When premium_auto_login is enabled, the ServerLoginNetworkHandlerMixin
     * intercepts the login phase and forces encryption for premium usernames.
     * By the time PLAYER_JOIN fires, premium players already have their online UUID.
     * 
     * This method detects whether the player was verified at login time by
     * comparing their UUID against the offline UUID for their username.
     * If they differ, the player was verified as premium during login.
     */
    private void handlePremiumAutoLogin(ServerPlayerEntity serverPlayer, AuthManager authManager,
                                        DatabaseManager dbManager, boolean verifiedAtLogin) {
        String username = serverPlayer.getName().getString();

        if (verifiedAtLogin) {
            // Player was verified as premium during the login handshake.
            // Skip the redundant Mojang API call.
            LOGGER.info("Player {} has online UUID (verified at login), proceeding with premium auto-login", username);
            completePremiumAutoLogin(serverPlayer, authManager, dbManager);
        } else {
            // Player has an offline UUID — they were not verified at login.
            // This means they are a non-premium (cracked) player.
            LOGGER.debug("Player {} has offline UUID, proceeding with normal auth flow", username);
            jailPlayer(serverPlayer, authManager, dbManager);
        }
    }

    /**
     * Put a player in the pre-auth jail, having first asked the database whether they
     * have an account.
     *
     * <p>That answer does double duty: it decides the welcome message, and it refreshes
     * {@link RegisteredNames} for exactly the player who is about to need it — which is
     * what makes the {@code /login} versus {@code /register} distinction in the command
     * tree correct for them even if the cache had gone stale.
     *
     * <p>If the lookup fails the player is still jailed, as unregistered. Failing the
     * other way would leave them loose in the world; this way the worst case is a
     * misleading prompt in front of a locked door.
     */
    private void jailPlayer(ServerPlayerEntity player, AuthManager authManager, DatabaseManager dbManager) {
        onMain(dbManager.isRegistered(player.getUuid()),
                "registration lookup for " + player.getName().getString(),
                isRegistered -> {
                    if (player.isDisconnected()) {
                        return;
                    }
                    RegisteredNames.getInstance().setPresent(player.getName().getString(), isRegistered);
                    authManager.addPendingPlayer(player, isRegistered);
                    CommandVisibility.resend(player);
                },
                () -> {
                    if (player.isDisconnected()) {
                        return;
                    }
                    player.sendMessage(Messages.databaseConnectionError(), false);
                    authManager.addPendingPlayer(player, false);
                    CommandVisibility.resend(player);
                });
    }

    /**
     * Read the player's 2FA flag into their in-memory session and resend the tree.
     *
     * <p>Only the flag, never the secret. {@code PlayerSession} is what the command
     * tree's predicates read, and a session created by restoring a token or by a
     * premium login has never spoken to the database, so without this the player is
     * offered {@code /vouch 2fa setup} when they already have 2FA configured.
     */
    private void syncTwoFactorFlagThenResend(ServerPlayerEntity player, DatabaseManager dbManager) {
        onMain(dbManager.has2FAEnabled(player.getUuid()),
                "2FA status for " + player.getName().getString(),
                has2FA -> {
                    if (player.isDisconnected()) {
                        return;
                    }
                    PlayerSession session = AuthManager.getInstance().getSession(player.getUuid());
                    if (session != null) {
                        session.set2FAEnabled(has2FA);
                    }
                    CommandVisibility.resend(player);
                },
                // has2FAEnabled fails closed, so a failure here says nothing about the
                // player. Their access is already settled; only completion is affected.
                () -> CommandVisibility.resend(player));
    }

    /** Fill the tab-completion name cache once, at boot. */
    private void seedRegisteredNames() {
        DatabaseManager.getInstance().listRegisteredUsernames().whenComplete((names, error) -> {
            if (error != null) {
                LOGGER.error("Could not seed the registered-name cache; "
                        + "player-name completion will fill in as players join", error);
                return;
            }
            RegisteredNames.getInstance().seed(names);
            LOGGER.info("Seeded {} registered username(s) for command completion", names.size());
        });
    }

    /**
     * Complete premium auto-login for a player verified as premium.
     * Handles 2FA enforcement if configured.
     */
    private void completePremiumAutoLogin(ServerPlayerEntity serverPlayer, AuthManager authManager, DatabaseManager dbManager) {
        String username = serverPlayer.getName().getString();

        // Ensure premium player has a row in vouch_players before creating sessions
        onMain(dbManager.ensurePremiumPlayerRegistered(serverPlayer.getUuid(), username),
                "premium auto-registration for " + username,
                registered -> {
                    if (serverPlayer.isDisconnected()) return;

                    RegisteredNames.getInstance().add(username);

                    VouchConfigManager config = VouchConfigManager.getInstance();
                    if (!config.isPremiumAutoLoginRequire2FA()) {
                        authManager.authenticateAsPremium(serverPlayer);
                        UXManager.getInstance().onPremiumAutoLogin(serverPlayer);
                        CommandVisibility.resend(serverPlayer);
                        return;
                    }

                    onMain(dbManager.has2FAEnabled(serverPlayer.getUuid()),
                            "2FA status for " + username,
                            has2FA -> {
                                if (serverPlayer.isDisconnected()) return;
                                if (has2FA) {
                                    authManager.addPendingPremiumFor2FA(serverPlayer);
                                    PlayerSession session =
                                            authManager.getSession(serverPlayer.getUuid());
                                    if (session != null) {
                                        session.set2FAEnabled(true);
                                    }
                                } else {
                                    authManager.authenticateAsPremium(serverPlayer);
                                    UXManager.getInstance().onPremiumAutoLogin(serverPlayer);
                                }
                                CommandVisibility.resend(serverPlayer);
                            },
                            // has2FAEnabled fails closed on purpose. This server wants a
                            // second factor from premium players, and we cannot tell
                            // whether this one has one — so nobody is let in on a
                            // database outage. Jail, not access.
                            () -> {
                                if (serverPlayer.isDisconnected()) return;
                                serverPlayer.sendMessage(Messages.databaseConnectionError(), false);
                                jailPlayer(serverPlayer, authManager, dbManager);
                            });
                },
                () -> {
                    if (serverPlayer.isDisconnected()) return;
                    serverPlayer.sendMessage(Messages.databaseConnectionError(), false);
                    jailPlayer(serverPlayer, authManager, dbManager);
                });
    }

    /**
     * Get the async executor for crypto operations
     */
    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    /**
     * Run a task on the main server thread
     */
    public void runOnMainThread(Runnable task) {
        if (server != null) {
            server.execute(task);
        } else {
            // Fallback: run directly (should not happen in normal operation)
            LOGGER.warn("Server not available, running task directly");
            task.run();
        }
    }

    /**
     * Hand an async result back to the main server thread, and never lose a failure.
     *
     * <p>This replaces about thirty-five hand-written
     * {@code future.thenAccept(v -> runOnMainThread(...))} chains. The whole mod had
     * exactly one {@code .exceptionally()}, so every other async failure — a database
     * outage, a fail-closed 2FA lookup, a hashing error — ended in silence: the
     * consumer simply never ran. From the player's side that is a login command that
     * does nothing, followed by the pre-auth countdown kicking them; from the
     * operator's side it is nothing at all in the log.
     *
     * <p>So a failure here always does two things: it logs with the caller's label,
     * and it runs {@code onFailure} on the main thread so the player can be told.
     * A consumer that throws is treated the same way, since a handler blowing up on
     * the main thread is the same silence with a different cause.
     *
     * @param context short label naming the work, used in the log line
     */
    public <T> void onMain(CompletableFuture<T> future, String context, Consumer<T> consumer) {
        onMain(future, context, consumer, null);
    }

    /**
     * As {@link #onMain(CompletableFuture, String, Consumer)}, with a recovery action
     * that runs on the main thread when the work fails.
     */
    public <T> void onMain(CompletableFuture<T> future, String context,
                           Consumer<T> consumer, Runnable onFailure) {
        future.whenComplete((value, error) -> runOnMainThread(() -> {
            if (error != null) {
                fail(context, error, onFailure);
                return;
            }
            try {
                consumer.accept(value);
            } catch (RuntimeException e) {
                fail(context, e, onFailure);
            }
        }));
    }

    /**
     * As {@link #onMain(CompletableFuture, String, Consumer)}, telling the player that
     * something went wrong when it does.
     */
    public <T> void onMain(CompletableFuture<T> future, String context,
                           ServerPlayerEntity player, Consumer<T> consumer) {
        onMain(future, context, consumer, () -> {
            if (player != null && !player.isDisconnected()) {
                player.sendMessage(Messages.internalError(), false);
            }
        });
    }

    /**
     * As above, but with the caller's own recovery instead of the generic message —
     * for the cases where a failure means more than "something went wrong", such as a
     * fail-closed 2FA lookup that has to leave the player outside.
     */
    public <T> void onMain(CompletableFuture<T> future, String context,
                           ServerPlayerEntity player, Consumer<T> consumer, Runnable onFailure) {
        onMain(future, context, consumer, () -> {
            if (player != null && !player.isDisconnected()) {
                onFailure.run();
            }
        });
    }

    /**
     * As {@link #onMain(CompletableFuture, String, Consumer)}, for work started by a
     * command whose source may be the console rather than a player.
     */
    public <T> void onMain(CompletableFuture<T> future, String context,
                           ServerCommandSource source, Consumer<T> consumer) {
        onMain(future, context, consumer, () -> {
            if (source != null) {
                source.sendError(Messages.internalError());
            }
        });
    }

    private void fail(String context, Throwable error, Runnable onFailure) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        LOGGER.error("Async {} failed", context, cause);
        if (onFailure == null) {
            return;
        }
        try {
            onFailure.run();
        } catch (RuntimeException e) {
            // The recovery is the last line of defence; it must not become the failure.
            LOGGER.error("Failure handling for {} threw", context, e);
        }
    }

    /**
     * Get the current server instance
     */
    public MinecraftServer getServer() {
        return server;
    }

    /**
     * Shutdown hook - cleanup resources
     */
    public void shutdown() {
        LOGGER.info("Shutting down {}...", MOD_NAME);
        AuthManager.getInstance().shutdown();
        PremiumVerifier.getInstance().shutdown();

        ConnectionFactory.getInstance().close();
        scheduler.shutdownNow();
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Async executor did not terminate in time, forcing shutdown...");
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        server = null;
        LOGGER.info("{} shutdown complete", MOD_NAME);
    }

    public Logger getLogger() {
        return LOGGER;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
