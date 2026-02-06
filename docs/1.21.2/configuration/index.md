# Configuration Overview

Vouch is configured through a single TOML file located at:

```
config/vouch/vouch.toml
```

This file is auto-generated on first server start with sensible defaults.

## File Structure

```toml
# Branding
[branding]
mod_name = "Vouch"
prefix = "&8[&6Vouch&8]&r "

# Database
[database]
type = "h2"
host = "localhost"
port = 3306
name = "vouch"
user = "root"
password = ""

[database.pool]
max_size = 10
min_idle = 2

# Authentication
[auth]
mode = "password_optional_2fa"
login_timeout = 60
max_attempts = 5
lockout_duration = 300
password_min_length = 6
password_max_length = 64

# Sessions
[session]
persistence = true
duration = 3600
bind_to_ip = true
bind_to_uuid = true
cleanup_interval = 300

# TOTP (2FA)
[totp]
require_for_ops = false
issuer = "Vouch"
window_size = 1
time_step = 30

# Cryptography
[crypto.argon2]
memory_cost = 15360
iterations = 2
parallelism = 1

# Language
language = "en_us"

# User Interface
[ui.titles]
enabled = true
fade_in = 10
stay = 70
fade_out = 20
errors_enabled = false

[ui.actionbar]
enabled = true

[ui.bossbar]
enabled = true
color = "YELLOW"
style = "PROGRESS"

[ui.effects]
blindness_level = 1
slowness_level = 0
hide_from_others = true
hide_from_tab_list = true
freeze_position = true
freeze_camera = false

[ui.sounds]
enabled = true
login_success = "minecraft:entity.player.levelup"
register_success = "minecraft:entity.experience_orb.pickup"
wrong_password = "minecraft:block.note_block.bass"
auth_timeout = "minecraft:entity.villager.no"
rate_limited = "minecraft:block.anvil.land"
volume = 1.0
pitch = 1.0

[ui.colors]
primary = "&6"
success = "&a"
error = "&c"
info = "&e"
muted = "&7"

# Miscellaneous
[misc]
show_processing_message = true
clear_chat_on_join = false
welcome_message_padding = 2
```

## Environment Variables

Vouch supports environment variables for sensitive configuration values.

### Explicit Syntax

Use `${ENV:VARIABLE_NAME}` in any string value:

```toml
[database]
password = "${ENV:DB_PASSWORD}"
user = "${ENV:DB_USER:root}"  # with default value
```

### Auto-Override

Any config key can be overridden by setting an environment variable following the pattern:

```
VOUCH_<SECTION>_<KEY>
```

For example:
- `database.password` → `VOUCH_DATABASE_PASSWORD`
- `database.host` → `VOUCH_DATABASE_HOST`
- `auth.login_timeout` → `VOUCH_AUTH_LOGIN_TIMEOUT`

::: warning Sensitive Values
Vouch will warn in the console if sensitive keys like `database.password` contain plain-text values instead of environment variable references.
:::

## Hot Reload

Most configuration changes can be applied without restarting the server:

```
/vouch admin reload
```

This reloads both the `vouch.toml` and language files.

## Section Reference

| Section | Description |
|---------|-------------|
| [Branding](./branding) | Mod name and message prefix |
| [Database](./database) | Database type and connection settings |
| [Authentication](./authentication) | Auth mode, timeouts, password rules |
| [Sessions](./sessions) | Session persistence and binding |
| [TOTP (2FA)](./totp) | Two-factor authentication settings |
| [Cryptography](./cryptography) | Argon2id hashing parameters |
| [User Interface](./ui) | Titles, BossBar, effects, sounds, colors |
| [Miscellaneous](./misc) | Processing messages, chat clearing |
