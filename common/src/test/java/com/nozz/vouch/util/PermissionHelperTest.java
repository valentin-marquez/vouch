package com.nozz.vouch.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two structural guarantees of {@link PermissionHelper}:
 *
 * <ol>
 *   <li>{@code vouch.bypass.auth} can only ever be satisfied by an explicit
 *       permission grant, and is refused outright on offline-mode servers for
 *       players whose identity was never cryptographically proven.</li>
 *   <li>Every declared permission node is reachable from a single iterable
 *       table, so a platform registrar cannot silently skip one.</li>
 * </ol>
 */
class PermissionHelperTest {

    @Nested
    class BypassResolution {

        @Test
        void refusesWithoutAnExplicitGrantEvenForAnOnlineModeOperator() {
            assertFalse(PermissionHelper.resolveBypassAuth(false, true, true, true));
        }

        @Test
        void refusesWithoutAnExplicitGrantOnAnOfflineServer() {
            assertFalse(PermissionHelper.resolveBypassAuth(false, false, true, true));
        }

        @Test
        void grantsOnAnOnlineModeServerWhenExplicitlyGranted() {
            assertTrue(PermissionHelper.resolveBypassAuth(true, true, true, false));
        }

        @Test
        void refusesOnAnOfflineServerWhenThePlayerWasNotPremiumVerified() {
            assertFalse(PermissionHelper.resolveBypassAuth(true, false, true, false));
        }

        @Test
        void grantsOnAnOfflineServerOnlyWhenThePlayerWasPremiumVerified() {
            assertTrue(PermissionHelper.resolveBypassAuth(true, false, true, true));
        }

        @Test
        void refusesForNonPlayerSources() {
            assertFalse(PermissionHelper.resolveBypassAuth(true, true, false, true));
            assertFalse(PermissionHelper.resolveBypassAuth(true, false, false, true));
        }
    }

    @Nested
    class ExplicitGrantOnlyDefault {

        @Test
        void isUnreachableByAnyVanillaOpLevel() {
            for (int opLevel = 0; opLevel <= 4; opLevel++) {
                assertFalse(opLevel >= PermissionHelper.Defaults.EXPLICIT_GRANT_ONLY,
                        "OP level " + opLevel + " must not satisfy an explicit-grant-only node");
            }
        }

        @Test
        void isTheDefaultAttachedToTheBypassNode() {
            PermissionHelper.Node bypass = PermissionHelper.Nodes.byId(PermissionHelper.Nodes.BYPASS_AUTH)
                    .orElseThrow();

            assertEquals(PermissionHelper.Defaults.EXPLICIT_GRANT_ONLY, bypass.defaultOpLevel());
            assertTrue(bypass.explicitGrantOnly());
            assertFalse(bypass.grantedToEveryoneByDefault());
        }

        @Test
        void adminNodesStillFallBackToOperatorLevel() {
            PermissionHelper.Node reload = PermissionHelper.Nodes.byId(PermissionHelper.Nodes.ADMIN_RELOAD)
                    .orElseThrow();

            assertEquals(PermissionHelper.Defaults.ADMIN_COMMAND, reload.defaultOpLevel());
            assertFalse(reload.explicitGrantOnly());
        }

        @Test
        void userNodesAreAvailableToEveryoneByDefault() {
            PermissionHelper.Node login = PermissionHelper.Nodes.byId(PermissionHelper.Nodes.LOGIN)
                    .orElseThrow();

            assertEquals(PermissionHelper.Defaults.USER_COMMAND, login.defaultOpLevel());
            assertTrue(login.grantedToEveryoneByDefault());
        }
    }

    @Nested
    class PremiumIdentity {

        /** Exactly how vanilla derives an offline-mode UUID from a username. */
        private static UUID offlineUuid(String username) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void treatsTheOfflineUuidOfTheOwnUsernameAsUnverified() {
            assertFalse(PermissionHelper.hasOnlineUuid(offlineUuid("Notch"), "Notch"));
        }

        @Test
        void treatsAnOnlineUuidAsVerified() {
            // Notch's real Mojang UUID, which is not the offline UUID for "Notch".
            UUID online = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

            assertTrue(PermissionHelper.hasOnlineUuid(online, "Notch"));
        }

        @Test
        void doesNotConfuseTheOfflineUuidOfADifferentName() {
            assertTrue(PermissionHelper.hasOnlineUuid(offlineUuid("Someone"), "Notch"));
        }

        @Test
        void refusesNullAndEmptyInput() {
            assertFalse(PermissionHelper.hasOnlineUuid(null, "Notch"));
            assertFalse(PermissionHelper.hasOnlineUuid(offlineUuid("Notch"), null));
            assertFalse(PermissionHelper.hasOnlineUuid(offlineUuid("Notch"), ""));
        }
    }

    @Nested
    class NodeTable {

        /**
         * The whole point of the table: a registrar that walks {@code Nodes.all()}
         * cannot forget a node, because forgetting one here fails this test.
         */
        @Test
        void everyDeclaredNodeConstantIsPresentInTheTable() throws IllegalAccessException {
            Set<String> declared = new LinkedHashSet<>();
            for (Field field : PermissionHelper.Nodes.class.getDeclaredFields()) {
                if (field.getType() == String.class
                        && Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())) {
                    declared.add((String) field.get(null));
                }
            }

            assertFalse(declared.isEmpty(), "no node constants found — the reflection filter is wrong");

            List<String> tabled = PermissionHelper.Nodes.all().stream().map(PermissionHelper.Node::id).toList();
            List<String> missing = new ArrayList<>(declared);
            missing.removeAll(tabled);

            assertEquals(List.of(), missing, "node constants missing from Nodes.all()");
        }

        @Test
        void tableEntriesAreAllDeclaredConstants() throws IllegalAccessException {
            Set<String> declared = new LinkedHashSet<>();
            for (Field field : PermissionHelper.Nodes.class.getDeclaredFields()) {
                if (field.getType() == String.class && Modifier.isPublic(field.getModifiers())) {
                    declared.add((String) field.get(null));
                }
            }

            for (PermissionHelper.Node node : PermissionHelper.Nodes.all()) {
                assertTrue(declared.contains(node.id()), node.id() + " has no matching constant");
            }
        }

        @Test
        void nodeIdsAreUnique() {
            List<String> ids = PermissionHelper.Nodes.all().stream().map(PermissionHelper.Node::id).toList();

            assertEquals(ids.size(), Set.copyOf(ids).size(), "duplicate node ids: " + ids);
        }

        @Test
        void everyNodeIdIsNamespacedUnderVouch() {
            for (PermissionHelper.Node node : PermissionHelper.Nodes.all()) {
                assertTrue(node.id().startsWith("vouch."), node.id());
            }
        }

        @Test
        void coversTheTwoNodesTheNeoForgeRegistrarUsedToSkip() {
            assertNotNull(PermissionHelper.Nodes.byId("vouch.admin.premium-override").orElse(null));
            assertNotNull(PermissionHelper.Nodes.byId("vouch.command.mark-as-online").orElse(null));
        }

        @Test
        void theTableIsImmutable() {
            List<PermissionHelper.Node> all = PermissionHelper.Nodes.all();

            assertThrows(UnsupportedOperationException.class,
                    () -> all.add(new PermissionHelper.Node("vouch.injected", 0)));
        }

        @Test
        void byIdReturnsEmptyForUnknownNodes() {
            assertTrue(PermissionHelper.Nodes.byId("vouch.does.not.exist").isEmpty());
            assertTrue(PermissionHelper.Nodes.byId(null).isEmpty());
        }
    }
}
