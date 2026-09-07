package com.nozz.vouch.command;

/**
 * Where a player stands in the authentication flow.
 *
 * Drives which commands Brigadier sends them, so it is deliberately coarse:
 * every distinction here must matter to the command surface.
 */
public enum PlayerAuthState {
    /** Connected, no account on this server yet. */
    UNAUTHENTICATED_UNREGISTERED,

    /** Connected, has an account, has not proven it yet. */
    UNAUTHENTICATED_REGISTERED,

    /** Password accepted, second factor still outstanding. */
    AWAITING_2FA,

    /** Fully authenticated. */
    AUTHENTICATED
}
