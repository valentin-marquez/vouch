# Authentication Modes

Vouch supports three authentication modes, configured via `auth.mode` in `vouch.toml`.

```toml
[auth]
mode = "password_optional_2fa"
```

## Comparison

| | `password_only` | `2fa_only` | `password_optional_2fa` |
|---|:---:|:---:|:---:|
| **Password** | ✅ Required | ❌ Not used | ✅ Required |
| **2FA (TOTP)** | ❌ Disabled | ✅ Required | ✅ Optional (per-player) |
| `/register` | `/register <pass> <pass>` | `/register` (QR) | `/register <pass> <pass>` |
| `/login` | `/login <pass>` | `/login <code>` | `/login <pass>` then `/2fa verify` |
| `/2fa setup` | ❌ Disabled | N/A (automatic) | ✅ Available |
| `/2fa disable` | ❌ Disabled | ❌ Cannot disable | ✅ Available |
| **Complexity** | Low | Medium | Medium |

---

## `password_only`

The simplest mode. Players register with a password and log in with the same password.

**Flow:**
1. Join → See registration prompt
2. `/register MyPassword MyPassword`
3. Reconnect → `/login MyPassword`

**Best for:**
- Small private servers
- Servers where simplicity is preferred
- When players may not have access to authenticator apps

**Limitations:**
- No 2FA commands available
- All `/2fa` commands are disabled and show an error message

---

## `2fa_only`

Authenticator-app-only mode. No passwords are used.

**Registration flow:**
1. Join → See registration prompt
2. `/register` (no arguments)
3. QR code appears as a map in your hand
4. Scan with your authenticator app
5. `/2fa verify <code>` to complete registration

**Login flow:**
1. Join → See login prompt
2. `/login <6-digit-code>` from your authenticator app

**Best for:**
- High-security servers
- Communities where all players are comfortable with authenticator apps
- Servers where password reuse is a concern

**Considerations:**
- All players **must** have an authenticator app
- If a player loses access to their authenticator, an admin must run `/vouch admin unregister <player>`
- `/2fa disable` is not available in this mode

---

## `password_optional_2fa`

The **default** and most flexible mode. Password is required, and 2FA can be optionally enabled per-player.

**Registration flow:**
1. Join → See registration prompt
2. `/register MyPassword MyPassword`
3. Optionally: `/2fa setup` → scan QR → `/2fa verify <code>`

**Login flow (without 2FA):**
1. Join → `/login MyPassword`

**Login flow (with 2FA enabled):**
1. Join → `/login MyPassword`
2. Prompted for 2FA code
3. `/2fa verify <code>`

**Best for:**
- Most servers (recommended default)
- Balancing security and accessibility
- Servers where admins want to encourage but not require 2FA

**Features:**
- Players can enable/disable 2FA at will via `/2fa setup` and `/2fa disable`
- Admins can require 2FA for OPs via `totp.require_for_ops = true`
- `/2fa status` shows whether 2FA is active

---

## Recommended Presets

### Survival / SMP Server
```toml
[auth]
mode = "password_optional_2fa"
login_timeout = 90
max_attempts = 5

[totp]
require_for_ops = true
```

### Anarchy Server
```toml
[auth]
mode = "password_only"
login_timeout = 30
max_attempts = 10
```

### Private / Whitelisted Server
```toml
[auth]
mode = "password_optional_2fa"
login_timeout = 120
max_attempts = 3

[totp]
require_for_ops = true
```

### High-Security Server
```toml
[auth]
mode = "2fa_only"
login_timeout = 60
max_attempts = 3
lockout_duration = 600
```
