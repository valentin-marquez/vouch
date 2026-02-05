# Database

Vouch supports four database backends. Choose based on your server's needs.

## Quick Comparison

| Feature | H2 | SQLite | MySQL | PostgreSQL |
|---------|:--:|:------:|:-----:|:----------:|
| **Setup** | Zero config | Zero config | External server | External server |
| **Performance** | Good | Good | Excellent | Excellent |
| **Concurrent Access** | Single process | Single process | Multi-process | Multi-process |
| **Connection Pool** | N/A | N/A | HikariCP | HikariCP |
| **Best for** | Single server | Single server | Networks | Networks |

---

## H2 (Default) {#h2}

H2 is the default embedded database. It requires no external setup.

```toml
[database]
type = "h2"
```

**Data file:** `vouch/vouch.mv.db` (relative to server directory)

**JDBC URL:** `jdbc:h2:<dataDir>/vouch;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`

H2 runs in MySQL compatibility mode for SQL compatibility. The database file is automatically created on first start.

::: tip
H2 is the recommended choice for single-server setups. It's fast, reliable, and requires zero configuration.
:::

---

## SQLite {#sqlite}

SQLite stores data in a single file, similar to H2 but using the SQLite engine.

```toml
[database]
type = "sqlite"
```

**Data file:** `vouch/vouch.db`

**JDBC URL:** `jdbc:sqlite:<dataDir>/vouch.db`

---

## MySQL {#mysql}

For servers that need a shared database or are part of a network.

```toml
[database]
type = "mysql"
host = "localhost"
port = 3306
name = "vouch"
user = "vouch_user"
password = "${ENV:VOUCH_DB_PASSWORD}"

[database.pool]
max_size = 10
min_idle = 2
```

**JDBC URL:** `jdbc:mysql://<host>:<port>/<name>?useSSL=false&allowPublicKeyRetrieval=true`

### Prerequisites

1. MySQL server 8.0+ installed and running
2. Create a database and user:

```sql
CREATE DATABASE vouch;
CREATE USER 'vouch_user'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON vouch.* TO 'vouch_user'@'localhost';
FLUSH PRIVILEGES;
```

### Connection Pool

Vouch uses [HikariCP](https://github.com/brettwooldridge/HikariCP) for MySQL connection pooling.

| Setting | Default | Description |
|---------|---------|-------------|
| `pool.max_size` | `10` | Maximum connections |
| `pool.min_idle` | `2` | Minimum idle connections |

---

## PostgreSQL {#postgresql}

For servers that prefer PostgreSQL as their database engine.

```toml
[database]
type = "postgresql"
host = "localhost"
port = 5432
name = "vouch"
user = "vouch_user"
password = "${ENV:VOUCH_DB_PASSWORD}"

[database.pool]
max_size = 10
min_idle = 2
```

**JDBC URL:** `jdbc:postgresql://<host>:<port>/<name>?sslmode=require`

### Prerequisites

1. PostgreSQL 14+ installed and running
2. Create a database and user:

```sql
CREATE USER vouch_user WITH PASSWORD 'your_password';
CREATE DATABASE vouch OWNER vouch_user;
```

### Connection Pool

Same HikariCP settings as MySQL apply.

---

## Database Schema {#schema}

Vouch automatically creates the required tables on first start.

### `vouch_players`

Stores player registrations.

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | `VARCHAR(36)` | Player UUID (Primary Key) |
| `username` | `VARCHAR(16)` | Player username |
| `password_hash` | `VARCHAR(255)` | Argon2id hash (empty for 2FA-only accounts) |
| `totp_secret` | `VARCHAR(64)` | TOTP Base32 secret (nullable) |
| `totp_enabled` | `BOOLEAN` | Whether 2FA is active |
| `created_at` | `TIMESTAMP` | Registration timestamp |
| `last_login` | `TIMESTAMP` | Last successful login (nullable) |
| `last_ip` | `VARCHAR(45)` | Last known IP address (nullable) |

### `vouch_sessions`

Stores persistent login sessions.

| Column | Type | Description |
|--------|------|-------------|
| `id` | `INTEGER` / `SERIAL` | Auto-increment ID (Primary Key) |
| `uuid` | `VARCHAR(36)` | Player UUID (FK → `vouch_players`) |
| `ip_address` | `VARCHAR(45)` | Session IP address |
| `session_token` | `VARCHAR(64)` | SHA-256 hash of the session token |
| `created_at` | `TIMESTAMP` | Session creation time |
| `expires_at` | `TIMESTAMP` | Session expiration time |

### Indexes

| Index | Table | Columns |
|-------|-------|---------|
| `idx_sessions_uuid_ip` | `vouch_sessions` | `uuid`, `ip_address` |

### Foreign Keys

- `vouch_sessions.uuid` → `vouch_players.uuid` (`ON DELETE CASCADE`)

::: info
When a player is unregistered via `/vouch admin unregister`, their sessions are automatically deleted due to the cascade constraint.
:::

---

## Bundled Drivers

Vouch bundles all database drivers in the mod JAR (relocated under `com.nozz.vouch.libs.*`):

| Database | Driver | Version |
|----------|--------|---------|
| H2 | `org.h2.Driver` | 2.2.224 |
| SQLite | `org.sqlite.JDBC` | — |
| MySQL | `com.mysql.cj.jdbc.Driver` | 8.3.0 |
| PostgreSQL | `org.postgresql.Driver` | 42.7.1 |

No external driver installation is required.
