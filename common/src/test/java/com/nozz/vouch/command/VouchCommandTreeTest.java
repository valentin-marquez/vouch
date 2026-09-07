package com.nozz.vouch.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.nozz.vouch.auth.AuthMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static com.nozz.vouch.command.PlayerAuthState.AUTHENTICATED;
import static com.nozz.vouch.command.PlayerAuthState.AWAITING_2FA;
import static com.nozz.vouch.command.PlayerAuthState.UNAUTHENTICATED_REGISTERED;
import static com.nozz.vouch.command.PlayerAuthState.UNAUTHENTICATED_UNREGISTERED;
import static com.nozz.vouch.command.VouchCommandTree.Leaf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the command tree, asserted against a real Brigadier dispatcher.
 *
 * <p>Brigadier is a plain library, so the tree can be built and parsed here even
 * though the executors need a running server. That covers the two things that
 * actually went wrong before: {@code /vouch} and {@code /auth} were hand-typed
 * twice and had already drifted apart, and nothing checked that an alias kept
 * pointing at the command it used to run.
 *
 * <p>{@link VouchCommandTree} is generic in the source type precisely so this
 * test can drive it with a plain value carrying an auth state, instead of a
 * {@code ServerCommandSource} that cannot exist outside a server.
 */
class VouchCommandTreeTest {

    /** Everything the tree's predicates are allowed to know about a caller. */
    private record Caller(PlayerAuthState state, boolean twoFactorEnabled, boolean admin, boolean unrestricted) {
        static Caller player(PlayerAuthState state) {
            return new Caller(state, false, false, false);
        }

        static Caller playerWith2FA(PlayerAuthState state) {
            return new Caller(state, true, false, false);
        }

        static Caller asAdmin() {
            return new Caller(AUTHENTICATED, false, true, false);
        }

        /**
         * Passes every predicate, for the tests that are asking what the tree looks
         * like rather than who may see it. No single real state can reach every leaf —
         * {@code 2fa setup} and {@code 2fa disable} are mutually exclusive by design —
         * so shape and visibility have to be asked separately.
         */
        static Caller anyone() {
            return new Caller(AUTHENTICATED, true, true, true);
        }
    }

    /**
     * Hooks that mirror what the server wires in: the real {@link CommandGate} for
     * state, a permission flag for the admin branch, and one distinct
     * {@link Command} object per leaf so a parse can be traced back to it.
     */
    private static final class RecordingHooks implements VouchCommandTree.Hooks<Caller> {
        /** A command that remembers which leaf it came from, so a parse can be traced. */
        private record LeafCommand(Leaf leaf) implements Command<Caller> {
            @Override
            public int run(com.mojang.brigadier.context.CommandContext<Caller> context) {
                return 1;
            }
        }

        private final Map<Leaf, Command<Caller>> actions = new EnumMap<>(Leaf.class);
        private final RegisteredNames names = new RegisteredNames();

        @Override
        public Predicate<Caller> requirement(VouchCommand command) {
            return caller -> {
                if (caller.unrestricted()) {
                    return true;
                }
                if (isAdminCommand(command) && !caller.admin()) {
                    return false;
                }
                return CommandGate.isAvailable(command, caller.state(), caller.twoFactorEnabled());
            };
        }

        @Override
        public Command<Caller> action(Leaf leaf) {
            return actions.computeIfAbsent(leaf, LeafCommand::new);
        }

        @Override
        public SuggestionProvider<Caller> registeredPlayerNames() {
            return RegisteredNameSuggestions.from(names);
        }

        private static boolean isAdminCommand(VouchCommand command) {
            return switch (command) {
                case ADMIN_RELOAD, ADMIN_USER, ADMIN_PREMIUM, ADMIN_EXPORT_LANG -> true;
                default -> false;
            };
        }
    }

    private CommandDispatcher<Caller> dispatcher;
    private RecordingHooks hooks;

    @BeforeEach
    void buildTree() {
        dispatcher = new CommandDispatcher<>();
        hooks = new RecordingHooks();
        VouchCommandTree.register(dispatcher, AuthMode.PASSWORD_OPTIONAL_2FA, hooks);
    }

    // ---- helpers -------------------------------------------------------------

    /** The leaf a complete command line ends up executing, or null if it does not parse. */
    private Leaf leafFor(String input, Caller caller) {
        ParseResults<Caller> parse = dispatcher.parse(input, caller);
        if (parse.getReader().canRead() || !parse.getExceptions().isEmpty()) {
            return null;
        }
        CommandContextBuilder<Caller> context = parse.getContext();
        while (context.getChild() != null) {
            context = context.getChild();
        }
        Command<Caller> command = context.getCommand();
        return command instanceof RecordingHooks.LeafCommand leaf ? leaf.leaf() : null;
    }

