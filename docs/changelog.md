# Changelog

All notable changes to Vouch are documented here.

## [0.4.0] — 2026-09-07

- **Commands:** one command tree shared by `/vouch` and `/auth`, with completion and visibility that follow the player's state. New `/vouch password`, `/vouch premium`, `/vouch admin user <player> info|unregister|resetpw` and `/vouch admin premium <player> force-offline|force-online`. `/2fa`, `/l` and `/reg` stay as shortcuts.
- **Fix:** a player who owes a 2FA code can now send it. The `2fa_only` mode could not be completed on a stock configuration (issue #9).
- **Security:** pre-auth jail also blocks commands and inventory; `vouch.bypass.auth` is explicit-grant-only; premium login is async, bounded and fail-closed; session restore now proves identity and the session policy is consulted on join.
- **Config:** new `auth.allowed_commands`; `vouch.toml` is no longer overwritten, and malformed or invalid values are reported.
- **Database:** versioned schema, fixed MySQL DDL, dedicated DB executor, 2FA read fails closed.
- **Crypto:** Argon2 parameters are stored inside the hash (PHC format).

## [0.3.1] — 2026-09-06

- Fix NeoForge crash with BouncyCastle; fetch skins during premium verification (`auth.premium_fetch_skins`).
- Brazilian Portuguese translation.

## [0.3.0] — 2026-05-09

- Mixed-mode premium login with cryptographic verification.
- Shadow jar no longer bundles SLF4J.

## [0.2.1] — 2026-05-09

- Relocation for the checkerframework library; Russian and Ukrainian translations.

## [0.2.0] — 2026-03-01

- Premium auto-login for online accounts (`auth.premium_auto_login`).
- Fix environment variables not being read for integer, boolean and float options.
- Multi-version builds for 1.21.2, 1.21.4, 1.21.5 and 1.21.6 (now frozen as legacy).

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
