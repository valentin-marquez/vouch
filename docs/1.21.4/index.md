# Vouch — Minecraft 1.21.4

<div class="tip custom-block" style="padding-top: 8px">
  Current version: <strong>0.1.0</strong> · Minecraft <strong>1.21.4</strong> · Java <strong>21+</strong>
</div>

::: info Multi-Version Support
Vouch supports multiple Minecraft 1.21.x versions. See the [GitHub releases](https://github.com/valentin-marquez/vouch/releases) for version-specific downloads.
Currently supported: **1.21.1**, **1.21.2-1.21.3**, **1.21.4** — more coming soon.
:::

## Download

Vouch is available on multiple platforms:

[![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg)](https://modrinth.com/mod/vouch)
[![CurseForge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg)](https://legacy.curseforge.com/minecraft/mc-mods/vouch)
[![GitHub Releases](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg)](https://github.com/valentin-marquez/vouch/releases)

**Vouch** is a secure, server-side authentication mod for Minecraft. It supports both **Fabric** and **NeoForge** through the Architectury API.

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

- **Minecraft** 1.21.4
- **Java** 21 or higher
- **Loader**: Fabric or NeoForge
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
