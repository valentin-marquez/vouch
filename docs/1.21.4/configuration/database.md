# Database Configuration

Configure the database backend that Vouch uses to store player registrations and sessions.

## Options

```toml
[database]
type = "h2"
host = "localhost"
port = 3306
name = "vouch"
user = "root"
password = ""

[database.pool]
max_size = 10
min_idle = 2
```

### `type`

| | |
|---|---|
| **Type** | String |
| **Default** | `"h2"` |
| **Values** | `h2`, `sqlite`, `mysql`, `postgresql` |

The database engine to use. See the [Database deep dive](../database) for detailed setup instructions for each type.

### `host`

| | |
|---|---|
| **Type** | String |
| **Default** | `"localhost"` |
| **Used by** | MySQL, PostgreSQL |

The hostname or IP address of the database server. Ignored for H2 and SQLite.

### `port`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `3306` |
| **Used by** | MySQL, PostgreSQL |

The port of the database server. Common defaults:
- MySQL: `3306`
- PostgreSQL: `5432`

### `name`

| | |
|---|---|
| **Type** | String |
| **Default** | `"vouch"` |
| **Used by** | MySQL, PostgreSQL |

The database name to connect to. Make sure this database exists before starting the server.

### `user`

| | |
|---|---|
| **Type** | String |
| **Default** | `"root"` |
| **Used by** | MySQL, PostgreSQL |

The database username.

::: warning
Avoid using `root` in production. Create a dedicated database user with limited permissions.
:::

### `password`

| | |
|---|---|
| **Type** | String |
| **Default** | `""` |
| **Used by** | MySQL, PostgreSQL |

The database password. Supports environment variable syntax:

```toml
password = "${ENV:DB_PASSWORD}"
```

See [Environment Variables](../configuration/#environment-variables) for details.

## Connection Pool

Vouch uses [HikariCP](https://github.com/brettwooldridge/HikariCP) for connection pooling with MySQL and PostgreSQL.

### `pool.max_size`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `10` |
| **Used by** | MySQL, PostgreSQL |

Maximum number of connections in the pool. For most Minecraft servers, 10 is more than enough.

### `pool.min_idle`

| | |
|---|---|
| **Type** | Integer |
| **Default** | `2` |
| **Used by** | MySQL, PostgreSQL |

Minimum number of idle connections maintained in the pool.

## Examples

### MySQL

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

### PostgreSQL

```toml
[database]
type = "postgresql"
host = "db.example.com"
port = 5432
name = "vouch"
user = "vouch_user"
password = "${ENV:VOUCH_DB_PASSWORD}"

[database.pool]
max_size = 15
min_idle = 3
```
