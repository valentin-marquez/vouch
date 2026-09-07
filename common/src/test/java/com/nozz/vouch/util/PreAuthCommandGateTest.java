package com.nozz.vouch.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pre-auth command allowlist.
 *
 * The mixin that gates {@code CommandExecutionC2SPacket} cannot be unit tested,
 * so every decision it makes lives here as a pure function over strings. The
 * cases below are the ones an attacker actually tries: namespaced aliases,
 * casing tricks, whitespace padding and slash juggling.
 */
class PreAuthCommandGateTest {

    private static final Set<String> ALLOWED = PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS;

    @Nested
    class RootLiteralExtraction {

        @Test
        void takesTheFirstWordAsTheRootLiteral() {
            assertEquals("login", PreAuthCommandGate.rootLiteral("login secret"));
        }

        @Test
        void ignoresArgumentsEntirely() {
            assertEquals("login", PreAuthCommandGate.rootLiteral("login my password has spaces"));
        }

        @Test
        void acceptsACommandWithNoArguments() {
            assertEquals("register", PreAuthCommandGate.rootLiteral("register"));
        }

        @Test
        void stripsASingleLeadingSlash() {
            assertEquals("login", PreAuthCommandGate.rootLiteral("/login secret"));
        }

        @Test
        void stripsLeadingWhitespaceBeforeTheSlash() {
            assertEquals("login", PreAuthCommandGate.rootLiteral("   /login secret"));
        }

        @Test
        void treatsATabAsAnArgumentSeparator() {
            assertEquals("login", PreAuthCommandGate.rootLiteral("login\tsecret"));
        }

        @Test
        void lowercasesTheRootLiteral() {
            assertEquals("login", PreAuthCommandGate.rootLiteral("/LoGiN secret"));
        }

        @Test
        void keepsTheNamespaceAttachedToTheRootLiteral() {
            assertEquals("minecraft:me", PreAuthCommandGate.rootLiteral("minecraft:me waves"));
        }

        @Test
        void returnsEmptyForNull() {
            assertEquals("", PreAuthCommandGate.rootLiteral(null));
        }

        @Test
        void returnsEmptyForBlankInput() {
            assertEquals("", PreAuthCommandGate.rootLiteral("     "));
        }

        @Test
        void returnsEmptyForABareSlash() {
            assertEquals("", PreAuthCommandGate.rootLiteral("/"));
        }

        @Test
        void returnsEmptyWhenTheSlashIsFollowedByWhitespace() {
            assertEquals("", PreAuthCommandGate.rootLiteral("/ login secret"));
        }

        @Test
        void stripsOnlyOneSlashSoDoubleSlashesStayDistinct() {
            assertEquals("/login", PreAuthCommandGate.rootLiteral("//login secret"));
        }
    }

    @Nested
    class AllowlistMatching {

        @Test
        void allowsTheDefaultLoginCommand() {
            assertTrue(PreAuthCommandGate.isAllowed("login hunter2", ALLOWED));
        }

        @Test
        void allowsTheDefaultLoginAlias() {
            assertTrue(PreAuthCommandGate.isAllowed("l hunter2", ALLOWED));
        }

        @Test
        void allowsTheDefaultRegisterCommand() {
            assertTrue(PreAuthCommandGate.isAllowed("register hunter2 hunter2", ALLOWED));
        }

        @Test
        void allowsTheDefaultRegisterAlias() {
            assertTrue(PreAuthCommandGate.isAllowed("reg hunter2 hunter2", ALLOWED));
        }

        @Test
        void allowsALeadingSlashForm() {
            assertTrue(PreAuthCommandGate.isAllowed("/login hunter2", ALLOWED));
        }

        @Test
        void allowsMixedCaseOfAnAllowedCommand() {
            assertTrue(PreAuthCommandGate.isAllowed("/Login hunter2", ALLOWED));
        }

        @Test
        void blocksVanillaMe() {
            assertFalse(PreAuthCommandGate.isAllowed("me is not logged in", ALLOWED));
        }

        @Test
        void blocksVanillaMsg() {
            assertFalse(PreAuthCommandGate.isAllowed("msg Notch hello", ALLOWED));
        }

        @Test
        void blocksVanillaTell() {
            assertFalse(PreAuthCommandGate.isAllowed("tell Notch hello", ALLOWED));
        }

        @Test
        void blocksVanillaTeamMsg() {
            assertFalse(PreAuthCommandGate.isAllowed("teammsg hello", ALLOWED));
        }

        @Test
        void blocksOp() {
            assertFalse(PreAuthCommandGate.isAllowed("op Intruder", ALLOWED));
        }

