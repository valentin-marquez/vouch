# Language System

Vouch supports multiple languages with full customization.

## Available Languages

| Code | Language | Status |
|------|----------|--------|
| `en_us` | English (US) | ✅ Built-in |
| `es_mx` | Español (México) | ✅ Built-in |

Set the active language in `vouch.toml`:

```toml
language = "en_us"
```

---

## File Locations

Language files are stored in two locations:

### Built-in (Read-only)

Bundled inside the mod JAR:

```
assets/vouch/lang/en_us.json
assets/vouch/lang/es_mx.json
```

### User Overrides

Generated in the server's config directory:

```
config/vouch/lang/en_us.json
config/vouch/lang/es_mx.json
```

User override files are auto-generated on first server start. Modifications to these files take priority over the built-in defaults.

### Fallback Chain

```
User override (config/vouch/lang/{lang}.json)
  → Built-in resource (assets/vouch/lang/{lang}.json)
    → Hardcoded defaults
      → Key itself (e.g., "vouch.auth.login.success")
```

---

## Customization

### Editing Messages

1. Start the server once to generate the language files.
2. Open `config/vouch/lang/en_us.json` (or your active language).
3. Edit any message.
4. Run `/vouch admin reload` to apply changes without restarting.

### Color Codes

Messages support Minecraft `&` color codes:

| Code | Color | Code | Format |
|------|-------|------|--------|
| `&0` | Black | `&k` | Obfuscated |
| `&1` | Dark Blue | `&l` | **Bold** |
| `&2` | Dark Green | `&m` | ~~Strikethrough~~ |
| `&3` | Dark Aqua | `&n` | <u>Underline</u> |
| `&4` | Dark Red | `&o` | *Italic* |
| `&5` | Dark Purple | `&r` | Reset |
| `&6` | Gold | | |
| `&7` | Gray | | |
| `&8` | Dark Gray | | |
| `&9` | Blue | | |
| `&a` | Green | | |
| `&b` | Aqua | | |
| `&c` | Red | | |
| `&d` | Light Purple | | |
| `&e` | Yellow | | |
| `&f` | White | | |

### Placeholders

Messages can include dynamic placeholders:

| Placeholder | Description |
|-------------|-------------|
| `{player}` | Player's username |
| `{time}` | Remaining time (seconds) |
| `{min}` | Minimum value (e.g., password length) |
| `{max}` | Maximum value |
| `{secret}` | TOTP secret (Base32) |
| `{path}` | File path |
| `{mod_name}` | Mod display name from branding config |

---

## Adding a New Language

1. Copy an existing language file:

```bash
cp config/vouch/lang/en_us.json config/vouch/lang/fr_fr.json
```

2. Translate all values in the new file (keep the keys unchanged).

3. Update `vouch.toml`:

```toml
language = "fr_fr"
```

4. Reload:

```
/vouch admin reload
```

---

## Message Keys Reference

### Authentication Messages

| Key | Default (en_us) |
|-----|----------------|
| `vouch.auth.welcome.unregistered` | Welcome message for new players |
| `vouch.auth.welcome.registered` | Welcome message for returning players |
| `vouch.auth.welcome.2fa_only.unregistered` | Welcome for new players (2FA mode) |
| `vouch.auth.welcome.2fa_only.registered` | Welcome for returning players (2FA mode) |
| `vouch.auth.login.success` | Login success message |
| `vouch.auth.login.wrong_password` | Wrong password message |
| `vouch.auth.login.too_many_attempts` | Too many attempts warning |
| `vouch.auth.login.locked_out` | Lockout message |
| `vouch.auth.register.success` | Registration success |
| `vouch.auth.register.success_2fa` | 2FA registration success |
| `vouch.auth.register.password_mismatch` | Passwords don't match |
| `vouch.auth.register.password_too_short` | Password too short |
| `vouch.auth.register.password_too_long` | Password too long |
| `vouch.auth.register.already_registered` | Already registered error |
| `vouch.auth.not_registered` | Not registered error |
| `vouch.auth.already_logged_in` | Already logged in |
| `vouch.auth.timeout` | Authentication timeout |
| `vouch.auth.session_restored` | Session restored message |
| `vouch.auth.session_expired` | Session expired message |
| `vouch.auth.logout.success` | Logout success |
| `vouch.auth.logout.kick` | Kick message on logout |

### Pre-Auth Messages

| Key | Description |
|-----|-------------|
| `vouch.jail.chat_blocked` | Chat blocked message |
| `vouch.jail.action_blocked` | Action blocked message |
| `vouch.jail.command_blocked` | Command blocked message |
| `vouch.jail.processing` | Processing indicator |

### 2FA Messages

| Key | Description |
|-----|-------------|
| `vouch.2fa.required` | 2FA verification required |
| `vouch.2fa.setup.instructions` | Setup instructions |
| `vouch.2fa.setup.manual_secret` | Manual secret entry text |
| `vouch.2fa.setup.failed` | Setup failed error |
| `vouch.2fa.enabled` | 2FA enabled confirmation |
| `vouch.2fa.disabled` | 2FA disabled confirmation |
| `vouch.2fa.invalid_code` | Invalid TOTP code |
| `vouch.2fa.already_enabled` | Already enabled error |
| `vouch.2fa.not_enabled` | Not enabled error |
| `vouch.2fa.nothing_pending` | No pending setup |
| `vouch.2fa.status.enabled` | Status: enabled |
| `vouch.2fa.status.disabled` | Status: disabled |
| `vouch.2fa.must_login_first` | Must authenticate first |
| `vouch.2fa.required_for_ops` | Required for operators |

### Admin Messages

| Key | Description |
|-----|-------------|
| `vouch.admin.player_unregistered` | Player unregistered |
| `vouch.admin.player_not_found` | Player not found |
| `vouch.admin.player_2fa_reset` | 2FA reset for player |
| `vouch.admin.config_reloaded` | Config reloaded |
| `vouch.admin.lang_exported` | Language exported |
| `vouch.admin.database_error` | Database error |
| `vouch.admin.no_permission` | No permission |

### UI Messages

| Key | Description |
|-----|-------------|
| `vouch.ui.title.welcome` | Welcome title |
| `vouch.ui.title.login_success` | Login success title |
| `vouch.ui.title.register_success` | Register success title |
| `vouch.ui.title.wrong_password` | Wrong password title |
| `vouch.ui.title.2fa_success` | 2FA success title |
| `vouch.ui.subtitle.*` | Various subtitle messages |
| `vouch.ui.actionbar.*` | Action bar messages |
| `vouch.ui.bossbar.*` | Boss bar text |

### Error Messages

| Key | Description |
|-----|-------------|
| `vouch.error.internal` | Internal error |
| `vouch.error.database` | Database error |
