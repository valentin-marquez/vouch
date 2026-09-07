# Sessions Configuration

Let a returning player skip the password for a while after they authenticate.

## What a session actually proves

Read this before enabling anything on this page.

Vouch is a server-side mod. It has no channel to hand a vanilla Minecraft client a secret and ask for it back on the next connection, so a session cannot be a token the player presents. What Vouch can check on reconnect is the username and the address the connection came from — and on an offline-mode server the UUID is derived from the username, so that half is chosen by whoever is connecting.

**A restored session therefore proves one thing: somebody reached this server from the same address, recently.** It is address trust. That is a real, useful signal, and it is weaker than a password.

The practical consequence: for as long as `duration`, anyone sharing that outbound address can join under that username without the password. That means the same house, the same school or office, the same VPN exit — and, importantly, the same CGNAT pool, which is how a large share of residential and mobile ISPs hand out addresses. If your players are on mobile data or a CGNAT-ed ISP, "the same address" can mean thousands of unrelated strangers.

::: warning
If that is not an acceptable trade for your server, set `persistence = false`. Every other setting here only narrows the window; none of them turn address trust into proof of identity.
:::

Two things limit the exposure automatically:

- **Premium players never use sessions.** A player who completed Vouch's encrypted premium login proved ownership of their Mojang account cryptographically, on this connection. Consulting the weaker evidence as well would only add a second way in, so Vouch skips it.
- **A second factor is still owed.** If the account has 2FA, a restored session does not satisfy it. See `require_2fa_on_restore`.

## Options

```toml
[session]
persistence = true
duration = 3600
require_2fa_on_restore = true
cleanup_interval = 300
```

### `persistence`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Whether a returning player may skip the password on the terms described above. Set to `false` to require authentication on every connection.

### `duration`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `3600` |

How long a session stays valid. This is the length of the window during which someone sharing the player's address can use it, so treat it as a risk dial rather than a convenience dial.

| Value | Duration |
|-------|----------|
| `1800` | 30 minutes |
| `3600` | 1 hour |
| `86400` | 24 hours |
| `604800` | 7 days |

### `require_2fa_on_restore`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Ask for the 2FA code even when a session is restored. Leave this on: a session is not a second factor, and a player who enabled 2FA asked for exactly this protection.

Turning it off means a restored session skips both factors, which hands anyone on the shared address the account the player took extra care over.

### `cleanup_interval`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `300` |

How often expired sessions are swept from the database.

## How sessions work

1. A player authenticates with `/login` or `/register`.
2. Vouch records their UUID, the address they connected from, and an expiry.
3. On reconnect, Vouch looks for a record that is not expired and whose address matches the address they are connecting from now.
4. If the player was premium-verified during this login, the record is not consulted at all — they are already authenticated by stronger means.
5. If the account has 2FA and `require_2fa_on_restore` is on, they are asked for the code.
6. `/logout` deletes every session for the player.

## Removed settings

`bind_to_ip` and `bind_to_uuid` no longer exist. If they are still in your `vouch.toml` they are ignored, and Vouch logs a warning at startup.

`bind_to_ip = false` removed the address check, which was the only evidence a session carried — it authenticated anyone who knew a username, from anywhere. There was no safe use for it.

`bind_to_uuid` was documented but never read by any code path; it did nothing.
