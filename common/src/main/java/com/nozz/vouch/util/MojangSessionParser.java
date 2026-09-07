package com.nozz.vouch.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses the JSON returned by the Mojang session server (`hasJoined`) into a
 * {@link GameProfile}.
 *
 * The session response carries the player's signed profile properties — most
 * importantly {@code textures}, which holds the skin and cape. Those properties
 * must be copied onto the profile handed to the server, otherwise the player
 * joins as Steve/Alex despite a successful premium verification.
 *
 * Kept free of networking and config lookups on purpose, so the parsing can be
 * exercised by unit tests without a running server.
 */
public final class MojangSessionParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/MojangSessionParser");

    private static final Pattern UNDASHED_UUID = Pattern.compile("[0-9a-fA-F]{32}");

    private MojangSessionParser() {
    }

    /**
     * Parse a session server response body.
     *
     * @param json                  The raw response body
     * @param includeSkinProperties Whether to copy the signed profile properties (skin/cape)
     * @return The authenticated profile, or empty if the payload is unusable
     */
    public static Optional<GameProfile> parseProfile(String json, boolean includeSkinProperties) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject root = parsed.getAsJsonObject();

            UUID uuid = parseUndashedUuid(root);
            String name = parseName(root);
            if (uuid == null || name == null) {
                return Optional.empty();
            }

            GameProfile profile = new GameProfile(uuid, name);
            if (includeSkinProperties) {
                copyProperties(root, profile);
            }
            return Optional.of(profile);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse Mojang session response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The session server returns UUIDs as 32 hex chars with no dashes.
     */
    private static UUID parseUndashedUuid(JsonObject root) {
        JsonElement id = root.get("id");
        if (id == null || !id.isJsonPrimitive()) {
            return null;
        }
        String hex = id.getAsString();
        if (!UNDASHED_UUID.matcher(hex).matches()) {
            LOGGER.warn("Mojang session response carried a malformed UUID: {}", hex);
            return null;
        }
        return UUID.fromString(hex.substring(0, 8) + "-" +
                hex.substring(8, 12) + "-" +
                hex.substring(12, 16) + "-" +
                hex.substring(16, 20) + "-" +
                hex.substring(20));
    }

    private static String parseName(JsonObject root) {
        JsonElement name = root.get("name");
        if (name == null || !name.isJsonPrimitive()) {
            return null;
        }
        return name.getAsString();
    }

    /**
     * Copy the signed profile properties (textures/skin, cape) onto the profile.
     * The signature must be preserved: clients reject unsigned textures.
     */
    private static void copyProperties(JsonObject root, GameProfile profile) {
        JsonElement properties = root.get("properties");
        if (properties == null || !properties.isJsonArray()) {
            return;
        }

        JsonArray array = properties.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject property = element.getAsJsonObject();
            String name = parseName(property);
            JsonElement value = property.get("value");
            if (name == null || value == null || !value.isJsonPrimitive()) {
                continue;
            }
            JsonElement signature = property.get("signature");
            String signatureValue = (signature != null && signature.isJsonPrimitive())
                    ? signature.getAsString()
                    : null;

            profile.getProperties().put(name, new Property(name, value.getAsString(), signatureValue));
        }
    }
}
