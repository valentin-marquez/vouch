package com.nozz.vouch.command;

import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.auth.PlayerSession;
import com.nozz.vouch.util.PermissionHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * What a given caller may currently see, and how the client is told about it.
 *
 * <p>This is where {@link CommandGate}'s pure decision meets a live server: it turns
 * a {@code ServerCommandSource} into the {@link PlayerAuthState} the gate reasons
 * about, and it resends the command tree whenever that state changes.
 *
 * <h2>Why resending matters</h2>
 * Brigadier sends each client the subset of the tree its predicates accept, once,
 * and the client caches it. A player who logs in keeps being shown {@code /login}
 * and no {@code /logout} until something resends. The predicates are still enforced
 * server-side, so a stale tree is not a security hole — it is a client that is
 * simply wrong about what exists, which is the most confusing kind of wrong. Every
 * transition therefore calls {@link #resend(ServerPlayer)}: joining, being
 * jailed, logging in, restoring a session, premium auto-login, completing a second
 * factor, enabling or disabling 2FA, registering, and logging out.
 */
public final class CommandVisibility {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/Commands");

    private CommandVisibility() {
    }

    /**
     * Where this player stands in the authentication flow.
     *
     * <p>Reads only in-memory state — this runs inside a Brigadier predicate, which
     * is evaluated for every node on every tree send and before every execution, so
     * it must never touch the database.
     */
    public static PlayerAuthState stateOf(ServerPlayer player) {
        AuthManager auth = AuthManager.getInstance();
        if (auth.isAuthenticated(player)) {
            return PlayerAuthState.AUTHENTICATED;
        }

        PlayerSession session = auth.getSession(player.getUUID());
        // A 2FA-only registration has no account yet (it is created when the first code
        // verifies), so the only thing marking the player as owing a code is the pending
        // setup on the session. Without this /2fa verify is hidden and refused.
        if (session != null && (session.isAwaiting2FA() || session.is2FAOnlyRegistration())) {
            return PlayerAuthState.AWAITING_2FA;
        }

        // The name cache is refreshed from the database as the player joins, which is
        // the moment this distinction starts mattering to them.
        return RegisteredNames.getInstance().contains(player.getName().getString())
                ? PlayerAuthState.UNAUTHENTICATED_REGISTERED
                : PlayerAuthState.UNAUTHENTICATED_UNREGISTERED;
    }

    /**
     * Whether this player already has a second factor configured, as far as their
     * live session knows.
     *
     * <p>Kept on the session and refreshed wherever the database is asked, precisely
     * so this lookup is free.
     */
    public static boolean twoFactorEnabled(ServerPlayer player) {
        PlayerSession session = AuthManager.getInstance().getSession(player.getUUID());
        return session != null && session.has2FAEnabled();
    }

    /**
     * The {@code requires} predicate for one command: the permission node it needs,
     * and then the auth state it belongs to.
     *
     * <p>Non-player sources — console, command blocks, a datapack — are outside the
     * authentication flow entirely, so they are judged on permission alone. Applying
     * {@code CommandGate} to them would make {@code /vouch admin reload} unusable
     * from the console, since the console can never be "authenticated".
     */
    public static Predicate<CommandSourceStack> requirementFor(VouchCommand command) {
        return source -> {
            if (!hasPermissionFor(command, source)) {
                return false;
            }
            if (!source.isPlayer()) {
                return isAvailableToConsole(command);
            }
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return false;
            }
            return CommandGate.isAvailable(command, stateOf(player), twoFactorEnabled(player));
        };
    }

    /** Push the current tree to one player. Safe to call for a player already gone. */
    public static void resend(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // The tree is rebuilt per player from the predicates, so this has to happen on
        // the server thread; every caller is already there.
        server.execute(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            try {
                server.getCommands().sendCommands(player);
            } catch (RuntimeException e) {
                // Cosmetic if it fails: the predicates still enforce server-side.
                LOGGER.warn("Could not resend the command tree to {}",
                        player.getName().getString(), e);
            }
        });
    }

    private static boolean hasPermissionFor(VouchCommand command, CommandSourceStack source) {
        return switch (command) {
            case REGISTER -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.REGISTER);
            case LOGIN -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGIN);
            case LOGOUT -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGOUT);

            case TWO_FACTOR_SETUP -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_SETUP);
            case TWO_FACTOR_VERIFY -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_VERIFY);
            case TWO_FACTOR_DISABLE -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_DISABLE);
            case TWO_FACTOR_STATUS -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.TWO_FA_STATUS);

            case PREMIUM -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.MARK_AS_ONLINE);

            // Changing your own password needs nothing beyond being able to log in:
            // anyone who can authenticate can rotate the credential they authenticate with.
            case PASSWORD -> PermissionHelper.hasUserPermission(source, PermissionHelper.Nodes.LOGIN);

            case ADMIN_RELOAD -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_RELOAD);
            case ADMIN_USER -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_UNREGISTER);
            case ADMIN_PREMIUM -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_PREMIUM_OVERRIDE);
            case ADMIN_EXPORT_LANG -> PermissionHelper.hasAdminPermission(source, PermissionHelper.Nodes.ADMIN_EXPORT_LANG);
        };
    }

    /**
     * Which commands make sense without a player behind them.
     *
     * <p>The administrative ones do; the personal ones do not — there is no console
     * password to change and no console session to end.
     */
    private static boolean isAvailableToConsole(VouchCommand command) {
        return switch (command) {
            case ADMIN_RELOAD, ADMIN_USER, ADMIN_PREMIUM, ADMIN_EXPORT_LANG -> true;
            default -> false;
        };
    }
}
