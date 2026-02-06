# Vouch — Minecraft 1.21.1

<div class="tip custom-block" style="padding-top: 8px">
  Current version: <strong>0.1.0</strong> · Minecraft <strong>1.21.1</strong> · Java <strong>21+</strong>
</div>

**Vouch** is a secure, server-side authentication mod for Minecraft 1.21.1. It supports both **Fabric** and **NeoForge** through the Architectury API.

## Key Features

| Feature | Description |
|---------|-------------|
| **Argon2id Hashing** | Secure password hashing via Bouncy Castle |
| **TOTP 2FA** | RFC 6238 time-based one-time passwords with in-game QR codes |
| **Multi-Database** | H2, SQLite, MySQL, PostgreSQL with HikariCP pooling |
| **Async Everything** | Zero TPS impact — crypto and DB run on dedicated thread pools |
| **Pre-Auth Jail** | Full player isolation with effects, freezing, and tab list hiding |
| **Persistent Sessions** | Bind to IP/UUID with configurable expiration |
| **Rate Limiting** | Progressive lockout against brute-force attacks |
| **Multi-Language** | `en_us`, `es_mx` built-in, fully customizable |
| **Rich UX** | Titles, BossBar countdown, ActionBar, configurable sounds |

## Requirements

- **Minecraft** 1.21.1
- **Java** 21 or higher
- **Loader**: Fabric (≥0.15.11) or NeoForge (≥21.1)
- **Dependencies**:
  - Fabric: [Fabric API](https://modrinth.com/mod/fabric-api) + [Architectury API](https://modrinth.com/mod/architectury-api)
  - NeoForge: [Architectury API](https://modrinth.com/mod/architectury-api)

## Quick Links

- [Installation Guide](./installation) — Get Vouch running on your server
- [Quick Start](./quick-start) — Minimal setup for your first auth flow
- [Configuration](./configuration/) — All `vouch.toml` options explained
- [Commands](./commands) — Complete command reference
- [FAQ](./faq) — Frequently asked questions

## Architecture

Vouch uses [Architectury API](https://github.com/architectury) to share a single codebase between Fabric and NeoForge. Platform-specific implementations are limited to permission checking and packet sending.

```
common/          # Shared code (auth, commands, config, crypto, db, mixins)
fabric/          # Fabric entrypoint + Fabric Permissions API
neoforge/        # NeoForge entrypoint + NeoForge PermissionAPI
```

::: tip Server-Side Only
Vouch is a **server-side only** mod. Players do not need to install anything on their client.
:::
