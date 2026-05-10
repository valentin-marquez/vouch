# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Vouch is a server-side authentication mod for Minecraft (1.21.x) supporting Fabric and NeoForge via Architectury. It provides password auth (Argon2id), TOTP 2FA, session persistence, and premium account auto-login. No client mod required.

## Build Commands

```bash
# Build all platforms (Fabric + NeoForge)
./gradlew build

# Build a single platform
./gradlew :fabric:build
./gradlew :neoforge:build

# Run a dev server (launches Minecraft server with the mod)
./gradlew :fabric:runServer
./gradlew :neoforge:runServer

# Clean build artifacts
./gradlew clean
```

Output JARs land in `fabric/build/libs/` and `neoforge/build/libs/`. The remapped production JARs (not `-dev` or `-dev-shadow`) are the distributable artifacts.

## Architecture

Three Gradle subprojects — `common`, `fabric`, `neoforge` — managed by the Architectury plugin.

**common/** — All game logic lives here. Platform-agnostic code using Architectury abstractions:
- `VouchMod` — Singleton entry point. Registers all Architectury events (join, quit, tick, commands, lifecycle). Manages thread pools (4-thread async executor + scheduler).
- `auth/` — `AuthManager` (authenticated vs pending state), `PreAuthManager` (jail enforcement: position freeze, chat/interaction blocking), `RateLimiter`, `MixedModeLoginHandler` (premium auto-login).
- `command/` — `VouchCommands` (/register, /login, /logout, /vouch admin) and `TwoFactorCommands` (/2fa setup/verify/disable/status).
- `config/` — `VouchConfigManager` loads TOML from `config/vouch/vouch.toml`. Supports `VOUCH_*` env var overrides and `${ENV:VAR}` syntax. `EnvResolver` handles env variable resolution.
- `crypto/` — `Argon2Hasher` (async, BouncyCastle-based, constant-time comparison) and `TOTPEngine` (RFC 6238, QR URI generation).
- `db/` — `DatabaseManager` (async queries, schema management for `vouch_players`/`vouch_sessions` tables), `ConnectionFactory` (HikariCP pooling, supports H2/SQLite/MySQL/PostgreSQL).
- `mixin/` — Bytecode modifications: login interception (premium auto-login), chat blocking, interaction blocking, damage prevention for unauthenticated players.
- `util/` — `UXManager` (titles/bossbar/actionbar/sounds), `LangManager` (i18n from JSON files), `Messages` (formatted text), `QRMapRenderer` (renders QR to Minecraft maps), `PremiumVerifier` (Mojang API), `PermissionHelper`/`PacketHelper` (platform abstraction interfaces).

**fabric/** — `VouchFabric` implements `DedicatedServerModInitializer`, calls `VouchMod.init()`. Contains `PacketHelperImpl` and `PermissionHelperImpl` using Fabric Permissions API.

**neoforge/** — `VouchNeoForge` uses `@Mod` annotation, listens to `FMLDedicatedServerSetupEvent`. Contains platform-specific `PacketHelperImpl` and `PermissionHelperImpl` using NeoForge PermissionAPI.

## Key Patterns

- **Platform abstraction**: `PermissionHelper` and `PacketHelper` are interfaces in common with `Impl` classes in each platform module using Architectury's `@ExpectPlatform` pattern.
- **Dependency relocation**: All third-party libs are shadow-relocated under `com.nozz.vouch.libs.*` to prevent classpath conflicts. Both `fabric/build.gradle` and `neoforge/build.gradle` define identical relocation rules.
- **Async-first**: Crypto operations (Argon2 hashing/verification) and database queries run on the async executor to avoid blocking the server tick thread.
- **Mappings**: Uses Yarn mappings (not Mojmap). All Minecraft class/method references use Yarn names.

## Localization

Language files are in `common/src/main/resources/assets/vouch/lang/` (en_us.json, es_mx.json). Keys use dot notation (e.g., `vouch.login.success`). Variables use `{placeholder}` syntax.

## CI/CD

- `release.yml` — Triggered on GitHub release. Builds both platforms, publishes to Modrinth and CurseForge, attaches JARs to the release.
- `docs.yml` — Triggered on pushes to `main` affecting `docs/`. Builds VitePress docs with Bun and deploys to GitHub Pages.

## Version Branching

Each Minecraft version range gets its own branch: `mc/1.21.1`, `mc/1.21.2`, etc. The `main` branch is the default. `todos.md` contains detailed porting notes for API changes across 1.21.x versions.

## Docs Site

VitePress site in `docs/`. Build with `bun install && bun run docs:build`. Version-specific content lives under `docs/<mc-version>/`.
