package com.nozz.vouch.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory set of registered usernames that feeds tab-completion.
 *
 * It exists because the alternative is a database round trip per keystroke.
 * Everything here is about it staying truthful without one: the cache is only
 * ever written from a place that has just been told the answer by the database,
 * and it answers case-insensitively because Minecraft usernames do.
 */
class RegisteredNamesTest {

    private RegisteredNames names;

    @BeforeEach
    void setUp() {
        names = new RegisteredNames();
    }

    @Test
    void anEmptyCacheSuggestsNothing() {
        assertEquals(List.of(), names.names());
        assertFalse(names.contains("Notch"));
    }

    @Test
    void seedingReplacesEverythingThatWasThereBefore() {
        names.add("Stale");
        names.seed(List.of("Alex", "Steve"));

        assertEquals(List.of("Alex", "Steve"), names.names());
        assertFalse(names.contains("Stale"));
    }

    @Test
    void namesComeBackSortedSoCompletionIsStable() {
        names.seed(List.of("zoe", "Alex", "mike"));

        assertEquals(List.of("Alex", "mike", "zoe"), names.names());
    }

    @Test
    void membershipIgnoresCaseBecauseMinecraftLoginsDo() {
        names.add("Notch");

        assertTrue(names.contains("notch"));
        assertTrue(names.contains("NOTCH"));
        assertTrue(names.contains("Notch"));
    }

    @Test
    void reAddingUnderADifferentCaseKeepsOneEntryAndTheNewestSpelling() {
        names.add("notch");
        names.add("Notch");

        assertEquals(List.of("Notch"), names.names());
    }

    @Test
    void removingIsAlsoCaseInsensitive() {
        names.add("Notch");
        names.remove("nOtCh");

        assertFalse(names.contains("Notch"));
        assertEquals(List.of(), names.names());
    }

    @Test
    void blankAndNullNamesAreRefusedRatherThanStored() {
        names.add(null);
        names.add("");
        names.add("   ");
        names.remove(null);

        assertEquals(List.of(), names.names());
        assertFalse(names.contains(null));
        assertFalse(names.contains(""));
    }

    @Test
    void namesAreTrimmedSoAStraySpaceCannotCreateADuplicate() {
        names.add("  Notch  ");

        assertEquals(List.of("Notch"), names.names());
        assertTrue(names.contains("Notch"));
    }

    @Test
    void setPresentIsTheOneCallSiteThatCarriesADatabaseAnswer() {
        names.setPresent("Notch", true);
        assertTrue(names.contains("Notch"));

        names.setPresent("Notch", false);
        assertFalse(names.contains("Notch"));
    }

    @Test
    void theReturnedListCannotBeUsedToEditTheCache() {
        names.seed(List.of("Alex"));

        List<String> snapshot = names.names();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("Steve"));
    }

    @Test
    void seedingWithNullClearsRatherThanThrowing() {
        names.add("Alex");
        names.seed(null);

        assertEquals(List.of(), names.names());
    }

    @Test
    void theSharedInstanceIsTheOneEveryCommandSees() {
        assertTrue(RegisteredNames.getInstance() == RegisteredNames.getInstance());
    }

    @Test
    void seedingIgnoresUnusableEntriesInsteadOfFailingTheWholeSeed() {
        names.seed(java.util.Arrays.asList("Alex", null, "  ", "Steve"));

        assertEquals(Set.of("Alex", "Steve"), Set.copyOf(names.names()));
    }
}
