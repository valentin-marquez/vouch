# Permissions

Vouch uses a permission system that integrates with both Fabric and NeoForge permission APIs.

## Permission Nodes

### Player Commands

| Permission | Default | Description |
|------------|---------|-------------|
| `vouch.command.register` | All players (OP 0) | Use `/register` |
| `vouch.command.login` | All players (OP 0) | Use `/login` |
| `vouch.command.logout` | All players (OP 0) | Use `/logout` |
| `vouch.command.mark-as-online` | All players (OP 0) | Use `/vouch premium` to opt into premium auto-login |

`/vouch password` is covered by `vouch.command.login`.

### 2FA Commands

| Permission | Default | Description |
|------------|---------|-------------|
| `vouch.command.2fa.setup` | All players (OP 0) | Use `/2fa setup` |
| `vouch.command.2fa.verify` | All players (OP 0) | Use `/2fa verify` |
| `vouch.command.2fa.disable` | All players (OP 0) | Use `/2fa disable` |
| `vouch.command.2fa.status` | All players (OP 0) | Use `/2fa status` |

### Admin Commands

| Permission | Default | Description |
|------------|---------|-------------|
| `vouch.admin.reload` | OP level 4 | Use `/vouch admin reload` |
| `vouch.admin.unregister` | OP level 4 | Use `/vouch admin user <player> ...` (`info`, `unregister`, `resetpw`) and the legacy `/vouch admin unregister` |
| `vouch.admin.export-lang` | OP level 4 | Use `/vouch admin export-lang` |
| `vouch.admin.premium-override` | OP level 4 | Use `/vouch admin premium <player> force-offline\|force-online` |

### Special

| Permission | Default | Description |
|------------|---------|-------------|
| `vouch.bypass.auth` | Explicit grant only | Bypass authentication entirely |

::: warning
The `vouch.bypass.auth` permission cannot be satisfied by an operator level. It requires an explicit grant from a permission provider such as LuckPerms.

That is deliberate: on an offline-mode server `ops.json` matches the offline UUID derived from a username, which is exactly the UUID a cracked client gets by joining under an operator's name. Falling back to an OP level would have handed the bypass to anyone who typed an operator's name.

On an offline-mode server the holder must **also** connect through Vouch's premium login, so they arrive with their online UUID. Grant it only to accounts you would trust with the server.
:::

---

## Fabric — LuckPerms Integration

On Fabric, Vouch uses the [Fabric Permissions API](https://github.com/lucko/fabric-permissions-api) (`me.lucko.fabric.api.permissions.v0.Permissions`).

This is compatible with:
- [LuckPerms](https://luckperms.net/) (recommended)
- FTB Ranks
- Any mod implementing the Fabric Permissions API

### Example: LuckPerms Setup

```bash
# Grant admin commands to a group
/lp group admin permission set vouch.admin.reload true
/lp group admin permission set vouch.admin.unregister true
/lp group admin permission set vouch.admin.export-lang true
/lp group admin permission set vouch.admin.premium-override true

# Grant auth bypass to a service account
/lp user ServiceBot permission set vouch.bypass.auth true
```

### Fallback Behavior

When no permission mod is installed, Vouch falls back to **vanilla OP levels**:
- Player commands: OP level 0 (all players)
- Admin commands: OP level 4
- `vouch.bypass.auth`: never granted by fallback — explicit grant only

---

## NeoForge — PermissionAPI Integration

On NeoForge, Vouch uses the built-in `net.neoforged.neoforge.server.permission.PermissionAPI`.

All permission nodes are automatically registered at startup via `PermissionGatherEvent.Nodes`. This means they appear in permission management tools and GUIs.

### Default Levels

NeoForge permissions use the same default OP levels as Fabric:
- Player commands → `PermissionNode` with default `OP 0`
- Admin commands → `PermissionNode` with default `OP 4`

### Fallback Behavior

Without a permission mod, NeoForge uses vanilla OP levels as the default resolver.
