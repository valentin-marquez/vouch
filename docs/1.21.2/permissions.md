# Permissions

Vouch uses a permission system that integrates with both Fabric and NeoForge permission APIs.

## Permission Nodes

### Player Commands

| Permission | Default | Description |
|------------|---------|-------------|
| `vouch.command.register` | All players (OP 0) | Use `/register` |
| `vouch.command.login` | All players (OP 0) | Use `/login` |
| `vouch.command.logout` | All players (OP 0) | Use `/logout` |

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
| `vouch.admin.unregister` | OP level 4 | Use `/vouch admin unregister` |
| `vouch.admin.export-lang` | OP level 4 | Use `/vouch admin export-lang` |

### Special

| Permission | Default | Description |
|------------|---------|-------------|
| `vouch.bypass.auth` | OP level 4 | Bypass authentication entirely |

::: warning
The `vouch.bypass.auth` permission should only be granted to trusted accounts or service accounts. Players with this permission will never be asked to authenticate.
:::

---

## Fabric — LuckPerms Integration

On Fabric, Vouch uses the [Fabric Permissions API](https://github.com/lucko/fabric-permissions-api) (`me.lucko.fabric.api.permissions.v0.Permissions`).

This is compatible with:
- [LuckPerms](https://luckperms.net/) (recommended)
- [FTB Ranks](https://www.curseforge.com/minecraft/mc-mods/ftb-ranks-forge)
- Any mod implementing the Fabric Permissions API

### Example: LuckPerms Setup

```bash
# Grant admin commands to a group
/lp group admin permission set vouch.admin.reload true
/lp group admin permission set vouch.admin.unregister true
/lp group admin permission set vouch.admin.export-lang true

# Grant auth bypass to a service account
/lp user ServiceBot permission set vouch.bypass.auth true
```

### Fallback Behavior

When no permission mod is installed, Vouch falls back to **vanilla OP levels**:
- Player commands: OP level 0 (all players)
- Admin commands: OP level 4
- `vouch.bypass.auth`: OP level 4

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
