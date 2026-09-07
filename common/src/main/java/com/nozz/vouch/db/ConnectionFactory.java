package com.nozz.vouch.db;

import com.nozz.vouch.config.VouchConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Connection factory for database access.
 *
 * Supports the database types Vouch actually ships drivers for:
 * - H2 (default, embedded)
 * - MySQL
 * - PostgreSQL
 *
 * SQLite is deliberately absent: the {@code org.xerial:sqlite-jdbc} driver is
 * declared in no build file and is not in the shipped jar, so selecting it
 * could only ever fail at boot with a driver-not-found error.
 *
 * Uses HikariCP for high-performance connection pooling.
 */
public final class ConnectionFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/ConnectionFactory");

    /** Fallback pool size when the pool has not been initialised yet. */
    public static final int DEFAULT_POOL_MAX_SIZE = 10;

    private static ConnectionFactory instance;
    private HikariDataSource dataSource;
    private DatabaseType databaseType;
    private int poolMaxSize = DEFAULT_POOL_MAX_SIZE;

    /**
     * Callbacks run (in registration order) immediately before the pool is closed,
     * so that components owning blocking work can drain before their connections
     * are yanked. Cleared once the pool is closed.
     */
    private final List<Runnable> preCloseHooks = new CopyOnWriteArrayList<>();

    public enum DatabaseType {
        H2("org.h2.Driver"),
        MYSQL("com.mysql.cj.jdbc.Driver"),
        POSTGRESQL("org.postgresql.Driver");

        private final String driverClass;

        DatabaseType(String driverClass) {
            this.driverClass = driverClass;
        }

        public String getDriverClass() {
            return driverClass;
        }
        
        /**
         * Get the driver class name, handling potential relocation by shadow plugin.
         * In production (shaded jar), drivers are relocated to com.nozz.vouch.libs.*
         */
        public String getResolvedDriverClass() {
            // List of classloaders to try
            ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                ConnectionFactory.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
            };
            
            // Try original class first
            for (ClassLoader loader : loaders) {
                if (loader != null) {
                    try {
                        Class.forName(driverClass, true, loader);
                        return driverClass;
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }

            // Try relocated class (production shadow jar)
            String relocated = switch (this) {
                case H2 -> "com.nozz.vouch.libs.h2.Driver";
                case MYSQL -> "com.nozz.vouch.libs.mysql.cj.jdbc.Driver";
                case POSTGRESQL -> "com.nozz.vouch.libs.postgresql.Driver";
            };
            
            for (ClassLoader loader : loaders) {
                if (loader != null) {
                    try {
                        Class.forName(relocated, true, loader);
                        return relocated;
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            
            // Fall back to original
            return driverClass;
        }
    }

    private ConnectionFactory() {
    }

    public static ConnectionFactory getInstance() {
        if (instance == null) {
            instance = new ConnectionFactory();
        }
        return instance;
    }

    /**
     * Initialize connection pool using the server's data directory
     * 
     * @param serverDir Path to the server directory
     */
    public void initialize(java.nio.file.Path serverDir) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        // Ensure absolute path for database drivers (H2 requires it)
        String dataDir = serverDir.toAbsolutePath().resolve("vouch").toString();
        
        // Create vouch data directory
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dataDir));
        } catch (java.io.IOException e) {
            LOGGER.warn("Could not create vouch data directory", e);
        }
        
        String type = config.getDatabaseType().toLowerCase();
        String jdbcUrl = config.getJdbcUrl(dataDir);
        
        if ("sqlite".equals(type)) {
            // Silently falling back to H2 here would hand the H2 driver a `jdbc:sqlite:`
            // URL and fail with something unrelated-looking. Say what is actually wrong.
            throw new IllegalStateException("database.type = \"sqlite\" is not supported: "
                    + "Vouch ships no SQLite JDBC driver. Use \"h2\" (embedded, the default), "
                    + "\"mysql\" or \"postgresql\".");
        }

        DatabaseType dbType = switch (type) {
            case "mysql" -> DatabaseType.MYSQL;
            case "postgresql", "postgres" -> DatabaseType.POSTGRESQL;
            default -> DatabaseType.H2;
        };
        
        if (dbType == DatabaseType.H2) {
            initialize(dbType, jdbcUrl, null, null);
        } else {
            initialize(dbType, jdbcUrl, config.getDatabaseUser(), config.getDatabasePassword());
        }
    }

    /**
     * Initialize the connection pool with H2 (default embedded database)
     * 
     * @param dataDir Path to the data directory
     */
    public void initializeH2(String dataDir) {
        String jdbcUrl = String.format(
                "jdbc:h2:%s/vouch;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE",
                dataDir
        );
        initialize(DatabaseType.H2, jdbcUrl, null, null);
    }

    /**
     * Initialize the connection pool with custom configuration, taking the pool
     * sizing from {@link VouchConfigManager}.
     */
    public void initialize(DatabaseType type, String jdbcUrl, String username, String password) {
        VouchConfigManager vouchConfig = VouchConfigManager.getInstance();
        initialize(type, jdbcUrl, username, password,
                vouchConfig.getDatabasePoolMaxSize(), vouchConfig.getDatabasePoolMinIdle());
    }

    /**
     * Initialize the connection pool with custom configuration and explicit pool sizing.
     *
     * This overload takes no dependency on {@link VouchConfigManager}, so it can be
     * driven by tests (or any caller) without the mod's configuration being loaded.
     */
    public void initialize(DatabaseType type, String jdbcUrl, String username, String password,
                           int poolMaxSize, int poolMinIdle) {
        if (dataSource != null) {
            LOGGER.warn("Connection pool already initialized, closing existing pool...");
            close();
        }

        this.databaseType = type;

        // Load the driver class explicitly to handle shadow plugin relocation
        String driverClass = type.getResolvedDriverClass();
        ClassLoader driverLoader = null;
        
        // Try multiple classloaders to find the driver
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            ConnectionFactory.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        
        for (ClassLoader loader : loaders) {
            if (loader != null) {
                try {
                    Class.forName(driverClass, true, loader);
                    driverLoader = loader;
                    LOGGER.debug("Loaded JDBC driver: {} using {}", driverClass, loader.getClass().getSimpleName());
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        
        if (driverLoader == null) {
            LOGGER.error("Failed to load JDBC driver: {} - tried multiple classloaders", driverClass);
            throw new RuntimeException("JDBC driver not found: " + driverClass);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(driverClass);

        if (username != null) {
            config.setUsername(username);
        }
        if (password != null) {
            config.setPassword(password);
        }

        // Connection pool settings
        this.poolMaxSize = Math.max(1, poolMaxSize);
        config.setMaximumPoolSize(this.poolMaxSize);
        config.setMinimumIdle(Math.max(0, Math.min(poolMinIdle, this.poolMaxSize)));
        config.setIdleTimeout(300000);        // 5 minutes
        config.setConnectionTimeout(10000);   // 10 seconds
        config.setMaxLifetime(1800000);       // 30 minutes
        config.setPoolName("Vouch-HikariPool");

        // Performance optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            this.dataSource = new HikariDataSource(config);
            LOGGER.info("Database connection pool initialized (type={}, url={})",
                    type.name(), sanitizeJdbcUrl(jdbcUrl));
        } catch (Exception e) {
            LOGGER.error("Failed to initialize database connection pool", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Get a connection from the pool
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Connection pool not initialized");
        }
        return dataSource.getConnection();
    }

    /**
     * Get the underlying DataSource (for advanced usage)
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    /**
     * Maximum size of the connection pool. Falls back to {@link #DEFAULT_POOL_MAX_SIZE}
     * before {@code initialize} has run, so callers sizing their own resources from it
     * always get a sane number.
     */
    public int getPoolMaxSize() {
        return poolMaxSize;
    }

    /**
     * Register a callback to run just before the pool is closed. Used by
     * {@link DatabaseManager} to drain its executor without the shutdown path
     * having to know about it.
     */
    public void addPreCloseHook(Runnable hook) {
        if (hook != null) {
            preCloseHooks.add(hook);
        }
    }

    public boolean isInitialized() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * Close the connection pool
     */
    public void close() {
        for (Runnable hook : preCloseHooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOGGER.warn("Pre-close hook failed", e);
            }
        }
        preCloseHooks.clear();

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Database connection pool closed");
        }
        dataSource = null;
    }

    /**
     * Sanitize JDBC URL for logging (hide password if present)
     */
    private String sanitizeJdbcUrl(String url) {
        return url.replaceAll("password=[^&;]*", "password=***");
    }
}