        @Test
        void blocksTypicalModpackCommands() {
            assertFalse(PreAuthCommandGate.isAllowed("tpa Notch", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("home", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("back", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("pay Notch 1000", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("kit starter", ALLOWED));
        }

        @Test
        void blocksTheNamespacedAliasOfABlockedCommand() {
            assertFalse(PreAuthCommandGate.isAllowed("minecraft:me is not logged in", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("/minecraft:msg Notch hello", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("MINECRAFT:ME waves", ALLOWED));
        }

        @Test
        void blocksAForeignNamespaceThatBorrowsAnAllowedName() {
            assertFalse(PreAuthCommandGate.isAllowed("evilmod:login hunter2", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("/somepack:register a b", ALLOWED));
        }

        @Test
        void blocksAnAllowedNameUsedAsAnArgument() {
            assertFalse(PreAuthCommandGate.isAllowed("execute run login hunter2", ALLOWED));
        }

        @Test
        void blocksCommandsThatMerelyStartWithAnAllowedName() {
            assertFalse(PreAuthCommandGate.isAllowed("loginx hunter2", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("registerall", ALLOWED));
        }

        @Test
        void blocksNullEmptyAndMalformedInput() {
            assertFalse(PreAuthCommandGate.isAllowed(null, ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("    ", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("/", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("//login hunter2", ALLOWED));
            assertFalse(PreAuthCommandGate.isAllowed("/ login hunter2", ALLOWED));
        }

        @Test
        void blocksEverythingWhenTheAllowlistIsEmpty() {
            assertFalse(PreAuthCommandGate.isAllowed("login hunter2", Set.of()));
        }

        @Test
        void blocksEverythingWhenTheAllowlistIsNull() {
            assertFalse(PreAuthCommandGate.isAllowed("login hunter2", null));
        }
    }

    @Nested
    class AllowlistNormalisation {

        @Test
        void normalisesEntriesWrittenWithSlashesPaddingAndCasing() {
            Set<String> normalised = PreAuthCommandGate.normalizeAllowlist(
                    List.of("/Login", "  REG  ", "register"));

            assertEquals(Set.of("login", "reg", "register"), normalised);
        }

        @Test
        void dropsBlankAndUnusableEntries() {
            Set<String> normalised = PreAuthCommandGate.normalizeAllowlist(
                    Arrays.asList("login", "", "   ", "/", null));

            assertEquals(Set.of("login"), normalised);
        }

        @Test
        void appliesToMatchingSoAConfiguredSlashFormStillWorks() {
            assertTrue(PreAuthCommandGate.isAllowed("2fa 123456",
                    PreAuthCommandGate.normalizeAllowlist(List.of("/2FA"))));
        }

        @Test
        void returnsAnEmptySetForNull() {
            assertEquals(Set.of(), PreAuthCommandGate.normalizeAllowlist(null));
        }
    }

    @Nested
    class Defaults {

        @Test
        void containsExactlyTheFourBootstrapCommands() {
            assertEquals(Set.of("login", "l", "register", "reg", "2fa", "vouch", "auth"),
                    PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS);
        }

        @Test
        void isAlreadyNormalised() {
            assertEquals(PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS,
                    PreAuthCommandGate.normalizeAllowlist(PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS));
        }
    }

    @Nested
    class DecisionSeam {

        @Test
        void allowsAndReportsTheRootLiteral() {
            PreAuthCommandGate.Decision decision = PreAuthCommandGate.evaluate("/login hunter2");

            assertTrue(decision.allowed());
            assertEquals("login", decision.rootLiteral());
            assertEquals(Optional.empty(), decision.suggestion());
        }

        @Test
        void blocksAndReportsTheRootLiteral() {
            PreAuthCommandGate.Decision decision = PreAuthCommandGate.evaluate("minecraft:me waves");

            assertFalse(decision.allowed());
            assertEquals("minecraft:me", decision.rootLiteral());
        }

        @Test
        void asksTheCorrectorOnlyForBlockedCommands() {
            PreAuthCommandGate.Corrector corrector = (root, allowed) -> Optional.of("login");
            try {
                PreAuthCommandGate.setCorrector(corrector);

                assertEquals(Optional.of("login"),
                        PreAuthCommandGate.evaluate("logni hunter2").suggestion());
                assertEquals(Optional.empty(),
                        PreAuthCommandGate.evaluate("login hunter2").suggestion());
            } finally {
                PreAuthCommandGate.setCorrector(null);
            }
        }

        @Test
        void aCorrectorCanNeverTurnABlockIntoAnAllow() {
            try {
                PreAuthCommandGate.setCorrector((root, allowed) -> Optional.of("login"));

                assertFalse(PreAuthCommandGate.evaluate("op Intruder").allowed());
            } finally {
                PreAuthCommandGate.setCorrector(null);
            }
        }

        @Test
        void resetsToTheNoOpCorrectorWhenGivenNull() {
            PreAuthCommandGate.setCorrector((root, allowed) -> Optional.of("login"));
            PreAuthCommandGate.setCorrector(null);

            assertEquals(Optional.empty(), PreAuthCommandGate.evaluate("logni hunter2").suggestion());
        }

        @Test
        void aThrowingCorrectorCannotBreakTheGate() {
            try {
                PreAuthCommandGate.setCorrector((root, allowed) -> {
                    throw new IllegalStateException("boom");
                });

                PreAuthCommandGate.Decision decision = PreAuthCommandGate.evaluate("logni hunter2");
                assertFalse(decision.allowed());
                assertEquals(Optional.empty(), decision.suggestion());
            } finally {
                PreAuthCommandGate.setCorrector(null);
            }
        }

        @Test
        void defaultDecisionUsesTheDefaultAllowlist() {
            assertSame(PreAuthCommandGate.DEFAULT_ALLOWED_COMMANDS, PreAuthCommandGate.allowedCommands());
        }
    }
}
