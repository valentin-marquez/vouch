package com.nozz.vouch.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the player's address out of a socket address string.
 *
 * This is the value rate limiting keys on and the value a persistent session is
 * bound to, so a parse that differs between two call sites — or that keeps the
 * brackets on an IPv6 address — quietly breaks both.
 */
class PlayerIpsTest {

    @Test
    void stripsTheLeadingSlashAndThePort() {
        assertEquals(Optional.of("192.168.1.10"), PlayerIps.parse("/192.168.1.10:56789"));
    }

    @Test
    void acceptsAnAddressWithNoLeadingSlash() {
        assertEquals(Optional.of("192.168.1.10"), PlayerIps.parse("192.168.1.10:56789"));
    }

    @Test
    void keepsOnlyTheAddressWhenAHostnameIsPresent() {
        assertEquals(Optional.of("203.0.113.7"), PlayerIps.parse("player.example.com/203.0.113.7:25565"));
    }

    @Test
    void unwrapsABracketedIpv6Address() {
        // Java renders IPv6 socket addresses in brackets. Keeping them would
        // store a different string than any other code path produces.
        assertEquals(Optional.of("2001:db8::1"), PlayerIps.parse("/[2001:db8::1]:25565"));
    }

    @Test
    void handlesABareIpv6AddressWithAPort() {
        assertEquals(Optional.of("2001:db8::1"), PlayerIps.parse("/2001:db8::1:25565"));
    }

    @Test
    void keepsAnIpv6AddressThatCarriesNoPort() {
        assertEquals(Optional.of("2001:db8::1"), PlayerIps.parse("/[2001:db8::1]"));
    }

    @Test
    void returnsEmptyForNull() {
        assertTrue(PlayerIps.parse(null).isEmpty());
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertTrue(PlayerIps.parse("   ").isEmpty());
        assertTrue(PlayerIps.parse("/").isEmpty());
    }

    @Test
    void unknownIsNotAValidAddress() {
        // The old code returned the literal string "unknown" on failure, which
        // collapsed every unparseable player into one shared rate-limit bucket.
        // An unknown address must be absent, so callers have to decide.
        assertTrue(PlayerIps.parse("unknown").isEmpty());
    }
}
