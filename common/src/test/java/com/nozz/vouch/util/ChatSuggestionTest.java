package com.nozz.vouch.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nozz.vouch.auth.AuthMode;
import com.nozz.vouch.command.VouchCommand;
import com.nozz.vouch.command.VouchCommandTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prompts that offer a clickable next step.
 *
 * <p>A suggestion has two halves and each rots differently. The translation key
 * can be renamed, leaving the player a message that renders as a raw dotted
 * identifier. The command can move — which is exactly what this restructure did
 * to half of them — leaving a click that fills the chat box with something the
 * dispatcher no longer knows. Both halves are checked here against their real
 * sources: the source language file, and a dispatcher carrying the real tree.
 *
 * <p>The rendered {@code Text} is not asserted on: building one reaches into
 * Minecraft's registries, which cannot be brought up on a plain test classpath.
 * The table it is built from can be, and the table is where the mistakes live.
 */
class ChatSuggestionTest {

    private static final Path SOURCE_LANGUAGE =
            Path.of("src/main/resources/assets/vouch/lang/en_us.json");

    /** A dispatcher carrying the real command tree, with every node visible. */
    private static CommandDispatcher<Object> tree() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        VouchCommandTree.register(dispatcher, AuthMode.PASSWORD_OPTIONAL_2FA,
                new VouchCommandTree.Hooks<>() {
                    @Override
                    public Predicate<Object> requirement(VouchCommand command) {
                        return source -> true;
                    }

                    @Override
                    public Command<Object> action(VouchCommandTree.Leaf leaf) {
                        return context -> 1;
                    }

                    @Override
                    public SuggestionProvider<Object> registeredPlayerNames() {
                        return (context, builder) -> builder.buildFuture();
                    }
                });
        return dispatcher;
    }

    private static JsonObject sourceLanguage() throws IOException {
        return JsonParser.parseString(Files.readString(SOURCE_LANGUAGE, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    void everyPromptNamesAMessageThatExists() throws IOException {
        JsonObject source = sourceLanguage();

        List<String> missing = new ArrayList<>();
        for (ChatSuggestion suggestion : ChatSuggestion.values()) {
            if (!source.has(suggestion.langKey())) {
                missing.add(suggestion + " -> " + suggestion.langKey());
            }
        }

        assertEquals(List.of(), missing,
                "a prompt whose key is gone renders as the raw key in front of the player");
    }

    @Test
    void theHoverTextExistsToo() throws IOException {
        assertTrue(sourceLanguage().has(ChatSuggestion.HOVER_KEY),
                ChatSuggestion.HOVER_KEY + " is shown on every clickable prompt");
    }

    @Test
    void theHoverTextIsToldWhichCommandItIsDescribing() throws IOException {
        assertTrue(sourceLanguage().get(ChatSuggestion.HOVER_KEY).getAsString().contains("{command}"),
                "the hover has to be able to name the command it will fill in");
    }

    @Test
    void everySuggestedCommandStillResolvesInTheRealTree() {
        CommandDispatcher<Object> dispatcher = tree();
        Object anyone = new Object();

        List<String> broken = new ArrayList<>();
        for (ChatSuggestion suggestion : ChatSuggestion.values()) {
            ParseResults<Object> parse = dispatcher.parse(suggestion.commandPath(), anyone);
            // Either the path is complete, or it is a complete prefix waiting for the
            // argument the trailing space invites. Neither may leave unparsed input.
            if (parse.getReader().canRead()) {
                broken.add(suggestion + " -> " + suggestion.command()
                        + " (stopped at '" + parse.getReader().getRemaining() + "')");
            }
        }

        assertEquals(List.of(), broken,
                "a clickable prompt must not fill in a command the dispatcher has never heard of");
    }

    @Test
    void aPromptWithoutArgumentsResolvesToAnExecutableCommand() {
        CommandDispatcher<Object> dispatcher = tree();
        Object anyone = new Object();

        // "/vouch 2fa setup" takes nothing further, so it must be runnable as clicked.
        ParseResults<Object> parse = dispatcher.parse("vouch 2fa setup", anyone);

        assertFalse(parse.getReader().canRead());
        assertTrue(parse.getContext().getChild() != null || parse.getContext().getCommand() != null);
    }

    @Test
    void everyCommandIsOfferedAsSomethingToFillInNotSomethingToSend() {
        for (ChatSuggestion suggestion : ChatSuggestion.values()) {
            assertTrue(suggestion.command().startsWith("/"),
                    suggestion + " must start with a slash or it is pasted as chat");
        }
    }

    @Test
    void aPromptThatExpectsAnArgumentLeavesRoomForIt() {
        // The trailing space is where the player's caret lands. Without it they have to
        // type one before their password, which is exactly the friction this removes.
        assertTrue(ChatSuggestion.NOT_REGISTERED.command().endsWith(" "));
        assertTrue(ChatSuggestion.MUST_LOGIN_FIRST.command().endsWith(" "));
        assertTrue(ChatSuggestion.TWO_FACTOR_REQUIRED.command().endsWith(" "));

        // And a prompt that expects nothing must not leave a stray one.
        assertFalse(ChatSuggestion.TWO_FACTOR_STATUS_DISABLED.command().endsWith(" "));
        assertFalse(ChatSuggestion.TWO_FACTOR_REQUIRED_FOR_OPS.command().endsWith(" "));
    }

    @Test
    void noPromptOffersACommandThatWouldSubmitACredentialOnClick() {
        // Every entry taking a secret must be incomplete as clicked, so that even a
        // client which ran it instead of filling it in would submit nothing.
        CommandDispatcher<Object> dispatcher = tree();
        Object anyone = new Object();

        for (ChatSuggestion suggestion : List.of(
                ChatSuggestion.NOT_REGISTERED,
                ChatSuggestion.MUST_LOGIN_FIRST,
                ChatSuggestion.TWO_FACTOR_REQUIRED)) {
            ParseResults<Object> parse = dispatcher.parse(suggestion.commandPath(), anyone);
            assertEquals(null, parse.getContext().getCommand(),
                    suggestion + " must not be executable with no argument supplied");
        }
    }
}
