package com.nozz.vouch.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VouchConfigManager}.
 *
 * <p>The manager is pure NightConfig + java.nio, so every test runs against a real
 * {@code vouch.toml} inside a {@link TempDir}. No Minecraft types are involved.
 */
class VouchConfigManagerTest {

    @TempDir
    Path baseDir;

    @AfterEach
    void tearDown() {
        VouchConfigManager.resetForTesting();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Path configFile() {
        return baseDir.resolve("vouch").resolve("vouch.toml");
    }

    private Path backupFile() {
        return baseDir.resolve("vouch").resolve("vouch.toml.bak");
    }

    private void writeConfig(String toml) throws IOException {
        Path file = configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, toml, StandardCharsets.UTF_8);
    }

    private String readConfig() throws IOException {
        return Files.readString(configFile(), StandardCharsets.UTF_8);
    }

    private VouchConfigManager init() {
        return VouchConfigManager.initialize(baseDir);
    }

    // ------------------------------------------------------------------
    // First run
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing config file is created with documented defaults")
    void createsDefaultConfigWhenMissing() throws IOException {
        VouchConfigManager config = init();

        assertTrue(Files.exists(configFile()), "config file should have been created");
        String written = readConfig();
        assertTrue(written.contains("mod_name"), "expected branding.mod_name in:\n" + written);
        assertTrue(written.contains("[database]"), "expected a [database] section in:\n" + written);
        assertTrue(written.contains("allowed_commands"), "expected auth.allowed_commands in:\n" + written);
        assertEquals("h2", config.getDatabaseType());
        assertEquals(10, config.getDatabasePoolMaxSize());
    }

    @Test
    @DisplayName("the file is on disk by the time save() returns (no async writer race)")
    void saveIsSynchronous() throws IOException {
        init();
        // No sleeping, no polling: if the write were asynchronous this would be flaky/empty.
        assertTrue(Files.size(configFile()) > 0, "config file should be fully written when save() returns");
    }

    // ------------------------------------------------------------------
    // Bug 1a: a hand-written config must survive a round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("user comments, unknown keys and custom sections survive a load/save round trip")
    void handWrittenConfigSurvivesRoundTrip() throws IOException {
        writeConfig("""
                # My server's Vouch config - do not touch, ask Marta first.
                [branding]
                # keep this in Spanish
                mod_name = "Autenticacion"
                prefix = "&8[&bAuth&8]&r "

                [database]
                type = "mysql"
                host = "db.internal"
                port = 3307
                # our DBA insists on this
                name = "vouch_prod"
                user = "vouch"
                password = "plain"
                experimental_flag = true

                [my_custom_section]
                hello = "world"
                answer = 42
                """);

        VouchConfigManager config = init();
        config.save();

        String after = readConfig();

        assertTrue(after.contains("ask Marta first"), "top comment lost:\n" + after);
        assertTrue(after.contains("keep this in Spanish"), "entry comment lost:\n" + after);
        assertTrue(after.contains("our DBA insists on this"), "entry comment lost:\n" + after);
        assertTrue(after.contains("experimental_flag"), "unknown key lost:\n" + after);
        assertTrue(after.contains("my_custom_section"), "custom section lost:\n" + after);
        assertTrue(after.contains("hello"), "custom section contents lost:\n" + after);

        // and the user's own values are still the values
        assertEquals("Autenticacion", config.getBrandingModName());
        assertEquals("mysql", config.getDatabaseType());
        assertEquals(3307, config.getDatabasePort());
        assertTrue(after.contains("3307"), "user value rewritten:\n" + after);
    }

    // ------------------------------------------------------------------
    // Bug 1b: a broken config must never be overwritten
    // ------------------------------------------------------------------

    @Test
    @DisplayName("malformed TOML fails loudly and leaves the file untouched")
    void malformedTomlDoesNotOverwriteFile() throws IOException {
        String original = """
                [branding]
                mod_name = "Vouch
                prefix = &8[&6Vouch&8]&r
                [[[ nope
                """;
        writeConfig(original);

        assertThrows(VouchConfigManager.ConfigException.class, this::init);

        assertEquals(original, readConfig(), "a parse failure must not rewrite the user's file");
    }

    @Test
    @DisplayName("a wrongly typed value fails loudly and leaves the file untouched")
    void wrongTypeDoesNotOverwriteFile() throws IOException {
        String original = """
                [totp]
                issuer = 42
                time_step = 30
                """;
        writeConfig(original);

        VouchConfigManager.ConfigException error =
                assertThrows(VouchConfigManager.ConfigException.class, this::init);

        assertTrue(error.getMessage().contains("totp.issuer"),
                "the error should name the offending key, was: " + error.getMessage());
        assertEquals(original, readConfig(), "a type error must not rewrite the user's file");
    }

