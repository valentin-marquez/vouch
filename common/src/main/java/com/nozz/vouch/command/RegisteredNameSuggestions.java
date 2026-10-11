package com.nozz.vouch.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;

import java.util.Locale;

/**
 * Tab-completion for arguments that name a registered player.
 *
 * <p>Reads {@link RegisteredNames}, never the database: Brigadier asks for
 * suggestions on every keystroke, and a query per keystroke would put the
 * connection pool on the critical path of typing.
 *
 * <p>Generic in the source type and free of Minecraft classes, so the same
 * provider the server installs is the one the tests exercise. It does what
 * {@code CommandSource.suggestMatching} does — prefix match, case-insensitive.
 */
public final class RegisteredNameSuggestions {

    private RegisteredNameSuggestions() {
    }

    public static <S> SuggestionProvider<S> from(RegisteredNames names) {
        return (context, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String name : names.names()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }
}