    private Leaf leafFor(String input) {
        return leafFor(input, Caller.anyone());
    }

    private CommandNode<Caller> node(String... path) {
        CommandNode<Caller> current = dispatcher.getRoot();
        for (String name : path) {
            current = Objects.requireNonNull(current.getChild(name), "no node named '" + name + "' under " + current.getName());
        }
        return current;
    }

    private List<String> suggestionsFor(String input, Caller caller) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, caller)).join()
                .getList().stream().map(s -> s.getText()).toList();
    }

    // ---- the tree itself -----------------------------------------------------

    @Nested
    class EveryApprovedPathParses {

        @Test
        void theEntryFlowStaysFlat() {
            assertEquals(Leaf.LOGIN, leafFor("login hunter2", Caller.player(UNAUTHENTICATED_REGISTERED)));
            assertEquals(Leaf.REGISTER, leafFor("register hunter2 hunter2", Caller.player(UNAUTHENTICATED_UNREGISTERED)));
            assertEquals(Leaf.LOGOUT, leafFor("logout"));
        }

        @Test
        void theTwoFactorBranchLivesUnderVouch() {
            assertEquals(Leaf.TWO_FACTOR_SETUP, leafFor("vouch 2fa setup"));
            assertEquals(Leaf.TWO_FACTOR_VERIFY, leafFor("vouch 2fa verify 123456"));
            assertEquals(Leaf.TWO_FACTOR_DISABLE, leafFor("vouch 2fa disable 123456"));
            assertEquals(Leaf.TWO_FACTOR_STATUS, leafFor("vouch 2fa status"));
        }

        @Test
        void changingAPasswordIsANewFirstClassCommand() {
            assertEquals(Leaf.PASSWORD, leafFor("vouch password oldpw newpw"));
        }

        @Test
        void premiumReplacesMarkAsOnline() {
            assertEquals(Leaf.PREMIUM, leafFor("vouch premium"));
        }

        @Test
        void theAdminBranchGroupsByObjectThenVerb() {
            assertEquals(Leaf.ADMIN_RELOAD, leafFor("vouch admin reload"));
            assertEquals(Leaf.ADMIN_USER_INFO, leafFor("vouch admin user Notch info"));
            assertEquals(Leaf.ADMIN_USER_UNREGISTER, leafFor("vouch admin user Notch unregister"));
            assertEquals(Leaf.ADMIN_USER_RESETPW, leafFor("vouch admin user Notch resetpw newpw"));
            assertEquals(Leaf.ADMIN_PREMIUM_FORCE_OFFLINE, leafFor("vouch admin premium Notch force-offline"));
            assertEquals(Leaf.ADMIN_PREMIUM_FORCE_ONLINE, leafFor("vouch admin premium Notch force-online"));
        }

        @Test
        void exportLangSurvivesTheRestructure() {
            assertEquals(Leaf.ADMIN_EXPORT_LANG, leafFor("vouch admin export-lang"));
        }

        @Test
        void anIncompletePathDoesNotSilentlyRunSomethingElse() {
            assertEquals(null, leafFor("vouch admin user Notch"));
            assertEquals(null, leafFor("vouch 2fa"));
            assertEquals(null, leafFor("vouch admin"));
        }
    }

    @Nested
    class BuiltOnceRegisteredTwice {

        @Test
        void authIsARedirectToVouchRatherThanASecondCopy() {
            assertSame(node("vouch"), node("auth").getRedirect());
        }

        @Test
        void everyPathUnderAuthRunsTheSameExecutorAsUnderVouch() {
            for (String path : List.of(
                    "2fa setup", "2fa verify 123456", "2fa disable 123456", "2fa status",
                    "password oldpw newpw", "premium",
                    "admin reload", "admin export-lang",
                    "admin user Notch info", "admin user Notch unregister", "admin user Notch resetpw newpw",
                    "admin premium Notch force-offline", "admin premium Notch force-online")) {
                assertEquals(leafFor("vouch " + path), leafFor("auth " + path),
                        "/auth " + path + " must run what /vouch " + path + " runs");
                assertNotNull(leafFor("auth " + path), "/auth " + path + " must resolve to something");
            }
        }

        @Test
        void theVouchBranchHasExactlyOneCopyOfEachSubcommand() {
            // The drift this replaces: /auth had its own hand-typed children, so a node
            // added to one tree never appeared in the other.
            assertTrue(node("auth").getChildren().isEmpty(),
                    "/auth must carry no children of its own, only a redirect");
        }
    }

    @Nested
    class AliasesThatWereKeptWorking {

        @Test
        void theShortEntryAliasesRedirectToTheRealCommands() {
            assertSame(node("login"), node("l").getRedirect());
            assertSame(node("register"), node("reg").getRedirect());
        }

        @Test
        void theShortEntryAliasesParseWithTheirArguments() {
            assertEquals(Leaf.LOGIN, leafFor("l hunter2", Caller.player(UNAUTHENTICATED_REGISTERED)));
            assertEquals(Leaf.REGISTER, leafFor("reg hunter2 hunter2", Caller.player(UNAUTHENTICATED_UNREGISTERED)));
        }

        @Test
        void theStandaloneTwoFactorRootStillReachesTheSameNodes() {
            assertSame(node("vouch", "2fa"), node("2fa").getRedirect());
            assertEquals(Leaf.TWO_FACTOR_SETUP, leafFor("2fa setup"));
            assertEquals(Leaf.TWO_FACTOR_VERIFY, leafFor("2fa verify 123456"));
            assertEquals(Leaf.TWO_FACTOR_DISABLE, leafFor("2fa disable 123456"));
            assertEquals(Leaf.TWO_FACTOR_STATUS, leafFor("2fa status"));
        }

        @Test
        void markAsOnlineStillRunsWhatPremiumRuns() {
            assertEquals(Leaf.PREMIUM, leafFor("vouch markAsOnline"));
            assertEquals(leafFor("vouch premium"), leafFor("vouch markAsOnline"));
        }

        @Test
        void theOldFlatAdminVerbsStillRunWhatTheirNewPathsRun() {
            assertEquals(Leaf.ADMIN_USER_UNREGISTER, leafFor("vouch admin unregister Notch"));
            assertEquals(Leaf.ADMIN_PREMIUM_FORCE_OFFLINE, leafFor("vouch admin force-offline Notch"));
            assertEquals(Leaf.ADMIN_PREMIUM_FORCE_ONLINE, leafFor("vouch admin force-online Notch"));
        }

        @Test
        void theOldFlatAdminVerbsAreReachableThroughAuthToo() {
            assertEquals(Leaf.ADMIN_USER_UNREGISTER, leafFor("auth admin unregister Notch"));
        }
    }

    @Nested
    class ModeSpecificEntryCommands {

        @Test
        void twoFactorOnlyModeTakesACodeWhereAPasswordWouldGo() {
            CommandDispatcher<Caller> totp = new CommandDispatcher<>();
            RecordingHooks totpHooks = new RecordingHooks();
            VouchCommandTree.register(totp, AuthMode.TWOFA_ONLY, totpHooks);

            ParseResults<Caller> login = totp.parse("login 123456", Caller.player(UNAUTHENTICATED_REGISTERED));
            assertFalse(login.getReader().canRead(), "/login <code> must parse in 2FA-only mode");

            ParseResults<Caller> register = totp.parse("register", Caller.player(UNAUTHENTICATED_UNREGISTERED));
            assertFalse(register.getReader().canRead(), "/register takes no argument in 2FA-only mode");
        }

        @Test
        void passwordModeStillWantsAConfirmation() {
            ParseResults<Caller> tooFew = dispatcher.parse("register hunter2", Caller.player(UNAUTHENTICATED_UNREGISTERED));
            assertEquals(null, leafFor("register hunter2", Caller.player(UNAUTHENTICATED_UNREGISTERED)));
            assertTrue(tooFew.getContext().getNodes().size() < 3);
        }
    }

    // ---- state-aware visibility ---------------------------------------------

    @Nested
    class StateDecidesWhatExists {

        @Test
        void aJailedPlayerIsNotSentTheVouchBranchAtAll() {
            Caller jailed = Caller.player(UNAUTHENTICATED_REGISTERED);

            assertFalse(node("vouch").canUse(jailed));
            assertFalse(node("auth").canUse(jailed));
            assertFalse(node("2fa").canUse(jailed));
            assertFalse(node("logout").canUse(jailed));
        }

        @Test
        void aJailedRegisteredPlayerSeesLoginAndNotRegister() {
            Caller jailed = Caller.player(UNAUTHENTICATED_REGISTERED);

            assertTrue(node("login").canUse(jailed));
            assertTrue(node("l").canUse(jailed));
            assertFalse(node("register").canUse(jailed));
            assertFalse(node("reg").canUse(jailed));
        }

        @Test
        void aJailedUnregisteredPlayerSeesRegisterAndNotLogin() {
            Caller jailed = Caller.player(UNAUTHENTICATED_UNREGISTERED);

            assertTrue(node("register").canUse(jailed));
            assertFalse(node("login").canUse(jailed));
        }

        @Test
        void aPlayerOwingASecondFactorSeesOnlyTheVerifyStep() {
            Caller awaiting = Caller.player(AWAITING_2FA);

            assertTrue(node("vouch", "2fa", "verify").canUse(awaiting));
            assertFalse(node("vouch", "2fa", "setup").canUse(awaiting));
            assertFalse(node("vouch", "2fa", "disable").canUse(awaiting));
            assertFalse(node("vouch", "2fa", "status").canUse(awaiting));
            assertFalse(node("login").canUse(awaiting));
            assertTrue(node("vouch").canUse(awaiting), "the branch stays visible for the verify step");
        }

        @Test
        void setupIsOfferedOnlyToSomeoneWithoutTwoFactorAndDisableOnlyToSomeoneWithIt() {
            Caller without = Caller.player(AUTHENTICATED);
            Caller with = Caller.playerWith2FA(AUTHENTICATED);

            assertTrue(node("vouch", "2fa", "setup").canUse(without));
            assertFalse(node("vouch", "2fa", "disable").canUse(without));

            assertFalse(node("vouch", "2fa", "setup").canUse(with));
            assertTrue(node("vouch", "2fa", "disable").canUse(with));
        }

        @Test
        void theAdminBranchIsHiddenFromAnOrdinaryAuthenticatedPlayer() {
            Caller ordinary = Caller.player(AUTHENTICATED);

            assertFalse(node("vouch", "admin").canUse(ordinary));
            assertTrue(node("vouch", "premium").canUse(ordinary));
        }

        @Test
        void thePredicateAlsoRefusesToExecuteNotJustToDisplay() {
            Caller jailed = Caller.player(UNAUTHENTICATED_REGISTERED);

            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("vouch 2fa setup", jailed));
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("logout", jailed));
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("2fa status", jailed));
        }

        @Test
        void aliasNodesCarryTheSameGateAsWhatTheyRedirectTo() {
            Caller unregistered = Caller.player(UNAUTHENTICATED_UNREGISTERED);

            assertEquals(node("register").canUse(unregistered), node("reg").canUse(unregistered));
            assertEquals(node("login").canUse(unregistered), node("l").canUse(unregistered));
            assertEquals(node("vouch").canUse(unregistered), node("auth").canUse(unregistered));
        }
    }

    // ---- suggestions ---------------------------------------------------------

    @Nested
    class ArgumentsThatNameSomethingSuggestSomething {

        @BeforeEach
        void seedNames() {
            hooks.names.seed(List.of("Alex", "Notch", "Steve"));
        }

        @Test
        void adminUserOffersRegisteredPlayers() {
            assertEquals(List.of("Alex", "Notch", "Steve"),
                    suggestionsFor("vouch admin user ", Caller.asAdmin()));
        }

        @Test
        void adminPremiumOffersRegisteredPlayers() {
            assertEquals(List.of("Alex", "Notch", "Steve"),
                    suggestionsFor("vouch admin premium ", Caller.asAdmin()));
        }

        @Test
        void theLegacyFlatAdminVerbsSuggestToo() {
            assertEquals(List.of("Alex", "Notch", "Steve"),
                    suggestionsFor("vouch admin unregister ", Caller.asAdmin()));
            assertEquals(List.of("Alex", "Notch", "Steve"),
                    suggestionsFor("vouch admin force-offline ", Caller.asAdmin()));
        }

        @Test
        void suggestionsNarrowAsThePlayerTypesAndIgnoreCase() {
            assertEquals(List.of("Notch"), suggestionsFor("vouch admin user no", Caller.asAdmin()));
            assertEquals(List.of("Alex"), suggestionsFor("vouch admin user AL", Caller.asAdmin()));
        }

        @Test
        void aPasswordArgumentSuggestsNothing() {
            assertEquals(List.of(), suggestionsFor("vouch password ", Caller.asAdmin()));
            assertEquals(List.of(), suggestionsFor("login ", Caller.player(UNAUTHENTICATED_REGISTERED)));
        }

        @Test
        void suggestionsFollowTheAliasesToo() {
            assertEquals(List.of("Alex", "Notch", "Steve"),
                    suggestionsFor("auth admin user ", Caller.asAdmin()));
        }
    }

    @Nested
    class NamesAreNotLeakedToPlayersWhoCannotSeeTheBranch {

        @Test
        void anOrdinaryPlayerGetsNoAdminSuggestions() {
            hooks.names.seed(List.of("Alex", "Notch"));

            List<String> suggestions =
                    new ArrayList<>(suggestionsFor("vouch admin user ", Caller.player(AUTHENTICATED)));

            assertEquals(List.of(), suggestions);
        }
    }
}
