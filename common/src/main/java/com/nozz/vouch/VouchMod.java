package com.nozz.vouch;

import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.PreAuthManager;
import com.nozz.vouch.command.TwoFactorCommands;
import com.nozz.vouch.command.VouchCommands;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.db.ConnectionFactory;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.util.LangManager;
import com.nozz.vouch.util.PermissionHelper;
import com.nozz.vouch.util.UXManager;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Vouch - Server-side authentication mod for Minecraft
 * 
 * Industrial-grade security with Argon2id hashing, TOTP 2FA,
 * and multi-database support (H2/SQLite/MySQL/PostgreSQL).
 * 
 * @version 0.1.0 MVP
 */
public final class VouchMod {
    public static final String MOD_ID = "vouch";
    public static final String MOD_NAME = "Vouch";
    public static final String VERSION = "0.1.0";

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
        mod.registerEvents();
        mod.registerLifecycleEvents();

        mod.initialized = true;
        LOGGER.info("{} initialized successfully!", MOD_NAME);
    }

    /**
     * Register server lifecycle events
     */
    private void registerLifecycleEvents() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            VouchCommands.registerAll(dispatcher, registryAccess, environment);
            TwoFactorCommands.registerAll(dispatcher, registryAccess, environment);
        });

        LifecycleEvent.SERVER_STARTING.register(server -> {
            this.server = server;
            LOGGER.info("Server starting, initializing database...");

            try {
                ConnectionFactory.getInstance().initialize(server.getRunDirectory());

                DatabaseManager.getInstance().initializeSchema();

                startSessionCleanupScheduler();

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
                LOGGER.info("Player {} bypassed authentication via permission", serverPlayer.getName().getString());
                return;
            }

            String ip = getPlayerIP(serverPlayer);
            authManager.hasValidSession(serverPlayer.getUuid(), ip).thenAccept(hasValidSession -> {
                runOnMainThread(() -> {
                    if (serverPlayer.isDisconnected()) {
                        return;
                    }

                    if (hasValidSession) {
                        authManager.authenticateFromSession(serverPlayer);
                        UXManager.getInstance().onSessionRestored(serverPlayer);
                        LOGGER.info("Player {} authenticated via persistent session", serverPlayer.getName().getString());
                    } else {
                        dbManager.isRegistered(serverPlayer.getUuid()).thenAccept(isRegistered -> {
                            runOnMainThread(() -> {
                                if (!serverPlayer.isDisconnected()) {
                                    authManager.addPendingPlayer(serverPlayer, isRegistered);
                                }
                            });
                        });
                    }
                });
            });
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
                        .thenAccept(deleted -> {
                            if (deleted > 0) {
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
     * Get player's IP address from their network connection
     */
    private String getPlayerIP(ServerPlayerEntity player) {
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