    @Test
    @DisplayName("an unreadable config aborts initialization instead of booting on defaults")
    void unreadableConfigAbortsInitialization() throws IOException {
        writeConfig("this is not = = toml\n");

        assertThrows(VouchConfigManager.ConfigException.class, this::init);
        assertThrows(IllegalStateException.class, VouchConfigManager::getInstance,
                "no half-initialized singleton must be left behind");
    }

    // ------------------------------------------------------------------
    // Bug 1c: ${ENV:...} must never be resolved into the file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a ${ENV:...} reference stays a reference in the file, never the resolved secret")
    void envReferenceIsNeverSerializedResolved() throws IOException {
        writeConfig("""
                [database]
                type = "mysql"
                password = "${ENV:VOUCH_TEST_UNSET_VARIABLE:not-a-real-password-test-fixture}"
                """);

        VouchConfigManager config = init();

        // the running mod sees the resolved value...
        assertEquals("not-a-real-password-test-fixture", config.getDatabasePassword());

        config.save();
        String after = readConfig();

        // ...but the file keeps the reference.
        assertTrue(after.contains("${ENV:VOUCH_TEST_UNSET_VARIABLE:not-a-real-password-test-fixture}"),
                "the ${ENV:...} reference was destroyed:\n" + after);
        assertFalse(after.contains("password = \"not-a-real-password-test-fixture\""),
                "the resolved secret was written to disk in cleartext:\n" + after);
    }

    @Test
    @DisplayName("a type error elsewhere cannot leak the resolved password")
    void typeErrorElsewhereCannotLeakPassword() throws IOException {
        String original = """
                [database]
                password = "${ENV:VOUCH_TEST_UNSET_VARIABLE:not-a-real-password-test-fixture}"

                [ui.sounds]
                volume = "loud"
                """;
        writeConfig(original);

        assertThrows(VouchConfigManager.ConfigException.class, this::init);

        String after = readConfig();
        assertEquals(original, after);
        assertFalse(after.contains("password = \"not-a-real-password-test-fixture\""),
                "resolved secret leaked into the file:\n" + after);
    }

