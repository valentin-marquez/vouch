# Sessions Configuration

Configure persistent sessions that let players skip re-authentication on reconnect.

## Options

```toml
[session]
persistence = true
duration = 3600
bind_to_ip = true
bind_to_uuid = true
cleanup_interval = 300
```

### `persistence`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

When enabled, authenticated players receive a session token. On their next connection (within the session duration), they are automatically authenticated without needing to re-enter their password.

Set to `false` to require authentication on every connection.

### `duration`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `3600` |

How long a session remains valid after creation. Default is 1 hour.

| Value | Duration |
|-------|----------|
| `1800` | 30 minutes |
| `3600` | 1 hour |
| `86400` | 24 hours |
| `604800` | 7 days |

### `bind_to_ip`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

When enabled, sessions are validated against the player's IP address. A session created from one IP will not be valid from a different IP.

::: warning
Disabling this reduces security. An attacker with a stolen session token could authenticate from any IP.
:::

### `bind_to_uuid`

| | |
|---|---|
| **Type** | Boolean |
| **Default** | `true` |

When enabled, sessions are validated against the player's UUID. This prevents session tokens from being used by different accounts.

### `cleanup_interval`

| | |
|---|---|
| **Type** | Integer (seconds) |
| **Default** | `300` |

How often Vouch cleans up expired sessions from the database. Default is every 5 minutes.

## How Sessions Work

1. Player authenticates via `/login` or `/register`.
2. A **32-byte random token** is generated using `SecureRandom`.
3. The token is **SHA-256 hashed** before being stored in the database.
4. On reconnect, Vouch checks for a valid (non-expired) session matching the player's UUID and IP.
5. If found, the player is automatically authenticated.
6. `/logout` invalidates all sessions for the player.

::: tip
Session tokens are never stored in plain text. Only the SHA-256 hash is persisted in the database, making stolen database records useless.
:::
