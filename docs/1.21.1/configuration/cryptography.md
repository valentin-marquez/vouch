# Cryptography Configuration

Configure the Argon2id password hashing parameters.

## Options

```toml
[crypto.argon2]
memory_cost = 15360
iterations = 2
parallelism = 1
```

### `memory_cost`

| | |
|---|---|
| **Type** | Integer (KiB) |
| **Default** | `15360` (15 MiB) |

The amount of memory used by the Argon2id algorithm per hash operation.

Higher values increase resistance to GPU-based attacks but use more server RAM during hashing.

| Value | Memory | Use Case |
|-------|--------|----------|
| `7680` | ~7.5 MiB | Low-memory environments |
| `15360` | ~15 MiB | **Recommended default** |
| `65536` | ~64 MiB | High-security servers |

### `iterations`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `2` |

The number of iterations (time cost). More iterations increase the time to compute a hash.

| Value | Approximate Time | Use Case |
|-------|-------------------|----------|
| `1` | ~50ms | Minimum |
| `2` | ~100ms | **Recommended** |
| `4` | ~200ms | Increased security |

### `parallelism`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `1` |

The degree of parallelism (number of threads used per hash operation). Higher values can speed up hashing on multi-core systems but use more CPU.

::: tip
The default of `1` is fine for most servers. Vouch already uses a dedicated 4-thread pool for all crypto operations, so multiple hashing operations run concurrently anyway.
:::

## Why Argon2id?

Vouch uses **Argon2id** — the recommended password hashing algorithm by [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). Here's why:

| Algorithm | GPU Resistant | Memory Hard | Side-Channel Safe |
|-----------|:----:|:----:|:-----:|
| SHA-256 | ❌ | ❌ | ❌ |
| bcrypt | ⚠️ | ❌ | ❌ |
| Argon2d | ✅ | ✅ | ❌ |
| Argon2i | ⚠️ | ✅ | ✅ |
| **Argon2id** | ✅ | ✅ | ✅ |

Argon2id combines the benefits of Argon2d (GPU resistance) and Argon2i (side-channel resistance).

## Hash Storage Format

Passwords are stored as:

```
base64(salt)$base64(hash)
```

- **Salt**: 128-bit (16 bytes), randomly generated per password
- **Hash**: 256-bit (32 bytes)
- **Comparison**: Constant-time to prevent timing attacks

## Performance Impact

All hashing operations run on a **dedicated 4-thread async executor**, separate from the main server thread. This means:

- Zero TPS impact during registration or login
- Multiple players can authenticate simultaneously
- The server thread is never blocked by crypto operations
