package com.nozz.vouch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural checks on the translation files.
 *
 * A broken translation is invisible until a player triggers the exact message,
 * which makes it the kind of thing that ships unnoticed. These checks catch the
 * two failures that actually break the game for that player: a key that no
 * longer exists in the source language (dead entry, silently never shown) and a
 * placeholder that was renamed or dropped in translation (the message renders
 * with a literal `{player}` or loses the argument entirely).
 */
class LanguageFileTest {

    private static final Path LANG_DIR = Path.of("src/main/resources/assets/vouch/lang");
    private static final String SOURCE_LANGUAGE = "en_us.json";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-zA-Z0-9_]+}");

    @TestFactory
    Stream<DynamicTest> everyTranslationMatchesTheSourceLanguage() throws IOException {
        JsonObject source = readLangFile(LANG_DIR.resolve(SOURCE_LANGUAGE));

        List<Path> translations;
        try (Stream<Path> files = Files.list(LANG_DIR)) {
            translations = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals(SOURCE_LANGUAGE))
                    .sorted()
                    .toList();
        }

        assertTrue(!translations.isEmpty(), "no translation files found in " + LANG_DIR.toAbsolutePath());

        return translations.stream().map(path -> DynamicTest.dynamicTest(
                path.getFileName().toString(),
                () -> assertTranslationMatchesSource(source, path)));
    }

    private void assertTranslationMatchesSource(JsonObject source, Path path) throws IOException {
        JsonObject translation = readLangFile(path);

        List<String> unknownKeys = new ArrayList<>();
        List<String> placeholderMismatches = new ArrayList<>();

        for (String key : translation.keySet()) {
            if (!source.has(key)) {
                unknownKeys.add(key);
                continue;
            }
            Set<String> expected = placeholdersOf(source.get(key).getAsString());
            Set<String> actual = placeholdersOf(translation.get(key).getAsString());
            if (!expected.equals(actual)) {
                placeholderMismatches.add(key + ": expected " + expected + " but found " + actual);
            }
        }

        assertEquals(List.of(), unknownKeys,
                path.getFileName() + " has keys that do not exist in " + SOURCE_LANGUAGE
                        + " — they will never be shown to a player");
        assertEquals(List.of(), placeholderMismatches,
                path.getFileName() + " has placeholders that do not match " + SOURCE_LANGUAGE);
    }

    private static Set<String> placeholdersOf(String message) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(message);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static JsonObject readLangFile(Path path) throws IOException {
        assertTrue(Files.exists(path), "missing language file: " + path.toAbsolutePath());
        String content = Files.readString(path, StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(content);
        } catch (RuntimeException e) {
            throw new UncheckedIOException(new IOException(path.getFileName() + " is not valid JSON", e));
        }
        assertTrue(parsed.isJsonObject(), path.getFileName() + " must be a JSON object");
        return parsed.getAsJsonObject();
    }
}
