package com.nozz.vouch.util;

import com.nozz.vouch.config.VouchConfigManager;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.nozz.vouch.util.PacketHelper.sendPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all user experience elements for Vouch.
 * 
 * Handles:
 * - Title/Subtitle display
 * - ActionBar messages
 * - BossBar countdown
 * - Sound effects
 * - Message formatting with colors
 */
public final class UXManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/UX");

    private static UXManager instance;
    private final Map<UUID, ServerBossBar> activeBossBars = new ConcurrentHashMap<>();

    private UXManager() {
    }

    public static UXManager getInstance() {
        if (instance == null) {
            instance = new UXManager();
        }
        return instance;
    }

    /**
     * Send a title and subtitle to the player
     */
    public void sendTitle(ServerPlayerEntity player, String title, String subtitle) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useTitles())
            return;

        sendPacket(player, new TitleFadeS2CPacket(
                config.getTitleFadeIn(),
                config.getTitleStay(),
                config.getTitleFadeOut()));

        if (subtitle != null && !subtitle.isEmpty()) {
            sendPacket(player, new SubtitleS2CPacket(formatText(subtitle)));
        }

        if (title != null && !title.isEmpty()) {
            sendPacket(player, new TitleS2CPacket(formatText(title)));
        }
    }

    /**
     * Send the welcome title for unregistered players
     */
    public void sendWelcomeTitleUnregistered(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        VouchConfigManager config = VouchConfigManager.getInstance();

        String subtitleKey = config.getAuthMode().is2FARequired()
                ? "vouch.ui.subtitle.2fa_only.unregistered"
                : "vouch.ui.subtitle.unregistered";

        sendTitle(player,
                lang.getRaw("vouch.ui.title.welcome"),
                lang.getRaw(subtitleKey));
    }

    /**
     * Send the welcome title for registered players
     */
    public void sendWelcomeTitleRegistered(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        VouchConfigManager config = VouchConfigManager.getInstance();

        String subtitleKey = config.getAuthMode().is2FARequired()
                ? "vouch.ui.subtitle.2fa_only.registered"
                : "vouch.ui.subtitle.registered";

        sendTitle(player,
                lang.getRaw("vouch.ui.title.welcome"),
                lang.getRaw(subtitleKey));
    }

    /**
     * Send login success title
     */
    public void sendLoginSuccessTitle(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendTitle(player,
                lang.getRaw("vouch.ui.title.login_success"),
                lang.getRaw("vouch.ui.subtitle.login_success"));
    }

    /**
     * Send register success title
     */
    public void sendRegisterSuccessTitle(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendTitle(player,
                lang.getRaw("vouch.ui.title.register_success"),
                lang.getRaw("vouch.ui.subtitle.register_success"));
    }

    /**
     * Send wrong password title (if enabled)
     */
    public void sendWrongPasswordTitle(ServerPlayerEntity player) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (config.useErrorTitles()) {
            LangManager lang = LangManager.getInstance();
            sendTitle(player,
                    lang.getRaw("vouch.ui.title.wrong_password"),
                    lang.getRaw("vouch.ui.subtitle.wrong_password"));
        }
    }

    /**
     * Clear any active title
     */
    public void clearTitle(ServerPlayerEntity player) {
        sendPacket(player, new ClearTitleS2CPacket(true));
    }

    /**
     * Send an action bar message
     */
    public void sendActionBar(ServerPlayerEntity player, String message) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useActionBar())
            return;

        sendPacket(player, new OverlayMessageS2CPacket(formatText(message)));
    }

    /**
     * Send the pre-auth action bar with countdown
     */
    public void sendPreAuthActionBar(ServerPlayerEntity player, int secondsRemaining) {
        LangManager lang = LangManager.getInstance();
        VouchConfigManager config = VouchConfigManager.getInstance();

        String messageKey = config.getAuthMode().is2FARequired()
                ? "vouch.ui.actionbar.pre_auth_2fa_only"
                : "vouch.ui.actionbar.pre_auth";

        String message = lang.get(messageKey, "time", secondsRemaining);
        sendActionBar(player, message);
    }

    /**
     * Send the awaiting 2FA action bar
     */
    public void sendAwaiting2FAActionBar(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendActionBar(player, lang.getRaw("vouch.ui.actionbar.awaiting_2fa"));
    }

    /**
     * Send rate limited action bar
     */
    public void sendRateLimitedActionBar(ServerPlayerEntity player, int secondsRemaining) {
        LangManager lang = LangManager.getInstance();
        String message = lang.get("vouch.ui.actionbar.rate_limited", "time", secondsRemaining);
        sendActionBar(player, message);
    }

    /**
     * Create and show a boss bar countdown for a player
     */
    public void showCountdownBossBar(ServerPlayerEntity player, int totalSeconds) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useBossBar())
            return;

        UUID uuid = player.getUuid();

        removeCountdownBossBar(player);

        LangManager lang = LangManager.getInstance();
        ServerBossBar bossBar = new ServerBossBar(
                formatText(lang.get("vouch.ui.bossbar.text", "time", totalSeconds)),
                parseBossBarColor(config.getBossBarColor()),
                parseBossBarStyle(config.getBossBarStyle()));
        bossBar.setPercent(1.0f);
        bossBar.addPlayer(player);

        activeBossBars.put(uuid, bossBar);
        LOGGER.debug("BossBar created for player {}", player.getName().getString());
    }

    /**
     * Update the boss bar countdown
     */
    public void updateCountdownBossBar(ServerPlayerEntity player, int secondsRemaining, int totalSeconds) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useBossBar())
            return;

        UUID uuid = player.getUuid();
        ServerBossBar bossBar = activeBossBars.get(uuid);

        if (bossBar == null)
            return;

        LangManager lang = LangManager.getInstance();
        String text = lang.get("vouch.ui.bossbar.text", "time", secondsRemaining);
        bossBar.setName(formatText(text));

        float progress = totalSeconds > 0 ? (float) secondsRemaining / totalSeconds : 0f;
        bossBar.setPercent(Math.max(0f, Math.min(1f, progress)));

        if (secondsRemaining <= 10) {
            bossBar.setColor(BossBar.Color.RED);
        } else if (secondsRemaining <= 20) {
            bossBar.setColor(BossBar.Color.YELLOW);
        }
    }

    /**
     * Remove the countdown boss bar for a player
     */
    public void removeCountdownBossBar(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        ServerBossBar bossBar = activeBossBars.remove(uuid);

        if (bossBar != null) {
            bossBar.removePlayer(player);
            LOGGER.debug("BossBar removed for player {}", player.getName().getString());
        }
    }

    /**
     * Remove boss bar by UUID (for disconnected players)
     */
    public void removeCountdownBossBar(UUID uuid) {
        ServerBossBar bossBar = activeBossBars.remove(uuid);
        if (bossBar != null) {
            bossBar.clearPlayers();
        }
    }

    private BossBar.Color parseBossBarColor(String color) {
        try {
            return BossBar.Color.valueOf(color.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Style parseBossBarStyle(String style) {
        try {
            return BossBar.Style.valueOf(style.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Style.PROGRESS;
        }
    }

    /**
     * Play a sound for the player
     */
    public void playSound(ServerPlayerEntity player, String soundId) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useSounds())
            return;

        try {
            Identifier id = Identifier.of(soundId);
            SoundEvent sound = Registries.SOUND_EVENT.get(id);

            if (sound != null) {
                player.playSoundToPlayer(
                        sound,
                        SoundCategory.MASTER,
                        config.getSoundVolume(),
                        config.getSoundPitch());
            } else {
                LOGGER.warn("Sound not found: {}", soundId);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to play sound '{}': {}", soundId, e.getMessage());
        }
    }

    /**
     * Play login success sound
     */
    public void playLoginSuccessSound(ServerPlayerEntity player) {
        playSound(player, VouchConfigManager.getInstance().getSoundLoginSuccess());
    }

    /**
     * Play register success sound
     */
    public void playRegisterSuccessSound(ServerPlayerEntity player) {
        playSound(player, VouchConfigManager.getInstance().getSoundRegisterSuccess());
    }

    /**
     * Play wrong password sound
     */
    public void playWrongPasswordSound(ServerPlayerEntity player) {
        playSound(player, VouchConfigManager.getInstance().getSoundWrongPassword());
    }

    /**
     * Play auth timeout sound
     */
    public void playAuthTimeoutSound(ServerPlayerEntity player) {
        playSound(player, VouchConfigManager.getInstance().getSoundAuthTimeout());
    }

    /**
     * Play rate limited sound
     */
    public void playRateLimitedSound(ServerPlayerEntity player) {
        playSound(player, VouchConfigManager.getInstance().getSoundRateLimited());
    }

    /**
     * Format a string with color codes and return as Text
     */
    public Text formatText(String input) {
        if (input == null || input.isEmpty()) {
            return Text.empty();
        }

        VouchConfigManager config = VouchConfigManager.getInstance();

        // Replace placeholder colors
        String processed = input
                .replace("{primary}", config.getColorPrimary())
                .replace("{success}", config.getColorSuccess())
                .replace("{error}", config.getColorError())
                .replace("{info}", config.getColorInfo())
                .replace("{muted}", config.getColorMuted());

        return parseColorCodes(processed);
    }

    /**
     * Send a formatted message with prefix
     */
    public void sendMessage(ServerPlayerEntity player, String message) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        String prefixed = config.getBrandingPrefix() + message;
        player.sendMessage(formatText(prefixed), false);
    }

    /**
     * Send a raw formatted message (no prefix)
     */
    public void sendRawMessage(ServerPlayerEntity player, String message) {
        player.sendMessage(formatText(message), false);
    }

    /**
     * Send welcome message for unregistered player
     */
    public void sendWelcomeMessageUnregistered(ServerPlayerEntity player) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        LangManager lang = LangManager.getInstance();

        if (config.clearChatOnJoin()) {
            clearChat(player);
        }
        addPadding(player, config.getWelcomeMessagePadding());

        // Check auth mode for appropriate message
        String messageKey = config.getAuthMode().is2FARequired()
                ? "vouch.auth.welcome.2fa_only.unregistered"
                : "vouch.auth.welcome.unregistered";
        sendMessage(player, lang.getRaw(messageKey));
    }

    /**
     * Send welcome message for registered player
     */
    public void sendWelcomeMessageRegistered(ServerPlayerEntity player) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        LangManager lang = LangManager.getInstance();

        if (config.clearChatOnJoin()) {
            clearChat(player);
        }
        addPadding(player, config.getWelcomeMessagePadding());

        String messageKey = config.getAuthMode().is2FARequired()
                ? "vouch.auth.welcome.2fa_only.registered"
                : "vouch.auth.welcome.registered";
        sendMessage(player, lang.getRaw(messageKey));
    }

    /**
     * Clear chat by sending many empty lines
     */
    private void clearChat(ServerPlayerEntity player) {
        for (int i = 0; i < 100; i++) {
            player.sendMessage(Text.empty(), false);
        }
    }

    private void addPadding(ServerPlayerEntity player, int lines) {
        for (int i = 0; i < lines; i++) {
            player.sendMessage(Text.empty(), false);
        }
    }

    /**
     * Parse Minecraft color codes (§ or &) and return Text
     */
    private Text parseColorCodes(String input) {
        // Replace & with § for compatibility
        String normalized = input.replace('&', '\u00A7');

        MutableText result = Text.empty();
        StringBuilder current = new StringBuilder();
        Formatting currentFormat = null;
        boolean isBold = false;
        boolean isItalic = false;
        boolean isUnderline = false;
        boolean isStrikethrough = false;
        boolean isObfuscated = false;

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);

            if (c == '\u00A7' && i + 1 < normalized.length()) {
                // Flush current text
                if (!current.isEmpty()) {
                    MutableText segment = Text.literal(current.toString());
                    if (currentFormat != null)
                        segment.formatted(currentFormat);
                    if (isBold)
                        segment.formatted(Formatting.BOLD);
                    if (isItalic)
                        segment.formatted(Formatting.ITALIC);
                    if (isUnderline)
                        segment.formatted(Formatting.UNDERLINE);
                    if (isStrikethrough)
                        segment.formatted(Formatting.STRIKETHROUGH);
                    if (isObfuscated)
                        segment.formatted(Formatting.OBFUSCATED);
                    result.append(segment);
                    current = new StringBuilder();
                }

                char code = Character.toLowerCase(normalized.charAt(++i));
                Formatting fmt = getFormattingByCode(code);

                if (fmt != null) {
                    if (fmt == Formatting.RESET) {
                        currentFormat = null;
                        isBold = false;
                        isItalic = false;
                        isUnderline = false;
                        isStrikethrough = false;
                        isObfuscated = false;
                    } else if (fmt == Formatting.BOLD) {
                        isBold = true;
                    } else if (fmt == Formatting.ITALIC) {
                        isItalic = true;
                    } else if (fmt == Formatting.UNDERLINE) {
                        isUnderline = true;
                    } else if (fmt == Formatting.STRIKETHROUGH) {
                        isStrikethrough = true;
                    } else if (fmt == Formatting.OBFUSCATED) {
                        isObfuscated = true;
                    } else {
                        currentFormat = fmt;
                    }
                }
            } else {
                current.append(c);
            }
        }

        // Flush remaining text
        if (!current.isEmpty()) {
            MutableText segment = Text.literal(current.toString());
            if (currentFormat != null)
                segment.formatted(currentFormat);
            if (isBold)
                segment.formatted(Formatting.BOLD);
            if (isItalic)
                segment.formatted(Formatting.ITALIC);
            if (isUnderline)
                segment.formatted(Formatting.UNDERLINE);
            if (isStrikethrough)
                segment.formatted(Formatting.STRIKETHROUGH);
            if (isObfuscated)
                segment.formatted(Formatting.OBFUSCATED);
            result.append(segment);
        }

        return result;
    }

    private Formatting getFormattingByCode(char code) {
        return switch (code) {
            case '0' -> Formatting.BLACK;
            case '1' -> Formatting.DARK_BLUE;
            case '2' -> Formatting.DARK_GREEN;
            case '3' -> Formatting.DARK_AQUA;
            case '4' -> Formatting.DARK_RED;
            case '5' -> Formatting.DARK_PURPLE;
            case '6' -> Formatting.GOLD;
            case '7' -> Formatting.GRAY;
            case '8' -> Formatting.DARK_GRAY;
            case '9' -> Formatting.BLUE;
            case 'a' -> Formatting.GREEN;
            case 'b' -> Formatting.AQUA;
            case 'c' -> Formatting.RED;
            case 'd' -> Formatting.LIGHT_PURPLE;
            case 'e' -> Formatting.YELLOW;
            case 'f' -> Formatting.WHITE;
            case 'k' -> Formatting.OBFUSCATED;
            case 'l' -> Formatting.BOLD;
            case 'm' -> Formatting.STRIKETHROUGH;
            case 'n' -> Formatting.UNDERLINE;
            case 'o' -> Formatting.ITALIC;
            case 'r' -> Formatting.RESET;
            default -> null;
        };
    }

    /**
     * Send complete login success feedback (title + sound + message)
     */
    public void onLoginSuccess(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendLoginSuccessTitle(player);
        playLoginSuccessSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.login.success"));
        removeCountdownBossBar(player);
        clearActionBar(player);
    }

    /**
     * Send complete register success feedback (title + sound + message)
     */
    public void onRegisterSuccess(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendRegisterSuccessTitle(player);
        playRegisterSuccessSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.register.success"));
        removeCountdownBossBar(player);
        clearActionBar(player);
    }

    /**
     * Send wrong password feedback
     */
    public void onWrongPassword(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendWrongPasswordTitle(player);
        playWrongPasswordSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.login.wrong_password"));
    }

    /**
     * Send session restored feedback
     */
    public void onSessionRestored(ServerPlayerEntity player) {
        LangManager lang = LangManager.getInstance();
        sendLoginSuccessTitle(player);
        playLoginSuccessSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.session_restored"));
    }

    /**
     * Clear action bar
     */
    private void clearActionBar(ServerPlayerEntity player) {
        sendPacket(player, new OverlayMessageS2CPacket(Text.empty()));
    }

    /**
     * Cleanup all UX elements for a player (on disconnect)
     */
    public void cleanupPlayer(UUID uuid) {
        removeCountdownBossBar(uuid);
    }

    public void shutdown() {
        for (ServerBossBar bar : activeBossBars.values()) {
            bar.clearPlayers();
        }
        activeBossBars.clear();
        LOGGER.debug("UXManager shutdown complete");
    }
}
