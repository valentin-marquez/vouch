# Multi-Platform Support

Vouch runs on both **Fabric** and **NeoForge** through the [Architectury API](https://github.com/architectury).

## Architecture

```
common/          ← Shared code (~95% of the codebase)
├── auth/        AuthManager, PreAuthManager, RateLimiter, AuthMode
├── command/     VouchCommands, TwoFactorCommands
├── config/      VouchConfigManager, EnvResolver
├── crypto/      Argon2Hasher, TOTPEngine
├── db/          ConnectionFactory, DatabaseManager
├── mixin/       4 server-side mixins
└── util/        Messages, LangManager, UXManager, QRMapRenderer,
                 SessionTokenGenerator, PermissionHelper, PacketHelper

fabric/          ← Fabric-specific (~2.5%)
├── VouchFabric.java           DedicatedServerModInitializer
├── PermissionHelperImpl.java  Fabric Permissions API
└── PacketHelperImpl.java      Yarn mappings: sendPacket()

neoforge/        ← NeoForge-specific (~2.5%)
├── VouchNeoForge.java         @Mod entrypoint
├── PermissionHelperImpl.java  NeoForge PermissionAPI
└── PacketHelperImpl.java      Mojang mappings: send()
```

## Architectury API

[Architectury](https://github.com/architectury) provides:

- **Event abstraction**: `PlayerEvent`, `InteractionEvent`, `TickEvent`, `CommandRegistrationEvent`, `LifecycleEvent`
- **Platform detection**: Runtime platform checking
- **ExpectPlatform**: Compile-time platform-specific implementations

### How It Works

Common code defines interfaces and uses `@ExpectPlatform` annotations. At build time, Architectury resolves these to the correct platform implementation.

Example — permission checking:

```java
// common/ — defines the contract
public class PermissionHelper {
    @ExpectPlatform
    public static boolean hasPermission(ServerPlayerEntity player, String node, int defaultLevel) {
        throw new AssertionError(); // replaced at compile time
    }
}

// fabric/ — Fabric implementation
public class PermissionHelperImpl {
    public static boolean hasPermission(ServerPlayerEntity player, String node, int defaultLevel) {
        return Permissions.check(player, node, defaultLevel);
    }
}

// neoforge/ — NeoForge implementation
public class PermissionHelperImpl {
    public static boolean hasPermission(ServerPlayerEntity player, String node, int defaultLevel) {
        return PermissionAPI.getPermission(player, registeredNodes.get(node));
    }
}
```

## Mixins

The following mixins are shared between both platforms:

| Mixin | Target | Purpose |
|-------|--------|---------|
| `ServerPlayNetworkHandlerMixin` | `ServerPlayNetworkHandler` | Blocks chat from unauthenticated players |
| `ServerPlayerEntityMixin` | `ServerPlayerEntity` | Freezes position/rotation during pre-auth |
| `PlayerInteractionMixin` | `ServerPlayerInteractionManager` | Blocks block/item interactions |
| `EntityDamageMixin` | `ServerPlayerEntity` | Makes unauthenticated players invulnerable |

All mixins are server-side only (`JAVA_21` compatibility, Mixin `0.8`+).

## Platform Differences

| Feature | Fabric | NeoForge |
|---------|--------|----------|
| **Entrypoint** | `DedicatedServerModInitializer` | `@Mod` annotation |
| **Permissions** | Fabric Permissions API (LuckPerms compatible) | NeoForge `PermissionAPI` |
| **Permission Registration** | Not required (queried on-demand) | Auto-registered via `PermissionGatherEvent.Nodes` |
| **Packet Sending** | `sendPacket()` (Yarn mappings) | `send()` (Mojang mappings) |
| **Dependencies** | Fabric API + Architectury | Architectury only |

## Building

The project uses Gradle with Architectury Plugin:

```bash
# Build both platforms
./gradlew build

# Build Fabric only
./gradlew :fabric:build

# Build NeoForge only
./gradlew :neoforge:build
```

Output JARs include all dependencies (shadow JAR) with drivers relocated under `com.nozz.vouch.libs.*`.
