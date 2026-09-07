package com.nozz.vouch.db;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One account, as {@code /vouch admin user &lt;player&gt; info} needs to show it.
 *
 * <p>Deliberately does not carry the password hash or the TOTP secret: this record
 * exists to be rendered into chat, and a type that cannot hold a secret cannot leak
 * one. Whether 2FA is on is a fact about the account; the secret is a credential.
 *
 * <p>{@code lastLogin} and {@code lastIp} are absent for an account that has been
 * created but never used — a premium auto-registration, or a registration whose
 * first login has not happened yet.
 */
public record PlayerRecord(
        UUID uuid,
        String username,
        boolean twoFactorEnabled,
        Optional<Instant> lastLogin,
        Optional<String> lastIp,
        Optional<Instant> createdAt
) {
}
