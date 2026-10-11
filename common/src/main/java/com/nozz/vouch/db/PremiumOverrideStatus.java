package com.nozz.vouch.db;

/**
 * Represents the premium override state for a player username.
 *
 * Used to determine whether a player should go through premium verification
 * during login on an offline-mode server.
 */
public enum PremiumOverrideStatus {
    /** No entry in database — behavior depends on config (offline-by-default). */
    NO_OVERRIDE,
    /** Player opted in or admin marked as online — will receive encryption challenge. */
    MARKED_ONLINE,
    /** Admin forced offline — always skip premium check. */
    FORCED_OFFLINE
}
