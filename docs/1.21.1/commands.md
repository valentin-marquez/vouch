# Commands

Complete reference for all Vouch commands.

## Player Commands

### `/register`

Register a new account on the server.

::: code-group

```[Password Modes]
/register <password> <confirmPassword>
```

```[2FA-Only Mode]
/register
```

:::

| | |
|---|---|
| **Permission** | `vouch.command.register` |
| **Default** | All players |

**Password modes** (`password_only`, `password_optional_2fa`):
- Both `<password>` and `<confirmPassword>` must match.
- Password must be between `password_min_length` and `password_max_length` characters.

**2FA-only mode** (`2fa_only`):
- No arguments needed — a QR code is generated and placed as a map in the player's hand.
- Scan the QR code with your authenticator app, then verify with `/2fa verify <code>`.

---

### `/login`

Log in to an existing account.

::: code-group

```[Password Modes]
/login <password>
```

```[2FA-Only Mode]
/login <code>
```

:::

| | |
|---|---|
| **Permission** | `vouch.command.login` |
| **Default** | All players |

**Password modes**: Enter your password.

**2FA-only mode**: Enter the 6-digit code from your authenticator app.

**With optional 2FA enabled**: After successful password login, you'll be prompted to enter your TOTP code via `/2fa verify <code>`.

---

### `/logout`

Log out and invalidate all active sessions.

```
/logout
```

| | |
|---|---|
| **Permission** | `vouch.command.logout` |
| **Default** | All players |

The player is disconnected from the server and all stored sessions are deleted. On the next connection, full re-authentication is required.

---

### `/vouch password`

Change your own password.

```
/vouch password <oldPassword> <newPassword>
```

| | |
|---|---|
| **Permission** | `vouch.command.login` |
| **Default** | All players |
| **Requires** | Authenticated, a mode that uses passwords |

Verifies the old password, then stores the new one. Failed attempts count toward the rate limit.

---

### `/vouch premium`

Opt into premium auto-login.

```
/vouch premium
```

| | |
|---|---|
| **Permission** | `vouch.command.mark-as-online` |
| **Default** | All players |
| **Requires** | Authenticated, `auth.premium_auto_login = true` |

Checks that your username exists in the Mojang API and marks the account as premium. On your next join Vouch asks the client for the encryption handshake and logs you in automatically. The old name `/vouch markAsOnline` still works. See [Authentication](configuration/authentication#premium-accounts).

---

## 2FA Commands

All 2FA commands are under the `/2fa` prefix. `/2fa` is a shortcut for `/vouch 2fa`, so `/vouch 2fa setup` works too. These commands are **disabled** in `password_only` mode.

### `/2fa setup`

Start the 2FA setup process.

```
/2fa setup
```

| | |
|---|---|
| **Permission** | `vouch.command.2fa.setup` |
| **Default** | All players |
| **Requires** | Authenticated |

Generates a TOTP secret and renders a QR code on a virtual map in the player's hand. The player must scan the QR code and verify with `/2fa verify` to complete setup.

---

### `/2fa verify`

Verify a TOTP code.

```
/2fa verify <code>
```

| | |
|---|---|
| **Permission** | `vouch.command.2fa.verify` |
| **Default** | All players |

Used in two contexts:

1. **During setup**: Completes the 2FA enrollment by verifying the first code.
2. **During login**: Completes the 2FA step after password authentication (in `password_optional_2fa` mode).

The `<code>` is a 6-digit number from your authenticator app.

---

### `/2fa disable`

Disable 2FA for your account.

```
/2fa disable <code>
```

| | |
|---|---|
| **Permission** | `vouch.command.2fa.disable` |
| **Default** | All players |
| **Requires** | Authenticated |

Requires a valid current TOTP code to confirm the action. Once disabled, the TOTP secret is removed and login will only require a password.

---

### `/2fa status`

Check your current 2FA status.

```
/2fa status
```

| | |
|---|---|
| **Permission** | `vouch.command.2fa.status` |
| **Default** | All players |
| **Requires** | Authenticated |

Displays whether 2FA is currently enabled or disabled for your account.

---

## Admin Commands

Admin commands are under `/vouch admin`. The `/auth` command is an alias for `/vouch`.

### `/vouch admin reload`

Reload configuration and language files.

```
/vouch admin reload
```

| | |
|---|---|
| **Permission** | `vouch.admin.reload` |
| **Default** | OP level 4 |

Reloads `vouch.toml` and all language files from disk without restarting the server.

---

### `/vouch admin user`

Inspect or manage a registered account.

```
/vouch admin user <player> info
/vouch admin user <player> unregister
/vouch admin user <player> resetpw <newPassword>
```

| | |
|---|---|
| **Permission** | `vouch.admin.unregister` |
| **Default** | OP level 4 |

- `info`: shows the UUID, whether 2FA is enabled, last login, last IP and premium status.
- `unregister`: same as `/vouch admin unregister <player>` below.
- `resetpw`: sets a new password (validated against `password_min_length` and `password_max_length`) and invalidates the player's persistent sessions.

---

### `/vouch admin premium`

Pin a username to offline login, or release it.

```
/vouch admin premium <player> force-offline
/vouch admin premium <player> force-online
```

| | |
|---|---|
| **Permission** | `vouch.admin.premium-override` |
| **Default** | OP level 4 |

`force-offline` stops premium auto-login for that name; `force-online` removes the override. It works for names with no account yet, so you can pin a name before its owner joins. The older `/vouch admin force-offline <player>` and `force-online <player>` still work.

---

### `/vouch admin unregister`

Remove a player's registration. Legacy spelling of `/vouch admin user <player> unregister`.

```
/vouch admin unregister <player>
```

| | |
|---|---|
| **Permission** | `vouch.admin.unregister` |
| **Default** | OP level 4 |

Deletes the player's account from the database, including their password hash, TOTP secret, and all sessions. If the player is online, they are kicked from the server.

---

### `/vouch admin export-lang`

Export the current language file.

```
/vouch admin export-lang
```

| | |
|---|---|
| **Permission** | `vouch.admin.export-lang` |
| **Default** | OP level 4 |

Exports the active language file to `config/vouch/lang/` for customization. If the file already exists, it is overwritten with the current defaults.

---

## Command Aliases

| Alias | Target |
|-------|--------|
| `/auth` | `/vouch` |
| `/auth admin reload` | `/vouch admin reload` |
| `/auth admin unregister` | `/vouch admin unregister` |
| `/2fa ...` | `/vouch 2fa ...` |
| `/l`, `/reg` | `/login`, `/register` |
| `/auth admin export-lang` | `/vouch admin export-lang` |
