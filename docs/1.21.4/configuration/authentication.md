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
