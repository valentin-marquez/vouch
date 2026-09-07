package com.nozz.vouch.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static com.nozz.vouch.db.DatabaseTestSupport.freshDatabaseName;
import static com.nozz.vouch.db.DatabaseTestSupport.openPool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFactoryTest {

    @AfterEach
    void tearDown() {
        DatabaseTestSupport.tearDown();
    }

    /**
     * SQLite was selectable in config but {@code org.xerial:sqlite-jdbc} is declared in
     * no build file and is not in the shipped jar, so {@code type = "sqlite"} could only
     * ever throw "JDBC driver not found" at boot.
     */
    @Test
    void onlyShippedDriversAreSelectable() {
        List<String> types = Arrays.stream(ConnectionFactory.DatabaseType.values())
                .map(Enum::name)
                .toList();
        assertEquals(List.of("H2", "MYSQL", "POSTGRESQL"), types);
        assertFalse(types.contains("SQLITE"), "SQLite has no driver on the classpath");
    }

    /** The seam that lets the tests run without VouchConfigManager being loaded. */
    @Test
    void poolSizeComesFromTheExplicitOverloadNotTheConfigSingleton() throws SQLException {
        openPool(freshDatabaseName("vouch_cf"), 3, 1);

        assertEquals(3, ConnectionFactory.getInstance().getPoolMaxSize());
        assertTrue(ConnectionFactory.getInstance().isInitialized());
        try (Connection conn = ConnectionFactory.getInstance().getConnection()) {
            assertTrue(conn.isValid(2));
        }
    }

    @Test
    void poolSizeFallsBackToADefaultBeforeInitialization() {
        ConnectionFactory.getInstance().close();
        assertTrue(ConnectionFactory.getInstance().getPoolMaxSize() >= 1,
                "callers sizing their own resources must always get a sane number");
    }

    @Test
    void gettingAConnectionAfterCloseFails() {
        openPool(freshDatabaseName("vouch_cf_closed"));
        ConnectionFactory.getInstance().close();

        assertThrows(SQLException.class, () -> ConnectionFactory.getInstance().getConnection());
    }

    @Test
    void preCloseHooksRunBeforeThePoolGoesAway() {
        openPool(freshDatabaseName("vouch_cf_hook"));

        boolean[] ran = {false};
        ConnectionFactory.getInstance().addPreCloseHook(() -> {
            ran[0] = true;
            // The pool is still usable at this point, which is the whole point of the hook.
            assertTrue(ConnectionFactory.getInstance().isInitialized());
        });

        ConnectionFactory.getInstance().close();
        assertTrue(ran[0]);
    }

    @Test
    void aFailingPreCloseHookDoesNotBlockShutdown() {
        openPool(freshDatabaseName("vouch_cf_badhook"));

        boolean[] secondRan = {false};
        ConnectionFactory factory = ConnectionFactory.getInstance();
        factory.addPreCloseHook(() -> {
            throw new IllegalStateException("boom");
        });
        factory.addPreCloseHook(() -> secondRan[0] = true);

        factory.close();

        assertTrue(secondRan[0], "one broken hook must not strand the others");
        assertFalse(factory.isInitialized());
    }
}
