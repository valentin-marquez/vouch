package com.nozz.vouch.util;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-agnostic permission checking utility.
 *
 * <p>Fabric: Uses Fabric Permissions API (compatible with LuckPerms, FTB Ranks, etc.)
 * <br>NeoForge: Uses NeoForge PermissionAPI
 *
 * <p>Most nodes fall back to vanilla OP levels when no permission mod is installed.
 * {@link Nodes#BYPASS_AUTH} deliberately does not — see {@link #canBypassAuth}.
 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /**
     * One permission node: its string id and the OP level that satisfies it when no
     * permission provider is installed (or when the provider has no opinion).
     *
     * @param id             the permission node string, e.g. {@code vouch.command.login}
     * @param defaultOpLevel the fallback OP level; {@link Defaults#EXPLICIT_GRANT_ONLY}
     *                       means "no OP level can satisfy this"
     */
    public record Node(String id, int defaultOpLevel) {

        /** {@code true} when every player satisfies this node without a grant. */
        public boolean grantedToEveryoneByDefault() {
            return defaultOpLevel <= Defaults.USER_COMMAND;
        }

        /** {@code true} when only an explicit permission grant can satisfy this node. */
        public boolean explicitGrantOnly() {
            return defaultOpLevel > Defaults.MAX_VANILLA_OP_LEVEL;
        }
    }

    /**
     * Permission nodes for Vouch commands.
     *
     * <p>Every node is declared through {@link #define} so that the constants and
     * {@link #all()} can never drift apart: a platform registrar walks {@code all()} and
     * therefore cannot silently skip a node. (The NeoForge entrypoint used to enumerate
     * nodes by hand and missed two of them, which meant LuckPerms grants for
     * {@code vouch.admin.premium-override} and {@code vouch.command.mark-as-online} were
     * honoured on Fabric but ignored on NeoForge.)
     */
    public static final class Nodes {

        private static final List<Node> DEFINITIONS = new ArrayList<>();

        // User commands (default: all players)
        public static final String REGISTER = define("vouch.command.register", Defaults.USER_COMMAND);
        public static final String LOGIN = define("vouch.command.login", Defaults.USER_COMMAND);
        public static final String LOGOUT = define("vouch.command.logout", Defaults.USER_COMMAND);

        // 2FA commands (default: all authenticated players)
        public static final String TWO_FA_SETUP = define("vouch.command.2fa.setup", Defaults.USER_COMMAND);
        public static final String TWO_FA_VERIFY = define("vouch.command.2fa.verify", Defaults.USER_COMMAND);
        public static final String TWO_FA_DISABLE = define("vouch.command.2fa.disable", Defaults.USER_COMMAND);
        public static final String TWO_FA_STATUS = define("vouch.command.2fa.status", Defaults.USER_COMMAND);

        // Player premium commands (default: all players)
        public static final String MARK_AS_ONLINE = define("vouch.command.mark-as-online", Defaults.USER_COMMAND);

        // Admin commands (default: OP level 4)
        public static final String ADMIN_RELOAD = define("vouch.admin.reload", Defaults.ADMIN_COMMAND);
        public static final String ADMIN_UNREGISTER = define("vouch.admin.unregister", Defaults.ADMIN_COMMAND);
        public static final String ADMIN_EXPORT_LANG = define("vouch.admin.export-lang", Defaults.ADMIN_COMMAND);
        public static final String ADMIN_PREMIUM_OVERRIDE = define("vouch.admin.premium-override", Defaults.ADMIN_COMMAND);

        // Special permissions.
        // Explicit grant only: an OP level must NEVER satisfy this node. On an
        // offline-mode server ops.json matches the offline UUID, which is exactly the
        // UUID a cracked client gets by joining under the operator's username.
        public static final String BYPASS_AUTH = define("vouch.bypass.auth", Defaults.EXPLICIT_GRANT_ONLY);

        /** Declared last on purpose: every {@link #define} call above has already run. */
        private static final List<Node> ALL = List.copyOf(DEFINITIONS);

        private Nodes() {
        }

        private static String define(String id, int defaultOpLevel) {
            DEFINITIONS.add(new Node(id, defaultOpLevel));
            return id;
        }

        /**
         * Every permission node Vouch declares, in declaration order.
         *
         * <p>Platform registrars should iterate this rather than listing nodes by hand.
         */
        public static List<Node> all() {
            return ALL;
        }

        /**
         * Look up a node by its string id.
         */
        public static Optional<Node> byId(String id) {
            if (id == null) {
                return Optional.empty();
            }
            return ALL.stream().filter(node -> node.id().equals(id)).findFirst();
        }
    }

    /**
     * Default OP levels for permission nodes.
     */
    public static final class Defaults {
        /** The highest OP level vanilla can hand out. */
        public static final int MAX_VANILLA_OP_LEVEL = 4;

        public static final int USER_COMMAND = 0;      // All players
        public static final int ADMIN_COMMAND = 4;     // Server operators only

        /**
         * Unreachable by any OP level, so the node can only be satisfied by an explicit
         * grant from a permission provider (LuckPerms, FTB Ranks, …).
         */
        public static final int EXPLICIT_GRANT_ONLY = Integer.MAX_VALUE;

        private Defaults() {
        }
    }

    /**
     * Check if a command source has a specific permission.
     *
     * @param source         The command source to check
     * @param permission     The permission node to check
     * @param defaultOpLevel The OP level to use as fallback when no permission mod is installed
     * @return true if the source has the permission
     */
    @ExpectPlatform
    public static boolean hasPermission(CommandSourceStack source, String permission, int defaultOpLevel) {
        throw new AssertionError("Platform implementation not loaded");
    }

    /**
     * Check a node using the default OP level declared for it in {@link Nodes}.
     */
    public static boolean hasPermission(CommandSourceStack source, Node node) {
        return hasPermission(source, node.id(), node.defaultOpLevel());
    }

    /**
     * Check if a command source has admin permission (OP level 4 default).
     */
    public static boolean hasAdminPermission(CommandSourceStack source, String permission) {
        return hasPermission(source, permission, Defaults.ADMIN_COMMAND);
    }

    /**
     * Check if a command source has user-level permission (available to all by default).
     */
    public static boolean hasUserPermission(CommandSourceStack source, String permission) {
        return hasPermission(source, permission, Defaults.USER_COMMAND);
    }

    /**
     * Check if a command source can bypass authentication.
     *
     * <p>This is the one node where a vanilla OP fallback would be a full authentication
     * bypass: on an offline-mode server {@code ops.json} is keyed by the offline UUID,
     * and an intruder who joins using an operator's username <em>is</em> that UUID. So:
     *
     * <ol>
     *   <li>the node is checked with {@link Defaults#EXPLICIT_GRANT_ONLY}, which no OP
     *       level can satisfy — only a real permission provider grant will;</li>
     *   <li>on an offline-mode server the grant is additionally refused unless the
     *       player's identity was cryptographically proven (see
     *       {@link #isPremiumVerified(ServerPlayer)});</li>
     *   <li>non-player sources (console, command blocks) never "bypass auth".</li>
     * </ol>
     *
     * <p>To legitimately grant this on an offline-mode server an admin must install a
     * permission provider, grant {@code vouch.bypass.auth} explicitly, and the holder
     * must join through Vouch's premium (encrypted) login so they carry their online
     * UUID.
     */
    public static boolean canBypassAuth(CommandSourceStack source) {
        boolean explicitGrant = hasPermission(source, Nodes.BYPASS_AUTH, Defaults.EXPLICIT_GRANT_ONLY);

        MinecraftServer server = source.getServer();
        boolean serverOnlineMode = server != null && server.usesAuthentication();

        ServerPlayer player = source.getPlayer();
        boolean premiumVerified = player != null && isPremiumVerified(player);

        return resolveBypassAuth(explicitGrant, serverOnlineMode, player != null, premiumVerified);
    }

    /**
     * The bypass decision, as a pure function so it can be tested without a server.
     *
     * @param explicitGrant    the permission provider granted {@code vouch.bypass.auth}
     * @param serverOnlineMode the server verifies sessions with Mojang itself
     * @param isPlayerSource   the source is an actual player
     * @param premiumVerified  the player's identity was cryptographically proven
     */
    public static boolean resolveBypassAuth(boolean explicitGrant,
                                            boolean serverOnlineMode,
                                            boolean isPlayerSource,
                                            boolean premiumVerified) {
        if (!explicitGrant || !isPlayerSource) {
            return false;
        }
        if (serverOnlineMode) {
            // Mojang already proved who this is; the grant is meaningful.
            return true;
        }
        // Offline mode: a username proves nothing. Only an account Vouch verified
        // through the encryption handshake may skip authentication.
        return premiumVerified;
    }

    /**
     * Whether this player's identity was cryptographically proven.
     *
     * <p>Vouch's mixed-mode login forces premium players through the Mojang encryption
     * handshake and assigns them their <em>online</em> UUID. A cracked client joining
     * under the same name only ever gets the offline UUID derived from that name, so a
     * UUID that is not the offline UUID for the player's own username is proof the
     * handshake succeeded.
     */
    public static boolean isPremiumVerified(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return hasOnlineUuid(player.getUUID(), player.getGameProfile().getName());
    }

    /**
     * @return {@code true} when {@code uuid} is not the offline UUID derived from
     * {@code username}
     */
    public static boolean hasOnlineUuid(UUID uuid, String username) {
        if (uuid == null || username == null || username.isEmpty()) {
            return false;
        }
        return !uuid.equals(UUIDUtil.createOfflinePlayerUUID(username));
    }
}
