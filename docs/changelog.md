# Changelog

All notable changes to Vouch are documented here.

## [0.1.0] — 2026-02-05

### Initial Release (MVP)

**Platforms:** Fabric, NeoForge · **Minecraft:** 1.21.1 · **Java:** 21+

#### Features

- **Authentication System**
  - Three auth modes: `password_only`, `2fa_only`, `password_optional_2fa`
  - Argon2id password hashing via Bouncy Castle
  - TOTP 2FA (RFC 6238) with in-game QR code rendering
  - Persistent sessions with IP/UUID binding

- **Database Support**
  - H2 (embedded, default)
  - SQLite
  - MySQL with HikariCP pooling
  - PostgreSQL with HikariCP pooling
  - Environment variable support for credentials

- **Security**
  - IP-based rate limiting with progressive lockout
  - Pre-auth player isolation (jail)
  - SHA-256 hashed session tokens
  - Constant-time hash comparison

- **User Experience**
  - Titles, subtitles, action bar, and boss bar
  - Configurable sound effects
  - Blindness, invisibility, and position freeze effects
  - Theme colors

- **Multi-Platform**
  - Fabric + NeoForge via Architectury API
  - Fabric Permissions API integration (LuckPerms compatible)
  - NeoForge PermissionAPI integration

- **Internationalization**
  - English (`en_us`) and Spanish (`es_mx`) built-in
  - Fully customizable language files
  - Hot-reloadable via `/vouch admin reload`

- **Administration**
  - `/vouch admin reload` — reload config and language files
  - `/vouch admin unregister <player>` — remove player registration
  - `/vouch admin export-lang` — export language file
  - `/auth` alias for `/vouch`

---

<!--
## [0.2.0] — TBD

### Planned
- Velocity proxy support
- BungeeCord proxy support
- Public API for mod integration
- Additional languages
-->
