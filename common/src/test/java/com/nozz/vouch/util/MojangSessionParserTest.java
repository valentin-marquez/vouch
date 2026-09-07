package com.nozz.vouch.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parsing of the Mojang session server (`hasJoined`) response.
 *
 * These tests exist so that skin/cape delivery can be verified without
 * launching a Minecraft server: the session payloads below are real-shaped
 * responses, and the parser is the only thing standing between them and the
 * GameProfile the player logs in with.
 */
class MojangSessionParserTest {

    private static final String TEXTURES_VALUE =
            "eyJ0aW1lc3RhbXAiOjE2NjcwMDAwMDAwMDAsInByb2ZpbGVJZCI6IjQ1NjZlNjlmYzkwNzQ4ZWU4ZDcxZDdiYTVhYTAwZDIwIn0=";
    private static final String TEXTURES_SIGNATURE = "Ac7A1sPvKpQeE5hR9mZgV2xN==";

    private static String sessionResponseWithTextures() {
        return """
                {
                  "id": "4566e69fc90748ee8d71d7ba5aa00d20",
                  "name": "Thinkofdeath",
                  "properties": [
                    {
                      "name": "textures",
                      "value": "%s",
                      "signature": "%s"
                    }
                  ]
                }
                """.formatted(TEXTURES_VALUE, TEXTURES_SIGNATURE);
    }

    @Test
    void parsesUndashedUuidIntoStandardUuid() {
        GameProfile profile = MojangSessionParser.parseProfile(sessionResponseWithTextures(), true).orElseThrow();

        assertEquals(UUID.fromString("4566e69f-c907-48ee-8d71-d7ba5aa00d20"), profile.getId());
    }

    @Test
    void parsesPlayerName() {
        GameProfile profile = MojangSessionParser.parseProfile(sessionResponseWithTextures(), true).orElseThrow();

        assertEquals("Thinkofdeath", profile.getName());
    }

    @Test
    void attachesSignedTexturesPropertyWhenSkinFetchingIsEnabled() {
        GameProfile profile = MojangSessionParser.parseProfile(sessionResponseWithTextures(), true).orElseThrow();

        Collection<Property> textures = profile.getProperties().get("textures");
        assertEquals(1, textures.size());
        Property texture = textures.iterator().next();
        assertEquals("textures", texture.name());
        assertEquals(TEXTURES_VALUE, texture.value());
        assertEquals(TEXTURES_SIGNATURE, texture.signature());
    }

    @Test
    void omitsTexturesPropertyWhenSkinFetchingIsDisabled() {
        GameProfile profile = MojangSessionParser.parseProfile(sessionResponseWithTextures(), false).orElseThrow();

        assertTrue(profile.getProperties().isEmpty(),
                "skin fetching is off, so no textures should reach the profile");
    }

    @Test
    void keepsPropertyWithoutSignatureUnsigned() {
        String json = """
                {
                  "id": "4566e69fc90748ee8d71d7ba5aa00d20",
                  "name": "Thinkofdeath",
                  "properties": [
                    { "name": "textures", "value": "%s" }
                  ]
                }
                """.formatted(TEXTURES_VALUE);

        GameProfile profile = MojangSessionParser.parseProfile(json, true).orElseThrow();

        Property texture = profile.getProperties().get("textures").iterator().next();
        assertNull(texture.signature());
    }

    @Test
    void returnsProfileWhenResponseCarriesNoProperties() {
        String json = """
                { "id": "4566e69fc90748ee8d71d7ba5aa00d20", "name": "Thinkofdeath" }
                """;

        GameProfile profile = MojangSessionParser.parseProfile(json, true).orElseThrow();

        assertEquals("Thinkofdeath", profile.getName());
        assertTrue(profile.getProperties().isEmpty());
    }

    @Test
    void returnsEmptyForMalformedJson() {
        Optional<GameProfile> profile = MojangSessionParser.parseProfile("not json at all", true);

        assertTrue(profile.isEmpty());
    }

    @Test
    void returnsEmptyWhenIdIsMissing() {
        Optional<GameProfile> profile = MojangSessionParser.parseProfile("{ \"name\": \"Thinkofdeath\" }", true);

        assertTrue(profile.isEmpty());
    }

    @Test
    void returnsEmptyWhenIdIsNotA32CharHexString() {
        Optional<GameProfile> profile =
                MojangSessionParser.parseProfile("{ \"id\": \"abc\", \"name\": \"Thinkofdeath\" }", true);

        assertTrue(profile.isEmpty());
    }
}
