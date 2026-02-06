# User Interface Configuration

Configure titles, boss bar, action bar, effects, sounds, and colors displayed during the authentication process.

## Titles

```toml
[ui.titles]
enabled = true
fade_in = 10
stay = 70
fade_out = 20
errors_enabled = false
```

### `enabled`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Enable or disable title/subtitle messages on the player's screen.

### `fade_in` / `stay` / `fade_out`

| | |
|---|---|
| **Type** | Integer (ticks) |
| **Defaults** | `10` / `70` / `20` |

Timing for title animations in ticks (20 ticks = 1 second).

| Setting | Default | Duration |
|---------|---------|----------|
| `fade_in` | 10 ticks | 0.5 seconds |
| `stay` | 70 ticks | 3.5 seconds |
| `fade_out` | 20 ticks | 1.0 seconds |

### `errors_enabled`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `false` |

When enabled, error messages (wrong password, rate limited, etc.) are also shown as titles instead of only in chat.

---

## Action Bar

```toml
[ui.actionbar]
enabled = true
```

### `enabled`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Enable the action bar (text above the hotbar) showing authentication instructions like "Use /login \<password\>" or "Use /register \<password\> \<password\>".

---

## Boss Bar

```toml
[ui.bossbar]
enabled = true
color = "YELLOW"
style = "PROGRESS"
```

### `enabled`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Enable the boss bar countdown timer during authentication.

### `color`

| | |
|---|---|
| **Type** | String |
| **Default** | `"YELLOW"` |
| **Values** | `PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE`, `WHITE` |

### `style`

| | |
|---|---|
| **Type** | String |
| **Default** | `"PROGRESS"` |
| **Values** | `PROGRESS`, `NOTCHED_6`, `NOTCHED_10`, `NOTCHED_12`, `NOTCHED_20` |

---

## Effects

```toml
[ui.effects]
blindness_level = 1
slowness_level = 0
hide_from_others = true
hide_from_tab_list = true
freeze_position = true
freeze_camera = false
```

Effects are applied to players while they are in the pre-auth state (not yet logged in).

### `blindness_level`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `1` |

Blindness effect level. Set to `0` to disable.

### `slowness_level`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `0` (disabled) |

Slowness effect level. Set to `0` to disable.

### `hide_from_others`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Make unauthenticated players invisible to other players.

### `hide_from_tab_list`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Hide unauthenticated players from the Tab List (player list).

### `freeze_position`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

Prevent unauthenticated players from moving. The player is teleported back to their join position on every tick.

### `freeze_camera`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `false` |

Prevent unauthenticated players from rotating their camera.

---

## Sounds

```toml
[ui.sounds]
enabled = true
login_success = "minecraft:entity.player.levelup"
register_success = "minecraft:entity.experience_orb.pickup"
wrong_password = "minecraft:block.note_block.bass"
auth_timeout = "minecraft:entity.villager.no"
rate_limited = "minecraft:block.anvil.land"
volume = 1.0
pitch = 1.0
```

### Sound Events

| Key | Default Sound | Triggered When |
|-----|---------------|----------------|
| `login_success` | `entity.player.levelup` | Player logs in successfully |
| `register_success` | `entity.experience_orb.pickup` | Player registers successfully |
| `wrong_password` | `block.note_block.bass` | Incorrect password entered |
| `auth_timeout` | `entity.villager.no` | Player kicked for timeout |
| `rate_limited` | `block.anvil.land` | Player is rate limited |

### `volume` / `pitch`

| | |
|---|---|
| **Type** | Float |
| **Default** | `1.0` |

Volume and pitch for all authentication sounds. Range: `0.0` to `2.0`.

You can use any valid [Minecraft sound event](https://minecraft.wiki/w/Sounds.json) as a value.

---

## Colors

```toml
[ui.colors]
primary = "&6"
success = "&a"
error = "&c"
info = "&e"
muted = "&7"
```

Theme colors used in messages. These are `&`-style color codes applied to different types of messages.

| Key | Default | Color | Used For |
|-----|---------|-------|----------|
| `primary` | `&6` | Gold | Highlights, important info |
| `success` | `&a` | Green | Success messages |
| `error` | `&c` | Red | Error messages |
| `info` | `&e` | Yellow | Informational messages |
| `muted` | `&7` | Gray | Secondary text, hints |
