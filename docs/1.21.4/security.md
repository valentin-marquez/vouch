# Security

An overview of Vouch's security architecture and design decisions.

## Password Hashing — Argon2id {#argon2id}

Vouch uses **Argon2id** for password hashing, the algorithm recommended by [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) and the winner of the [Password Hashing Competition](https://www.password-hashing.net/).

### Why Argon2id?

| Algorithm | GPU Resistant | Memory Hard | Side-Channel Safe | Recommendation |
|-----------|:---:|:---:|:---:|---|
| SHA-256 | ❌ | ❌ | ❌ | Never use for passwords |
| bcrypt | ⚠️ Limited | ❌ | ❌ | Legacy, being phased out |
| scrypt | ✅ | ✅ | ❌ | Good, but Argon2 is newer |
| **Argon2id** | ✅ | ✅ | ✅ | **Current best practice** |

Argon2id is a hybrid of:
- **Argon2d**: Maximizes GPU resistance
- **Argon2i**: Resists side-channel attacks

### Implementation Details

- **Library**: Bouncy Castle (pure Java — no native dependencies)
- **Salt**: 128-bit (16 bytes), randomly generated per password via `SecureRandom`
- **Hash output**: 256-bit (32 bytes)
- **Storage format**: `base64(salt)$base64(hash)`
- **Comparison**: Constant-time to prevent timing attacks
- **Threading**: Dedicated 4-thread async executor (never blocks main thread)

### Configurable Parameters

```toml
[crypto.argon2]
memory_cost = 15360   # 15 MiB per hash
iterations = 2        # time cost
parallelism = 1       # threads per hash
```

See [Cryptography Configuration](./configuration/cryptography) for tuning guidance.

---

## TOTP — Time-Based One-Time Passwords {#totp}

Vouch implements [RFC 6238](https://tools.ietf.org/html/rfc6238) compliant TOTP for two-factor authentication.

### Specification

| Parameter | Value |
|-----------|-------|
| **Algorithm** | HmacSHA1 |
| **Digits** | 6 |
| **Time step** | 30 seconds (configurable) |
| **Secret size** | 160-bit (32 Base32 characters) |
| **Window** | ±1 step (configurable) |

### QR Code Delivery

Instead of sending URLs or secrets via chat (which could be logged), Vouch renders QR codes directly onto virtual Minecraft maps:

1. A virtual map ID is generated (`32767 - player.getId()`)
2. The QR code is rendered using ZXing (116×116 px, 6px margins)
3. Sent via `MapUpdateS2CPacket` — **no file is created on disk**
4. The player's current held item is saved and restored after scanning

### Compatible Apps

Google Authenticator, Authy, Aegis, Microsoft Authenticator, 1Password, and any app supporting `otpauth://` URIs.

---

## Rate Limiting {#rate-limiting}

Vouch protects against brute-force attacks with IP-based rate limiting.

### Progressive Lockout

| Failed Attempts | Action |
|:-:|---|
| `max_attempts / 2` (min 3) | 30-second lockout |
| `max_attempts` | Lockout for `lockout_duration` (default: 5 min) |
| `max_attempts × 2` | 1-hour lockout |

### Implementation

- **Tracking**: IP-based using Guava Cache with 1-hour TTL
- **Reset**: Counters are cleared on successful login
- **Feedback**: Players receive a "rate limited" message and a configurable sound

---

## Persistent Sessions {#sessions}

Sessions allow players to reconnect without re-authenticating.

### Security Model

1. **Token generation**: 32 bytes (256 bits) via `SecureRandom`
2. **Storage**: Only the **SHA-256 hash** of the token is stored in the database
3. **Binding**: Sessions are bound to both UUID and IP address (configurable)
4. **Expiration**: Configurable duration (default: 1 hour)
5. **Cleanup**: Automatic periodic removal of expired sessions

This means:
- Even if the database is compromised, session tokens cannot be recovered
- Session hijacking requires access to the same IP address
- Old sessions are automatically pruned

### Session Lifecycle

```
Player authenticates
  → 32-byte random token generated
  → SHA-256(token) stored in database with UUID + IP + expiry
  → On reconnect: validate UUID + IP against stored hash
  → /logout: deletes all sessions for the player
  → Expired sessions: cleaned up every 5 minutes
```

---

## Environment Variables {#env-vars}

Vouch supports environment variables to protect sensitive configuration values like database credentials.

### Explicit Syntax

```toml
password = "${ENV:DB_PASSWORD}"
password = "${ENV:DB_PASSWORD:default_value}"  # with fallback
```

### Auto-Override

Any config key can be overridden via environment variables:

| Config Key | Environment Variable |
|------------|---------------------|
| `database.password` | `VOUCH_DATABASE_PASSWORD` |
| `database.user` | `VOUCH_DATABASE_USER` |
| `database.host` | `VOUCH_DATABASE_HOST` |
| `auth.login_timeout` | `VOUCH_AUTH_LOGIN_TIMEOUT` |

### Warnings

Vouch warns in the console if sensitive keys contain plain-text values:
- `database.password`
- `database.user`

---

## Pre-Auth Isolation ("Jail") {#pre-auth}

While unauthenticated, players are fully isolated from the game world:

| Protection | Description |
|------------|-------------|
| **Chat blocked** | Cannot send or see chat messages |
| **Commands blocked** | Only auth commands are allowed |
| **Movement frozen** | Teleported back to join position each tick |
| **Invulnerable** | All damage is cancelled |
| **Invisible** | Hidden from other players |
| **Tab list hidden** | Removed from the player list |
| **Interactions blocked** | Cannot break/place blocks, use items, or interact with entities |
| **No drops** | Item dropping is prevented |
| **Flight allowed** | Temporarily enabled to prevent fall-damage kicks |

All protections are implemented via Mixins and Architectury events, ensuring they work identically on Fabric and NeoForge.

---

## Threat Model

| Threat | Mitigation |
|--------|-----------|
| Password brute-force | Rate limiting with progressive lockout |
| Credential stuffing | Argon2id makes large-scale attacks impractical |
| Session hijacking | IP binding + hashed tokens |
| Database compromise | Passwords stored as Argon2id hashes, sessions as SHA-256 hashes |
| Timing attacks | Constant-time hash comparison |
| Network sniffing (MITM) | Passwords only sent via Minecraft protocol, never in plain text over HTTP |
| Player impersonation | UUID + IP session binding |
| Main thread blocking | All crypto operations run on dedicated async executor |
