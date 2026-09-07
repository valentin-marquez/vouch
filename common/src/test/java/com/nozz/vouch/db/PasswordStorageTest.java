package com.nozz.vouch.db;

import com.nozz.vouch.crypto.Argon2Hasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.nozz.vouch.db.DatabaseTestSupport.freshDatabaseName;
import static com.nozz.vouch.db.DatabaseTestSupport.openPool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storing a password hash after the account already exists, and the lookups the
 * command layer needs to do its job without querying per keystroke.
 *
 * <p>{@code updatePassword} is what makes {@link Argon2Hasher#needsRehash(String)}
 * useful: a stored hash can only be upgraded at the one moment the plain text
 * exists, which is immediately after a successful verification.
 */
class PasswordStorageTest {

    private static final String PASSWORD = "correct horse battery staple";

    private DatabaseManager db;

    @BeforeEach
    void setUp() throws SQLException {
        openPool(freshDatabaseName("vouch_pw"));
        db = DatabaseManager.getInstance();
        db.initializeSchema();
    }

    @AfterEach
    void tearDown() {
        DatabaseTestSupport.tearDown();
    }

    private String storedHashOf(UUID uuid) throws Exception {
        return db.getPasswordHash(uuid).get(5, TimeUnit.SECONDS).orElseThrow();
    }

    @Nested
    class UpdatingAPassword {

        @Test
        void replacesTheStoredHashForAnExistingAccount() throws Exception {
            UUID uuid = UUID.randomUUID();
            db.registerPlayer(uuid, "Notch", Argon2Hasher.hash("old")).get(5, TimeUnit.SECONDS);

            String replacement = Argon2Hasher.hash("new");
            assertTrue(db.updatePassword(uuid, replacement).get(5, TimeUnit.SECONDS));

            assertEquals(replacement, storedHashOf(uuid));
            assertTrue(Argon2Hasher.verify("new", storedHashOf(uuid)));
            assertFalse(Argon2Hasher.verify("old", storedHashOf(uuid)));
        }

        @Test
        void reportsFailureForAnAccountThatDoesNotExist() throws Exception {
            assertFalse(db.updatePassword(UUID.randomUUID(), Argon2Hasher.hash("new"))
                    .get(5, TimeUnit.SECONDS));
        }

        @Test
        void refusesToStoreAnEmptyHash() throws Exception {
            UUID uuid = UUID.randomUUID();
            db.registerPlayer(uuid, "Notch", Argon2Hasher.hash("old")).get(5, TimeUnit.SECONDS);
            String before = storedHashOf(uuid);

            assertFalse(db.updatePassword(uuid, null).get(5, TimeUnit.SECONDS));
            assertFalse(db.updatePassword(uuid, "   ").get(5, TimeUnit.SECONDS));

            assertEquals(before, storedHashOf(uuid), "a rejected update must not blank the hash");
        }
    }

    @Nested
    class UpgradingALegacyHashOnLogin {

        /**
         * The pre-PHC format: {@code base64(salt)$base64(digest)}, with the cost
         * parameters implied by whatever the configuration happened to be.
         */
        private String legacyHashOf(String password) {
            String phc = Argon2Hasher.hash(password);
            String[] fields = phc.split("\\$");
            // $argon2id$v=19$m=..,t=..,p=..$salt$digest
            return fields[4] + "$" + fields[5];
        }

        @Test
        void aLegacyHashStillVerifiesButIsFlaggedForRehashing() {
            String legacy = legacyHashOf(PASSWORD);

            assertTrue(Argon2Hasher.verify(PASSWORD, legacy));
            assertTrue(Argon2Hasher.needsRehash(legacy));
        }

        @Test
        void aFreshHashIsNotFlagged() {
            assertFalse(Argon2Hasher.needsRehash(Argon2Hasher.hash(PASSWORD)));
        }

        @Test
        void rehashingOnLoginLeavesAPhcHashThatStillAcceptsTheSamePassword() throws Exception {
            UUID uuid = UUID.randomUUID();
            String legacy = legacyHashOf(PASSWORD);
            db.registerPlayer(uuid, "Legacy", legacy).get(5, TimeUnit.SECONDS);

            // What /login now does: verify, notice the format is stale, re-hash, persist.
            String stored = storedHashOf(uuid);
            assertTrue(Argon2Hasher.verify(PASSWORD, stored));
            assertTrue(Argon2Hasher.needsRehash(stored));
            db.updatePassword(uuid, Argon2Hasher.hash(PASSWORD)).get(5, TimeUnit.SECONDS);

            String upgraded = storedHashOf(uuid);
            assertNotEquals(legacy, upgraded);
            assertTrue(upgraded.startsWith("$argon2id$"), "the upgrade must land in PHC format");
            assertFalse(Argon2Hasher.needsRehash(upgraded), "and must not ask to be upgraded again");
            assertTrue(Argon2Hasher.verify(PASSWORD, upgraded), "the player's password is unchanged");
        }
    }

    @Nested
    class ListingRegisteredNames {

        @Test
        void returnsEveryRegisteredUsername() throws Exception {
            db.registerPlayer(UUID.randomUUID(), "Alex", "hash").get(5, TimeUnit.SECONDS);
            db.registerPlayer(UUID.randomUUID(), "Notch", "hash").get(5, TimeUnit.SECONDS);
            db.registerPlayerWith2FA(UUID.randomUUID(), "Steve", "JBSWY3DPEHPK3PXP").get(5, TimeUnit.SECONDS);

            List<String> names = db.listRegisteredUsernames().get(5, TimeUnit.SECONDS);

            assertEquals(List.of("Alex", "Notch", "Steve"), names.stream().sorted().toList());
        }

        @Test
        void isEmptyOnAFreshServerRatherThanFailing() throws Exception {
            assertEquals(List.of(), db.listRegisteredUsernames().get(5, TimeUnit.SECONDS));
        }

        @Test
        void dropsAnAccountOnceItIsUnregistered() throws Exception {
            UUID uuid = UUID.randomUUID();
            db.registerPlayer(uuid, "Notch", "hash").get(5, TimeUnit.SECONDS);
            db.unregisterPlayer(uuid).get(5, TimeUnit.SECONDS);

            assertEquals(List.of(), db.listRegisteredUsernames().get(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    class ReadingOneAccount {

        @Test
        void reportsWhatAnAdminNeedsToSee() throws Exception {
            UUID uuid = UUID.randomUUID();
            db.registerPlayerWith2FA(uuid, "Notch", "JBSWY3DPEHPK3PXP").get(5, TimeUnit.SECONDS);
            db.updateLastLogin(uuid, "203.0.113.7").get(5, TimeUnit.SECONDS);

            PlayerRecord record = db.getPlayerRecord(uuid).get(5, TimeUnit.SECONDS).orElseThrow();

            assertEquals(uuid, record.uuid());
            assertEquals("Notch", record.username());
            assertTrue(record.twoFactorEnabled());
            assertEquals(Optional.of("203.0.113.7"), record.lastIp());
            assertTrue(record.lastLogin().isPresent());
        }

        @Test
        void isEmptyForAnAccountThatDoesNotExist() throws Exception {
            assertEquals(Optional.empty(), db.getPlayerRecord(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
        }

        @Test
        void findsAnAccountByNameSoAdminCommandsWorkForOfflinePlayers() throws Exception {
            UUID uuid = UUID.randomUUID();
            db.registerPlayer(uuid, "Notch", "hash").get(5, TimeUnit.SECONDS);

            PlayerRecord found = db.findPlayerByName("notch").get(5, TimeUnit.SECONDS).orElseThrow();

            assertEquals(uuid, found.uuid());
            assertEquals("Notch", found.username(), "the stored spelling comes back, not what was typed");
        }

        @Test
        void findingByNameIsEmptyForAnUnknownName() throws Exception {
            assertEquals(Optional.empty(), db.findPlayerByName("Nobody").get(5, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), db.findPlayerByName(null).get(5, TimeUnit.SECONDS));
        }
    }
}
