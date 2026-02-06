# API Reference

::: warning Work in Progress
Vouch does not currently expose a public API for other mods. The information on this page is for developers interested in understanding Vouch's internal architecture or contributing to the project.
:::

## Internal Architecture

### Core Classes

| Class | Package | Description |
|-------|---------|-------------|
| `VouchMod` | `com.nozz.vouch` | Main mod class, registers events and initializes systems |
| `AuthManager` | `com.nozz.vouch.auth` | Central authentication logic |
| `PreAuthManager` | `com.nozz.vouch.auth` | Pre-auth state management (jail, effects, timers) |
| `AuthMode` | `com.nozz.vouch.auth` | Authentication mode enum |
| `RateLimiter` | `com.nozz.vouch.auth` | IP-based rate limiting |
| `PlayerSession` | `com.nozz.vouch.auth` | Player session data container |

### Database Layer

| Class | Description |
|-------|-------------|
| `DatabaseManager` | All database operations (async via `CompletableFuture`) |
| `ConnectionFactory` | JDBC connection creation and HikariCP pool management |

### Key Database Methods

```java
CompletableFuture<Boolean> isRegistered(UUID uuid)
CompletableFuture<Void> registerPlayer(UUID uuid, String username, String passwordHash)
CompletableFuture<Void> registerPlayerWith2FA(UUID uuid, String username, String totpSecret)
CompletableFuture<String> getPasswordHash(UUID uuid)
CompletableFuture<Void> updateLastLogin(UUID uuid, String ip)
CompletableFuture<Void> storeTOTPSecret(UUID uuid, String secret)
CompletableFuture<String> getTOTPSecret(UUID uuid)
CompletableFuture<Boolean> has2FAEnabled(UUID uuid)
CompletableFuture<Void> disable2FA(UUID uuid)
CompletableFuture<Void> unregisterPlayer(UUID uuid)
CompletableFuture<Void> createSession(UUID uuid, String ip, String tokenHash, long expiresAt)
CompletableFuture<Boolean> validateSession(UUID uuid, String ip)
CompletableFuture<Boolean> hasAnyValidSession(UUID uuid)
CompletableFuture<Void> deleteSession(UUID uuid, String ip)
CompletableFuture<Void> deleteAllSessions(UUID uuid)
CompletableFuture<Void> cleanupExpiredSessions()
```

### Cryptography

| Class | Description |
|-------|-------------|
| `Argon2Hasher` | Argon2id hashing and verification (async) |
| `TOTPEngine` | TOTP code generation and validation |
| `SessionTokenGenerator` | Secure random session token generation |

### Utility Classes

| Class | Description |
|-------|-------------|
| `Messages` | Chat message formatting with color codes |
| `LangManager` | Language file loading and message resolution |
| `UXManager` | Title, BossBar, ActionBar, and sound management |
| `QRMapRenderer` | QR code rendering onto virtual maps |
| `PermissionHelper` | Cross-platform permission checking |
| `PacketHelper` | Cross-platform packet sending |
| `EnvResolver` | Environment variable resolution in config |

## Events Used

Vouch listens to the following Architectury events:

| Event | Handler |
|-------|---------|
| `PlayerEvent.PLAYER_JOIN` | Check session or start pre-auth |
| `PlayerEvent.PLAYER_QUIT` | Remove player from tracking |
| `InteractionEvent.RIGHT_CLICK_BLOCK` | Block for unauthenticated |
| `InteractionEvent.LEFT_CLICK_BLOCK` | Block for unauthenticated |
| `InteractionEvent.INTERACT_ENTITY` | Block for unauthenticated |
| `TickEvent.PLAYER_POST` | Enforce position freeze |
| `CommandRegistrationEvent` | Register all commands |
| `LifecycleEvent.SERVER_STARTING` | Init database, start schedulers |
| `LifecycleEvent.SERVER_STOPPING` | Cleanup and shutdown |

## Threading Model

| Thread Pool | Size | Purpose |
|-------------|------|---------|
| Crypto Executor | 4 threads | Argon2id hashing/verification |
| Session Cleanup | 1 thread (scheduled) | Periodic expired session removal |
| Main Server Thread | — | Player-facing actions (via `server.execute()`) |

All database operations return `CompletableFuture` and run on the common fork-join pool. Results that affect gameplay are dispatched back to the main server thread.

## Future API Plans

A public API surface is planned for future versions, potentially including:

- **Authentication events** — `PlayerAuthenticatedEvent`, `PlayerRegisteredEvent`, `PlayerLoggedOutEvent`
- **Auth state queries** — `isAuthenticated(player)`, `isPendingAuth(player)`
- **Custom auth providers** — Extend the authentication system with custom logic
- **Webhook integrations** — Notify external services of auth events

If you're interested in API features, please [open an issue](https://github.com/nozzdev/vouch/issues) on GitHub.
