# Authentication Configuration

Configure the authentication mode, timeouts, and password requirements.

## Options

```toml
[auth]
mode = "password_optional_2fa"
login_timeout = 60
max_attempts = 5
lockout_duration = 300
password_min_length = 6
password_max_length = 64
allowed_commands = ["login", "l", "register", "reg", "2fa", "vouch", "auth"]
premium_auto_login = false
premium_auto_login_require_2fa = true
premium_offline_by_default = true
premium_fetch_skins = true
```

### `mode`

| | |
|---|---|
| **Type** | String |
| **Default** | `"password_optional_2fa"` |
| **Values** | `password_only`, `2fa_only`, `password_optional_2fa` |

The authentication mode determines how players register and log in. See [Auth Modes](../auth-modes) for a detailed comparison.

| Mode | Password | 2FA | Description |
|------|----------|-----|-------------|
| `password_only` | ✅ Required | ❌ Disabled | Simple password authentication |
| `2fa_only` | ❌ Disabled | ✅ Required | Authenticator app only |
| `password_optional_2fa` | ✅ Required | ✅ Optional | Password + opt-in 2FA |

### `login_timeout`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `60` |

Time in seconds before an unauthenticated player is kicked from the server. The countdown is displayed visually via the BossBar.

::: tip
Set to a higher value (e.g., `120`) if your players have slow internet or need more time to open their authenticator app.
:::

### `max_attempts`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `5` |

Maximum number of failed login attempts before the player is locked out. Rate limiting kicks in progressively:

| Attempts | Action |
|----------|--------|
| `max_attempts / 2` (min 3) | 30-second lockout |
| `max_attempts` | Lockout for `lockout_duration` seconds |
| `max_attempts × 2` | 1-hour lockout |

### `lockout_duration`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `300` |

Duration in seconds that a player is locked out after exceeding `max_attempts`. Default is 5 minutes.

### `password_min_length`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `6` |

Minimum password length required during registration.

### `password_max_length`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `64` |

Maximum password length allowed. Prevents excessively long inputs that could slow down hashing.

### `allowed_commands`

| | |
|---|---|
| **Type** | List of strings |
| **Default** | `["login", "l", "register", "reg", "2fa", "vouch", "auth"]` |

Command roots (without the leading `/`) that an unauthenticated player may run. Every other command is blocked until the player logs in. Add aliases here if you rename or proxy the login commands.

The list is matched on the command root only. Vouch's own subcommands are gated separately, by authentication state, so keeping `vouch` here does not expose anything an unauthenticated player should not reach.

::: warning
A player who has entered their password but still owes a 2FA code is not authenticated yet. The roots that carry the second factor (`2fa`, `vouch`, `auth`) must stay in the list, or that player can never finish logging in. An empty list is rejected and replaced by the defaults.
:::

## Premium accounts

These options control automatic login for players with a valid Mojang/Microsoft account. They need the server to run with `online-mode=false`. Players are verified against the Mojang API with the standard encryption handshake.

### `premium_auto_login`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `false` |

Authenticate premium players automatically when they join. Works behind BungeeCord/Velocity with IP forwarding, since UUIDs are already forwarded.

### `premium_auto_login_require_2fa`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

If `true`, a premium player who has 2FA enabled still has to enter their code. Keeping it enabled is recommended.

### `premium_offline_by_default`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

If `true`, every player is treated as offline (no encryption challenge) until they opt in with `/vouch premium` and join again. This prevents "Invalid session" errors for cracked clients that use a premium username.

If `false`, any username that exists in the Mojang API receives an encryption challenge. Admins can pin a name with `/vouch admin premium <player> force-offline`.

### `premium_fetch_skins`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Fetch the player's signed profile properties (skin and cape) from the Mojang session server during premium verification. Disable it only if you serve skins from another source.
