package com.nozz.vouch.command;

/**
 * Decides whether a command applies to a player in a given state.
 *
 * Brigadier sends each client only the nodes whose predicate accepts them, and
 * evaluates the same predicate again before executing. So this one function is
 * simultaneously what tab-completion offers and what the server will accept for
 * this mod's own commands — an unauthenticated player is not shown `/logout`
 * and cannot run it either.
 *
 * Commands belonging to other mods are outside Brigadier's reach here; those
 * are blocked at the packet level instead.
 */
public final class CommandGate {

    private CommandGate() {
    }

    /**
     * @param command          the command being considered
     * @param state            where the player stands in the auth flow
     * @param twoFactorEnabled whether the player already has 2FA configured
     */
    public static boolean isAvailable(VouchCommand command, PlayerAuthState state, boolean twoFactorEnabled) {
        return switch (command) {
            case REGISTER -> state == PlayerAuthState.UNAUTHENTICATED_UNREGISTERED;
            case LOGIN -> state == PlayerAuthState.UNAUTHENTICATED_REGISTERED;

            // Offered while confirming a setup in progress, and as the second
            // step of a login.
            case TWO_FACTOR_VERIFY -> state == PlayerAuthState.AWAITING_2FA
                    || (state == PlayerAuthState.AUTHENTICATED && !twoFactorEnabled);

            case TWO_FACTOR_SETUP -> state == PlayerAuthState.AUTHENTICATED && !twoFactorEnabled;
            case TWO_FACTOR_DISABLE -> state == PlayerAuthState.AUTHENTICATED && twoFactorEnabled;

            case LOGOUT, PASSWORD, PREMIUM, TWO_FACTOR_STATUS,
                 ADMIN_RELOAD, ADMIN_USER, ADMIN_PREMIUM, ADMIN_EXPORT_LANG ->
                    state == PlayerAuthState.AUTHENTICATED;
        };
    }
}
