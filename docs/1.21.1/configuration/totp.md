# TOTP (2FA) Configuration

Configure Time-Based One-Time Password settings for two-factor authentication.

## Options

```toml
[totp]
require_for_ops = false
issuer = "Vouch"
window_size = 1
time_step = 30
```

### `require_for_ops`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `false` |

When enabled, server operators (OP) are required to set up 2FA. On login, OPs without 2FA will be prompted to run `/2fa setup`.

::: tip
This is recommended for production servers to protect admin accounts.
:::

### `issuer`

| | |
|---|---|
| **Type** | String |
| **Default** | `"Vouch"` |

The issuer name displayed in authenticator apps. This appears as the account label alongside the player's username.

For example, with `issuer = "MyServer"`, the authenticator app will show:

```
MyServer (PlayerName)
```

### `window_size`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `1` |

The number of time steps to accept before and after the current step. A value of `1` means codes from the previous, current, and next time step are accepted (±30 seconds with default `time_step`).

| Value | Accepted Window |
|-------|----------------|
| `0` | Current step only (±0s) |
| `1` | ±1 step (±30s) — **recommended** |
| `2` | ±2 steps (±60s) |

::: warning
Higher values increase tolerance for clock drift but reduce security. A value of `1` is recommended.
:::

### `time_step`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `30` |

The TOTP time step duration in seconds. This is the standard value used by most authenticator apps and should not be changed unless you have a specific reason.

## Compatible Apps

Vouch produces standard `otpauth://` URIs and is compatible with all major authenticator apps:

| App | Platform | Link |
|-----|----------|------|
| **Google Authenticator** | iOS, Android | [Play Store](https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2) |
| **Authy** | iOS, Android, Desktop | [authy.com](https://authy.com/) |
| **Aegis** | Android | [GitHub](https://github.com/beemdevelopment/Aegis) |
| **Microsoft Authenticator** | iOS, Android | [Play Store](https://play.google.com/store/apps/details?id=com.azure.authenticator) |
| **1Password** | All platforms | [1password.com](https://1password.com/) |

## 2FA Setup Flow

1. Player runs `/2fa setup` (must be authenticated first).
2. Vouch generates a 160-bit Base32 secret.
3. A QR code is rendered onto a virtual Minecraft map (128×128 pixels).
4. The map is temporarily placed in the player's main hand (no inventory impact).
5. Player scans the QR code with their authenticator app.
6. Player verifies with `/2fa verify <code>`.
7. 2FA is now active — future logins require a TOTP code.