    // ------------------------------------------------------------------
    // Backups
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the previous file is backed up before it is rewritten")
    void backsUpBeforeRewriting() throws IOException {
        String original = """
                [branding]
                mod_name = "Backup me"
                """;
        writeConfig(original);

        init();

        assertTrue(Files.exists(backupFile()), "expected vouch.toml.bak next to vouch.toml");
        assertEquals(original, Files.readString(backupFile(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a complete config is not rewritten and not backed up")
    void completeConfigIsLeftAlone() throws IOException {
        init();
        String generated = readConfig();
        Files.deleteIfExists(backupFile());
        VouchConfigManager.resetForTesting();

        init();

        assertEquals(generated, readConfig(), "a config with no missing keys must not be rewritten");
        assertFalse(Files.exists(backupFile()), "nothing was written, so nothing should be backed up");
    }

    // ------------------------------------------------------------------
    // Bug 2: validation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("database.pool.max_size = 0 is clamped to a usable pool")
        void clampsPoolSize() throws IOException {
            writeConfig("""
                    [database.pool]
                    max_size = 0
                    min_idle = 0
                    """);

            VouchConfigManager config = init();

            assertTrue(config.getDatabasePoolMaxSize() >= 1,
                    "Hikari throws on maximumPoolSize < 1, which strands every player");
            assertTrue(config.getDatabasePoolMinIdle() <= config.getDatabasePoolMaxSize());
        }

        @Test
        @DisplayName("min_idle above max_size is clamped down to max_size")
        void clampsMinIdleToMaxSize() throws IOException {
            writeConfig("""
                    [database.pool]
                    max_size = 4
                    min_idle = 50
                    """);

            VouchConfigManager config = init();

            assertEquals(4, config.getDatabasePoolMaxSize());
            assertEquals(4, config.getDatabasePoolMinIdle());
        }

        @Test
        @DisplayName("password_min_length above password_max_length is reconciled")
        void reconcilesPasswordLengths() throws IOException {
            writeConfig("""
                    [auth]
                    password_min_length = 20
                    password_max_length = 8
                    """);

            VouchConfigManager config = init();

            assertTrue(config.getPasswordMinLength() <= config.getPasswordMaxLength(),
                    "nobody could ever register with min > max");
            assertEquals(20, config.getPasswordMinLength(), "the admin's minimum must be honoured");
        }

        @Test
        @DisplayName("auth.login_timeout = 0 is clamped to a workable timeout")
        void clampsLoginTimeout() throws IOException {
            writeConfig("""
                    [auth]
                    login_timeout = 0
                    """);

            assertTrue(init().getLoginTimeout() >= 5);
        }

        @Test
        @DisplayName("a negative session.duration is clamped")
        void clampsNegativeSessionDuration() throws IOException {
            writeConfig("""
                    [session]
                    duration = -3600
                    """);

            assertTrue(init().getSessionDuration() > 0);
        }

        @Test
        @DisplayName("totp.time_step = 0 is clamped (a zero step divides by zero)")
        void clampsTotpTimeStep() throws IOException {
            writeConfig("""
                    [totp]
                    time_step = 0
                    """);

            assertTrue(init().getTotpTimeStep() >= 1);
        }

        @Test
        @DisplayName("ui.effects.blindness_level above 255 is clamped to 255")
        void clampsBlindnessLevel() throws IOException {
            writeConfig("""
                    [ui.effects]
                    blindness_level = 300
                    """);

            assertEquals(255, init().getBlindnessLevel());
        }

        @Test
        @DisplayName("out-of-range sound volume and pitch are clamped")
        void clampsSoundVolumeAndPitch() throws IOException {
            writeConfig("""
                    [ui.sounds]
                    volume = -1.0
                    pitch = 99.0
                    """);

            VouchConfigManager config = init();
            assertTrue(config.getSoundVolume() >= 0.0f);
            assertTrue(config.getSoundPitch() <= 2.0f);
        }

        @Test
        @DisplayName("clamping does not rewrite the user's file")
        void clampingDoesNotRewriteValues() throws IOException {
            writeConfig("""
                    [ui.effects]
                    blindness_level = 300
                    """);

            init();

            assertTrue(readConfig().contains("300"),
                    "clamping is an in-memory correction; the file keeps what the admin wrote");
        }

        @Test
        @DisplayName("an unsupported database.type aborts instead of silently using another database")
        void unsupportedDatabaseTypeIsFatal() throws IOException {
            writeConfig("""
                    [database]
                    type = "sqlite"
                    """);

            VouchConfigManager.ConfigException error =
                    assertThrows(VouchConfigManager.ConfigException.class, VouchConfigManagerTest.this::init);
            assertTrue(error.getMessage().contains("database.type"), error.getMessage());
            assertTrue(error.getMessage().contains("h2"),
                    "the admin needs to be told what to switch to: " + error.getMessage());
            assertEquals("[database]\ntype = \"sqlite\"\n", readConfig(),
                    "the file must survive the refusal");
        }

        @Test
        @DisplayName("an unrecognised auth.mode aborts instead of silently downgrading security")
        void unknownAuthModeIsFatal() throws IOException {
            writeConfig("""
                    [auth]
                    mode = "password_only_2fa"
                    """);

            assertThrows(VouchConfigManager.ConfigException.class, VouchConfigManagerTest.this::init);
        }

        @Test
        @DisplayName("a cosmetic enum falls back to the default with a warning")
        void unknownBossBarColorFallsBack() throws IOException {
            writeConfig("""
                    [ui.bossbar]
                    color = "CHARTREUSE"
                    """);

            assertEquals("YELLOW", init().getBossBarColor());
        }
    }

    // ------------------------------------------------------------------
    // auth.allowed_commands
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("auth.allowed_commands")
    class AllowedCommands {

        @Test
        @DisplayName("defaults to the login/register command roots")
        void hasSensibleDefault() {
            VouchConfigManager config = init();
            assertEquals(List.of("login", "l", "register", "reg", "2fa", "vouch", "auth"), config.getAllowedCommands());
            assertTrue(config.isCommandAllowedPreAuth("login"));
            assertTrue(config.isCommandAllowedPreAuth("/register"));
            assertFalse(config.isCommandAllowedPreAuth("op"));
        }

        @Test
        @DisplayName("is read from the file and normalised")
        void readsFromFile() throws IOException {
            writeConfig("""
                    [auth]
                    allowed_commands = ["Login", "/reg", "  2fa  ", ""]
                    """);

            VouchConfigManager config = init();

            assertEquals(List.of("login", "reg", "2fa"), config.getAllowedCommands());
            assertTrue(config.isCommandAllowedPreAuth("2FA"));
            assertFalse(config.isCommandAllowedPreAuth("register"));
        }

        @Test
        @DisplayName("an empty list falls back to the defaults so players can still log in")
        void emptyListFallsBackToDefaults() throws IOException {
            writeConfig("""
                    [auth]
                    allowed_commands = []
                    """);

            assertEquals(List.of("login", "l", "register", "reg", "2fa", "vouch", "auth"), init().getAllowedCommands());
        }

        @Test
        @DisplayName("a non-string entry is a fatal type error")
        void nonStringEntryIsFatal() throws IOException {
            writeConfig("""
                    [auth]
                    allowed_commands = ["login", 7]
                    """);

            assertThrows(VouchConfigManager.ConfigException.class, VouchConfigManagerTest.this::init);
        }

        @Test
        @DisplayName("is added to an existing config file on upgrade, with its comment")
        void isAddedToExistingConfig() throws IOException {
            writeConfig("""
                    [auth]
                    mode = "password_only"
                    """);

            init();

            String after = readConfig();
            assertTrue(after.contains("allowed_commands"), "new key not added on upgrade:\n" + after);
            assertTrue(after.contains("\"login\""), "default value not written:\n" + after);
        }
    }

    // ------------------------------------------------------------------
    // Documentation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every shipped setting reaches the generated file")
    void everySettingIsWritten() throws IOException {
        VouchConfigManager config = init();
        String written = readConfig();

        // Guards against a key being dropped when this class is merged across branches.
        for (String key : List.of(
                "mod_name", "prefix", "type", "host", "port", "name", "user", "password",
                "max_size", "min_idle", "mode", "login_timeout", "max_attempts",
                "lockout_duration", "password_min_length", "password_max_length",
                "allowed_commands", "premium_auto_login", "premium_auto_login_require_2fa",
                "premium_offline_by_default", "premium_fetch_skins", "persistence", "duration",
                "require_2fa_on_restore", "cleanup_interval", "require_for_ops", "issuer",
                "window_size", "time_step", "memory_cost", "iterations", "parallelism",
                "language", "fade_in", "stay", "fade_out", "errors_enabled", "color", "style",
                "blindness_level", "slowness_level", "hide_from_others", "hide_from_tab_list",
                "freeze_position", "freeze_camera", "login_success", "register_success",
                "wrong_password", "auth_timeout", "rate_limited", "volume", "pitch",
                "primary", "success", "error", "info", "muted", "show_processing_message",
                "clear_chat_on_join", "welcome_message_padding")) {
            assertTrue(written.contains(key), "missing '" + key + "' in:\n" + written);
        }

        assertTrue(config.isPremiumOfflineByDefault());
        assertTrue(config.isPremiumFetchSkins());

        // bind_to_ip and bind_to_uuid were removed deliberately: the first
        // authenticated anyone who knew a username when set to false, the
        // second was documented but read by nothing. Assert they stay gone, so
        // a future merge cannot quietly bring them back.
        assertFalse(written.contains("bind_to_ip"), "bind_to_ip was removed:\n" + written);
        assertFalse(written.contains("bind_to_uuid"), "bind_to_uuid was removed:\n" + written);
    }

    @Test
    @DisplayName("the generated database.type comment no longer advertises sqlite")
    void databaseTypeCommentDropsSqlite() throws IOException {
        init();

        String written = readConfig();
        int typeLine = written.indexOf("type =");
        assertTrue(typeLine > 0);
        String header = written.substring(0, typeLine);
        assertFalse(header.toLowerCase().contains("sqlite"),
                "sqlite is not bundled; it must not be offered in the comment:\n" + written);
    }

    // ------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reload picks up edits made to the file")
    void reloadPicksUpEdits() throws IOException {
        VouchConfigManager config = init();
        assertEquals(60, config.getLoginTimeout());

        String current = readConfig();
        Files.writeString(configFile(), current.replace("login_timeout = 60", "login_timeout = 90"),
                StandardCharsets.UTF_8);

        config.reload();

        assertEquals(90, config.getLoginTimeout());
    }

    @Test
    @DisplayName("a failed reload keeps the previously loaded configuration intact")
    void failedReloadKeepsPreviousValues() throws IOException {
        VouchConfigManager config = init();
        String current = readConfig();
        Files.writeString(configFile(), current.replace("login_timeout = 60", "login_timeout = 90"),
                StandardCharsets.UTF_8);
        config.reload();
        assertEquals(90, config.getLoginTimeout());

        Files.writeString(configFile(), "[[[ broken\n", StandardCharsets.UTF_8);

        assertThrows(VouchConfigManager.ConfigException.class, config::reload);
        assertEquals(90, config.getLoginTimeout(),
                "a bad reload must not silently reset the live configuration to defaults");
    }

    @Test
    @DisplayName("repeated reloads do not accumulate background writer threads")
    void repeatedReloadsDoNotLeakThreads() {
        VouchConfigManager config = init();
        config.reload();
        int before = Thread.activeCount();

        for (int i = 0; i < 50; i++) {
            config.reload();
        }

        assertTrue(Thread.activeCount() <= before,
                "reload leaked threads: " + before + " -> " + Thread.activeCount());
    }
}
