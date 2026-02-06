# User Experience

Vouch provides rich visual and audio feedback during the authentication process.

## Pre-Auth State ("Jail") {#jail}

When a player joins the server without an active session, they enter the **pre-auth state**. In this state, the player is fully isolated from the game world.

### What Players **Can** Do

| Action | Status |
|--------|--------|
| Type auth commands (`/register`, `/login`, `/2fa verify`) | ✅ |
| Look around (if `freeze_camera = false`) | ✅ |

### What Players **Cannot** Do

| Action | Status | Implementation |
|--------|--------|----------------|
| Send chat messages | ❌ | `ServerPlayNetworkHandlerMixin` |
| Execute non-auth commands | ❌ | Command registration filter |
| Move | ❌ | `ServerPlayerEntityMixin` (position reset per tick) |
| Break blocks | ❌ | `PlayerInteractionMixin` |
| Place blocks | ❌ | `PlayerInteractionMixin` |
| Use items | ❌ | `PlayerInteractionMixin` |
| Interact with entities | ❌ | Architectury event |
| Take damage | ❌ | `EntityDamageMixin` |
| Drop items | ❌ | Architectury event |
| Be seen by other players | ❌ | Invisibility effect |
| Appear in Tab List | ❌ | Tab list removal |

### Effects Applied

| Effect | Config Key | Default |
|--------|-----------|---------|
| Blindness | `ui.effects.blindness_level` | Level 1 |
| Slowness | `ui.effects.slowness_level` | Disabled (0) |
| Invisibility | `ui.effects.hide_from_others` | Enabled |
| Position freeze | `ui.effects.freeze_position` | Enabled |
| Camera freeze | `ui.effects.freeze_camera` | Disabled |
| Tab list hiding | `ui.effects.hide_from_tab_list` | Enabled |

::: tip
Flight is temporarily enabled for unauthenticated players to prevent fall-damage-related kicks. It is restored to its original state upon authentication.
:::

---

## Visual Feedback {#visual}

### Titles

Large screen messages displayed for important events.

| Event | Title | Subtitle |
|-------|-------|----------|
| Join (unregistered) | "Welcome!" | "Use /register \<password\> \<password\>" |
| Join (registered) | "Welcome!" | "Use /login \<password\>" |
| Login success | "Authenticated!" | "Welcome back, {player}!" |
| Register success | "Registered!" | "You can now play" |
| Wrong password | "Wrong Password!" | "Please try again" |
| 2FA success | "2FA Verified!" | — |

Configuration:

```toml
[ui.titles]
enabled = true
fade_in = 10    # 0.5 seconds
stay = 70       # 3.5 seconds
fade_out = 20   # 1.0 seconds
```

### Boss Bar

A countdown bar at the top of the screen showing remaining authentication time.

- Displays the text from `vouch.ui.bossbar.text` with the `{time}` placeholder
- Bar progress decreases as time runs out
- Color and style are configurable

```toml
[ui.bossbar]
enabled = true
color = "YELLOW"    # PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE
style = "PROGRESS"  # PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20
```

### Action Bar

Persistent text above the hotbar with authentication instructions.

```toml
[ui.actionbar]
enabled = true
```

Messages vary by auth mode and state:
- Pre-auth: "Use /login \<password\>" or "Use /register \<password\> \<password\>"
- Awaiting 2FA: "Use /2fa verify \<code\>"
- Rate limited: "Too many attempts. Try again later."

---

## Sounds {#sounds}

Configurable sound effects for authentication events.

| Event | Default Sound | Config Key |
|-------|---------------|-----------|
| Login success | `entity.player.levelup` | `ui.sounds.login_success` |
| Register success | `entity.experience_orb.pickup` | `ui.sounds.register_success` |
| Wrong password | `block.note_block.bass` | `ui.sounds.wrong_password` |
| Auth timeout | `entity.villager.no` | `ui.sounds.auth_timeout` |
| Rate limited | `block.anvil.land` | `ui.sounds.rate_limited` |

```toml
[ui.sounds]
enabled = true
volume = 1.0
pitch = 1.0
```

You can use any valid [Minecraft sound event](https://minecraft.wiki/w/Sounds.json). The format is `minecraft:category.sound.name`.

---

## QR Code Display (2FA) {#qr}

When a player runs `/2fa setup`, a QR code is rendered in-game:

1. A virtual map (128×128 pixels) is created with a unique ID
2. The QR code is generated using ZXing with error correction level M
3. The image is 116×116 pixels with 6px margins
4. The map is sent to the player via `MapUpdateS2CPacket`
5. The player's current main hand item is temporarily replaced
6. After scanning, the original item is restored

**No inventory modification occurs** — the map is purely visual and doesn't affect the player's actual inventory.

---

## Theme Colors {#colors}

Vouch uses a color system for consistent message styling:

```toml
[ui.colors]
primary = "&6"    # Gold — highlights
success = "&a"    # Green — success messages
error = "&c"      # Red — error messages
info = "&e"       # Yellow — informational
muted = "&7"      # Gray — secondary text
```

These colors are used throughout all chat messages and can be customized to match your server's branding.
