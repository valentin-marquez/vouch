# Branding

Configure the display name and message prefix that Vouch uses in chat messages.

## Options

```toml
[branding]
mod_name = "Vouch"
prefix = "&8[&6Vouch&8]&r "
```

### `mod_name`

| | |
|---|---|
| **Type** | String |
| **Default** | `"Vouch"` |

The display name used in messages and placeholders. This is referenced by the `{mod_name}` placeholder in language files.

### `prefix`

| | |
|---|---|
| **Type** | String |
| **Default** | `"&8[&6Vouch&8]&r "` |

The prefix prepended to all chat messages sent by Vouch. Supports Minecraft `&` color codes.

#### Color Codes

| Code | Color | Code | Color |
|------|-------|------|-------|
| `&0` | Black | `&8` | Dark Gray |
| `&1` | Dark Blue | `&9` | Blue |
| `&2` | Dark Green | `&a` | Green |
| `&3` | Dark Aqua | `&b` | Aqua |
| `&4` | Dark Red | `&c` | Red |
| `&5` | Dark Purple | `&d` | Light Purple |
| `&6` | Gold | `&e` | Yellow |
| `&7` | Gray | `&f` | White |
| `&k` | Obfuscated | `&l` | **Bold** |
| `&m` | ~~Strikethrough~~ | `&n` | <u>Underline</u> |
| `&o` | *Italic* | `&r` | Reset |

#### Examples

```toml
# Gold brackets, amber name
prefix = "&8[&6Vouch&8]&r "

# Simple colored prefix
prefix = "&eVouch &7» &r"

# Custom server name
prefix = "&6MyServer Auth &7| &r"
```
