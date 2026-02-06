# FAQ

Frequently asked questions about Vouch.

## General

### Does Vouch work on the client?

**No.** Vouch is a **server-side only** mod. Players do not need to install anything on their client. All authentication happens through chat commands and server-side logic.

### Which Minecraft versions are supported?

Vouch supports **Minecraft 1.21.2** and **1.21.3** with this version. Other supported versions include 1.21.1 and 1.21.4 — see the version selector in the documentation.

### Does Vouch support Fabric and NeoForge?

**Yes.** Vouch is built with [Architectury API](https://github.com/architectury) and supports both:
- **Fabric** (requires Fabric API + Architectury API)
- **NeoForge** (requires Architectury API only)

---

## Performance

### Does Vouch affect server TPS?

**No.** All heavy operations run on dedicated thread pools:
- Password hashing (Argon2id): 4-thread async executor
- Database queries: Async via `CompletableFuture`
- Session cleanup: Scheduled single-thread executor

Results are dispatched back to the main thread only when player-facing actions are needed.

### How much memory does Vouch use?

Memory usage depends on the Argon2id configuration:
- Default: ~15 MiB per concurrent hash operation
- With 4 threads: up to ~60 MiB during peak (all 4 slots active)
- Database connections: minimal (HikariCP pool)

For most servers, the memory overhead is negligible.

---

## Security

### Can I use Vouch with offline mode?

**Yes.** Vouch works in offline mode (`online-mode=false`). In fact, this is one of the primary use cases — authenticating players on cracked/offline servers.

::: warning
In offline mode, there's no Mojang UUID verification. Players can join with any username. Vouch ensures they authenticate with their registered password or 2FA code regardless.
:::

### What if a player loses their authenticator app?

An admin can unregister the player, allowing them to re-register:

```
/vouch admin unregister <player>
```

The player will be kicked (if online) and can rejoin to create a new account.

### Are passwords stored in plain text?

**Absolutely not.** Passwords are hashed using **Argon2id** with a unique 128-bit salt per password. The original password is never stored or recoverable.

### Can session tokens be stolen from the database?

Even if the database is compromised, session tokens are **SHA-256 hashed** before storage. The original token cannot be recovered from the hash.

---

## Configuration

### How do I reset a player's password?

There is no password reset command. Instead, unregister the player and let them re-register:

```
/vouch admin unregister <player>
```

### Can I change the auth mode after players have registered?

**Yes**, but with considerations:

| From → To | Impact |
|-----------|--------|
| `password_only` → `password_optional_2fa` | ✅ Seamless — existing accounts work, 2FA becomes available |
| `password_optional_2fa` → `password_only` | ⚠️ Players with 2FA will only need their password |
| `password_*` → `2fa_only` | ❌ Existing password-only accounts cannot login. Need to re-register. |
| `2fa_only` → `password_*` | ❌ Existing 2FA-only accounts need to re-register with a password. |

### Can I use multiple databases simultaneously?

**No.** Vouch uses a single database backend. If you need to migrate, export your data from the old database and import it into the new one.

### How do I protect my database password?

Use environment variables:

```toml
[database]
password = "${ENV:VOUCH_DB_PASSWORD}"
```

Or set the environment variable `VOUCH_DATABASE_PASSWORD` and it will auto-override the config value. See [Environment Variables](./configuration/#environment-variables).

---

## Proxy

### Can I use Vouch with Velocity or BungeeCord?

Proxy support is **in development**. Currently, Vouch is designed for standalone server installations. A future release will add proxy forwarding and shared session support.

### Does Vouch work with server networks?

For networks, you can use a shared MySQL or PostgreSQL database across multiple servers. However, full proxy-aware session management is not yet available.

---

## Troubleshooting

### My changes to `vouch.toml` aren't taking effect

Run `/vouch admin reload` after editing the config file. Some changes may require a full server restart.

### The QR code for 2FA doesn't appear

- Ensure the player is holding nothing in their main hand (or that the item can be temporarily replaced)
- Check for client-side mods that might interfere with map rendering
- Verify the player is authenticated before running `/2fa setup`

### Players can move/chat before authenticating

- Check that Vouch is loading correctly (no errors in logs)
- Ensure no other mods are overriding the same Mixin targets
- Verify `ui.effects.freeze_position` is `true` in the config

For more detailed troubleshooting, see the [Troubleshooting](./troubleshooting) page.
