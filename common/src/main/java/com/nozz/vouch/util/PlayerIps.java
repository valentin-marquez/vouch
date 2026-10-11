package com.nozz.vouch.util;

import java.net.SocketAddress;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves the address a player is connected from.
 *
 * This value keys rate limiting and binds persistent sessions, so it has to be
 * produced identically everywhere. It used to be copy-pasted into four classes,
 * each returning the literal string {@code "unknown"} when parsing failed —
 * which collapsed every unparseable player into a single shared rate-limit
 * bucket. An unresolvable address is now absent, and the caller decides.
 */
public final class PlayerIps {

    private PlayerIps() {
    }

    /** The address of the player's connection, if it can be determined. */
    public static Optional<String> of(ServerPlayer player) {
        try {
            if (player == null || player.connection == null) {
                return Optional.empty();
            }
            var address = player.connection.getRemoteAddress();
            return address == null ? Optional.empty() : parse(address.toString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parse the address out of a socket address rendering such as
     * {@code host/192.0.2.1:25565} or {@code /[2001:db8::1]:25565}.
     */
    public static Optional<String> parse(String socketAddress) {
        if (socketAddress == null) {
            return Optional.empty();
        }

        String remaining = socketAddress.trim();
        int lastSlash = remaining.lastIndexOf('/');
        if (lastSlash >= 0) {
            remaining = remaining.substring(lastSlash + 1);
        }
        if (remaining.isBlank()) {
            return Optional.empty();
        }

        String address;
        if (remaining.startsWith("[")) {
            // Bracketed IPv6: everything between the brackets, port ignored.
            int close = remaining.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            address = remaining.substring(1, close);
        } else {
            // The last colon separates the port, for IPv4 and bare IPv6 alike.
            int lastColon = remaining.lastIndexOf(':');
            address = lastColon > 0 ? remaining.substring(0, lastColon) : remaining;
        }

        if (address.isBlank() || address.equals("unknown")) {
            return Optional.empty();
        }
        return Optional.of(address);
    }
}
