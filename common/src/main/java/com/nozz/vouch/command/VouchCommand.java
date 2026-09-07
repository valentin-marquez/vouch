package com.nozz.vouch.command;

/**
 * Every command this mod registers, as a value the gate can reason about.
 *
 * Naming a command here is what makes it subject to {@link CommandGate}; a
 * command that is not in this enum has no state rules and would be reachable
 * from anywhere.
 */
public enum VouchCommand {
    REGISTER,
    LOGIN,
    LOGOUT,

    PASSWORD,
    PREMIUM,

    TWO_FACTOR_SETUP,
    TWO_FACTOR_VERIFY,
    TWO_FACTOR_DISABLE,
    TWO_FACTOR_STATUS,

    ADMIN_RELOAD,
    ADMIN_USER,
    ADMIN_PREMIUM,
    ADMIN_EXPORT_LANG
}
