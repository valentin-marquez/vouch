package com.nozz.vouch.command;

import com.nozz.vouch.util.PreAuthCommandGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Did you mean /login?" for a player who is still in the jail.
 *
 * The whole point of the pre-auth gate is that an unauthenticated player cannot
 * see the command surface. A corrector that searched the full dispatcher would
 * hand it back to them one typo at a time — so the only candidates it is ever
 * given are the ones already visible, and the tests below are mostly about that
 * boundary rather than about spelling.
 */
class CommandTypoCorrectorTest {

    private static final Set<String> VISIBLE = Set.of("login", "l", "register", "reg");

    private final CommandTypoCorrector corrector = new CommandTypoCorrector();

    private Optional<String> suggest(String typed) {
        return corrector.suggest(typed, VISIBLE);
    }

    @Nested
    class WhatItCorrects {

        @Test
        void aSingleTranspositionIsCorrected() {
            assertEquals(Optional.of("login"), suggest("lgoin"));
        }

        @Test
        void aMissingLetterIsCorrected() {
            assertEquals(Optional.of("register"), suggest("regiter"));
        }

        @Test
        void anExtraLetterIsCorrected() {
            assertEquals(Optional.of("login"), suggest("logiin"));
        }

        @Test
        void aWrongLetterIsCorrected() {
            assertEquals(Optional.of("register"), suggest("regixter"));
        }

        @Test
        void theClosestCandidateWinsWhenSeveralAreClose() {
            assertEquals(Optional.of("reg"), corrector.suggest("rem", Set.of("reg", "register")));
        }
    }

    @Nested
    class WhatItRefusesToCorrect {

        @Test
        void aCommandThatIsNothingLikeAnyVisibleOneGetsNoSuggestion() {
            assertEquals(Optional.empty(), suggest("gamemode"));
            assertEquals(Optional.empty(), suggest("tp"));
            assertEquals(Optional.empty(), suggest("op"));
        }

        @Test
        void aShortLiteralIsNotStretchedIntoALongOne() {
            // Two edits away from "reg", but three characters cannot be a typo for it
            // without the suggestion being a guess.
            assertEquals(Optional.empty(), suggest("ban"));
        }

        @Test
        void aSingleCharacterTypoIsNotCorrectedToTheSingleCharacterAlias() {
            // "l" is one edit from every other letter on the keyboard; correcting to it
            // would turn any stray keypress into a login prompt.
            assertEquals(Optional.empty(), suggest("k"));
            assertEquals(Optional.empty(), suggest("p"));
        }

        @Test
        void anEmptyCandidateSetProducesNothing() {
            assertEquals(Optional.empty(), corrector.suggest("lgoin", Set.of()));
            assertEquals(Optional.empty(), corrector.suggest("lgoin", null));
        }

        @Test
        void anEmptyOrNullInputProducesNothing() {
            assertEquals(Optional.empty(), suggest(""));
            assertEquals(Optional.empty(), suggest(null));
        }

        @Test
        void anExactMatchIsNotSuggestedBackAtThePlayer() {
            // The gate only consults the corrector for blocked commands, so an exact
            // match here means the allowlist and the caller disagreed. Say nothing.
            assertEquals(Optional.empty(), suggest("login"));
        }
    }

    @Nested
    class VisibilityBoundary {

        @Test
        void aCommandOutsideTheVisibleSetIsNeverSuggestedHoweverCloseItIs() {
            // "logout" is one edit from "logput", but a jailed player cannot see it.
            assertEquals(Optional.empty(), corrector.suggest("logput", Set.of("register")));
        }

        @Test
        void onlyTheGivenCandidatesAreConsideredEvenWhenTheModHasMoreCommands() {
            assertEquals(Optional.of("register"), corrector.suggest("registr", Set.of("register")));
            assertEquals(Optional.empty(), corrector.suggest("logn", Set.of("register")));
        }
    }

    @Nested
    class InstalledInTheGate {

        @AfterEach
        void restoreDefaultCorrector() {
            PreAuthCommandGate.setCorrector(null);
        }

        @Test
        void aBlockedCommandComesBackWithASuggestionOnceInstalled() {
            PreAuthCommandGate.setCorrector(new CommandTypoCorrector());

            PreAuthCommandGate.Decision decision = PreAuthCommandGate.evaluate("/lgoin hunter2", VISIBLE);

            assertFalse(decision.allowed());
            assertEquals(Optional.of("login"), decision.suggestion());
        }

        @Test
        void theCorrectorCannotTurnABlockIntoAnAllow() {
            PreAuthCommandGate.setCorrector((root, allowed) -> Optional.of("login"));

            assertFalse(PreAuthCommandGate.evaluate("/gamemode creative", VISIBLE).allowed());
        }

        @Test
        void anAllowedCommandIsNotDecoratedWithASuggestion() {
            PreAuthCommandGate.setCorrector(new CommandTypoCorrector());

            PreAuthCommandGate.Decision decision = PreAuthCommandGate.evaluate("/login hunter2", VISIBLE);

            assertTrue(decision.allowed());
            assertEquals(Optional.empty(), decision.suggestion());
        }
    }

    @Nested
    class Distance {

        @Test
        void identicalStringsAreZeroApart() {
            assertEquals(0, CommandTypoCorrector.distance("login", "login"));
        }

        @Test
        void eachSubstitutionInsertionAndDeletionCostsOne() {
            assertEquals(1, CommandTypoCorrector.distance("login", "logon"));
            assertEquals(1, CommandTypoCorrector.distance("login", "logins"));
            assertEquals(1, CommandTypoCorrector.distance("login", "logn"));
        }

        @Test
        void aTranspositionCostsTwoUnderPlainEditDistance() {
            assertEquals(2, CommandTypoCorrector.distance("login", "lgoin"));
        }

        @Test
        void distanceToAnEmptyStringIsItsLength() {
            assertEquals(5, CommandTypoCorrector.distance("login", ""));
            assertEquals(5, CommandTypoCorrector.distance("", "login"));
        }
    }
}
