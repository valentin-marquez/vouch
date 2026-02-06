# Troubleshooting

Solutions for common issues with Vouch.

## Common Errors {#common-errors}

### "Database error"

**Symptoms:** Players see a database error when trying to register or login.

**Causes & Solutions:**

| Cause | Solution |
|-------|----------|
| MySQL/PostgreSQL server is down | Ensure the database server is running |
| Wrong credentials | Check `database.user` and `database.password` in `vouch.toml` |
| Database doesn't exist | Create the database: `CREATE DATABASE vouch;` |
| Firewall blocking connection | Allow connections on the database port (3306/5432) |
| H2 file locked | Only one server instance can use the H2 file at a time |

### "Session expired"

**Symptoms:** Players are asked to re-authenticate even though they were recently logged in.

**Solutions:**
- Increase `session.duration` (default: 3600 seconds / 1 hour)
- Check if the player's IP changed (dynamic IP with `bind_to_ip = true`)
- Verify `session.persistence` is `true`

### Login timeout too short

**Symptoms:** Players are kicked before they can type their password.

**Solution:** Increase `auth.login_timeout` in `vouch.toml`:

```toml
[auth]
login_timeout = 120  # 2 minutes
```

### Wrong password but password is correct

**Symptoms:** Player enters the correct password but gets "Wrong Password".

**Causes:**
- Player may have registered with a different password than they think
- Admin unregistered and the player re-registered with a different password
- Case sensitivity — passwords are case-sensitive

**Solution:** Admin can unregister the player: `/vouch admin unregister <player>`

### 2FA code invalid

**Symptoms:** TOTP codes are always rejected.

**Causes & Solutions:**
- **Clock drift**: Ensure the server's system clock is accurate (use NTP)
- **Wrong app account**: Player may be reading the code from a different account
- **Time step mismatch**: Keep `totp.time_step` at `30` (default)
- **Window too small**: Try increasing `totp.window_size` to `2`

### Rate limited / Locked out

**Symptoms:** Player cannot attempt login.

**Solutions:**
- Wait for the lockout to expire (default: 5 minutes)
- Admin can restart the server to clear rate limit counters (stored in memory, not persisted)
- Adjust `auth.max_attempts` and `auth.lockout_duration`

---

## Logs {#logs}

### Log Location

Vouch logs to the standard server log:

| Platform | File |
|----------|------|
| Fabric | `logs/latest.log` |
| NeoForge | `logs/latest.log` |

### Log Levels

| Level | Information |
|-------|-------------|
| `INFO` | Normal operations (startup, shutdown, auth events) |
| `WARN` | Non-critical issues (plain-text passwords in config, missing lang keys) |
| `ERROR` | Critical failures (database errors, crypto failures) |

### What to Check

When reporting issues, include:
1. The full stack trace from `logs/latest.log`
2. Your `vouch.toml` (redact database credentials)
3. Minecraft version and mod loader version
4. Vouch version
5. Other mods installed

---

## Compatibility {#compatibility}

### Known Compatible Mods

| Mod | Status | Notes |
|-----|--------|-------|
| LuckPerms | ✅ | Full permission integration |
| FTB Ranks | ✅ | Via Fabric Permissions API |
| Lithium | ✅ | Performance mods work fine |
| Sodium (client) | ✅ | Client-side, no interaction |
| Carpet | ✅ | No conflicts |

### Potential Conflicts

| Mod | Status | Notes |
|-----|--------|-------|
| Other auth mods | ⚠️ | Don't run two auth mods simultaneously |
| Chat management mods | ⚠️ | May interfere with chat blocking |
| Movement mods | ⚠️ | May conflict with position freeze |

### Proxy Support

| Proxy | Status |
|-------|--------|
| Velocity | 🚧 In development |
| BungeeCord | 🚧 In development |
| Standalone | ✅ Fully supported |

::: info
Proxy support is planned for a future release. Currently, Vouch is designed for standalone server installations.
:::

---

## Debug Checklist

If you're having issues, go through this checklist:

1. **Check the logs** — `logs/latest.log` for errors or warnings
2. **Verify config** — Ensure `vouch.toml` is valid TOML syntax
3. **Test database** — For MySQL/PostgreSQL, verify you can connect manually
4. **Check permissions** — Make sure the config directory is writable
5. **Mod conflicts** — Try with only Vouch + dependencies installed
6. **JVM version** — Vouch requires Java 21+
7. **Reload config** — Run `/vouch admin reload` after changes
8. **Restart server** — Some changes require a full restart

## Getting Help

If you can't resolve the issue:

1. Check the [FAQ](./faq) page
2. Search [GitHub Issues](https://github.com/nozzdev/vouch/issues)
3. Open a new issue with logs and config details
