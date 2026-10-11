package com.nozz.vouch.util;

import com.nozz.vouch.config.VouchConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.nozz.vouch.util.PacketHelper.sendPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

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
    private final Map<UUID, ServerBossEvent> activeBossBars = new ConcurrentHashMap<>();

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
    public void sendTitle(ServerPlayer player, String title, String subtitle) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useTitles())
            return;

        sendPacket(player, new ClientboundSetTitlesAnimationPacket(
                config.getTitleFadeIn(),
                config.getTitleStay(),
                config.getTitleFadeOut()));

        if (subtitle != null && !subtitle.isEmpty()) {
            sendPacket(player, new ClientboundSetSubtitleTextPacket(formatText(subtitle)));
        }

        if (title != null && !title.isEmpty()) {
            sendPacket(player, new ClientboundSetTitleTextPacket(formatText(title)));
        }
    }

    /**
     * Send the welcome title for unregistered players
     */
    public void sendWelcomeTitleUnregistered(ServerPlayer player) {
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
    public void sendWelcomeTitleRegistered(ServerPlayer player) {
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
    public void sendLoginSuccessTitle(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();
        sendTitle(player,
                lang.getRaw("vouch.ui.title.login_success"),
                lang.getRaw("vouch.ui.subtitle.login_success"));
    }

    /**
     * Send register success title
     */
    public void sendRegisterSuccessTitle(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();
        sendTitle(player,
                lang.getRaw("vouch.ui.title.register_success"),
                lang.getRaw("vouch.ui.subtitle.register_success"));
    }

    /**
     * Send wrong password title (if enabled)
     */
    public void sendWrongPasswordTitle(ServerPlayer player) {
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
    public void clearTitle(ServerPlayer player) {
        sendPacket(player, new ClientboundClearTitlesPacket(true));
    }

    /**
     * Send an action bar message
     */
    public void sendActionBar(ServerPlayer player, String message) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useActionBar())
            return;

        sendPacket(player, new ClientboundSetActionBarTextPacket(formatText(message)));
    }

    /**
     * Send the pre-auth action bar with countdown
     */
    public void sendPreAuthActionBar(ServerPlayer player, int secondsRemaining) {
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
    public void sendAwaiting2FAActionBar(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();
        sendActionBar(player, lang.getRaw("vouch.ui.actionbar.awaiting_2fa"));
    }

    /**
     * Send rate limited action bar
     */
    public void sendRateLimitedActionBar(ServerPlayer player, int secondsRemaining) {
        LangManager lang = LangManager.getInstance();
        String message = lang.get("vouch.ui.actionbar.rate_limited", "time", secondsRemaining);
        sendActionBar(player, message);
    }

    /**
     * Create and show a boss bar countdown for a player
     */
    public void showCountdownBossBar(ServerPlayer player, int totalSeconds) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useBossBar())
            return;

        UUID uuid = player.getUUID();

        removeCountdownBossBar(player);

        LangManager lang = LangManager.getInstance();
        ServerBossEvent bossBar = new ServerBossEvent(
                UUID.randomUUID(),
                formatText(lang.get("vouch.ui.bossbar.text", "time", totalSeconds)),
                parseBossBarColor(config.getBossBarColor()),
                parseBossBarStyle(config.getBossBarStyle()));
        bossBar.setProgress(1.0f);
        bossBar.addPlayer(player);

        activeBossBars.put(uuid, bossBar);
        LOGGER.debug("BossBar created for player {}", player.getName().getString());
    }

    /**
     * Update the boss bar countdown
     */
    public void updateCountdownBossBar(ServerPlayer player, int secondsRemaining, int totalSeconds) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useBossBar())
            return;

        UUID uuid = player.getUUID();
        ServerBossEvent bossBar = activeBossBars.get(uuid);

        if (bossBar == null)
            return;

        LangManager lang = LangManager.getInstance();
        String text = lang.get("vouch.ui.bossbar.text", "time", secondsRemaining);
        bossBar.setName(formatText(text));

        float progress = totalSeconds > 0 ? (float) secondsRemaining / totalSeconds : 0f;
        bossBar.setProgress(Math.max(0f, Math.min(1f, progress)));

        if (secondsRemaining <= 10) {
            bossBar.setColor(BossEvent.BossBarColor.RED);
        } else if (secondsRemaining <= 20) {
            bossBar.setColor(BossEvent.BossBarColor.YELLOW);
        }
    }

    /**
     * Remove the countdown boss bar for a player
     */
    public void removeCountdownBossBar(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ServerBossEvent bossBar = activeBossBars.remove(uuid);

        if (bossBar != null) {
            bossBar.removePlayer(player);
            LOGGER.debug("BossBar removed for player {}", player.getName().getString());
        }
    }

    /**
     * Remove boss bar by UUID (for disconnected players)
     */
    public void removeCountdownBossBar(UUID uuid) {
        ServerBossEvent bossBar = activeBossBars.remove(uuid);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
        }
    }

    private BossEvent.BossBarColor parseBossBarColor(String color) {
        try {
            return BossEvent.BossBarColor.valueOf(color.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossEvent.BossBarColor.YELLOW;
        }
    }

    private BossEvent.BossBarOverlay parseBossBarStyle(String style) {
        try {
            return BossEvent.BossBarOverlay.valueOf(style.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossEvent.BossBarOverlay.PROGRESS;
        }
    }

    /**
     * Play a sound for the player
     */
    public void playSound(ServerPlayer player, String soundId) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        if (!config.useSounds())
            return;

        try {
            Identifier id = Identifier.parse(soundId);
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(id);

            if (sound != null) {
                // Sent to this player only; Entity#playSound would broadcast to everyone nearby.
                player.connection.send(new ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        SoundSource.MASTER,
                        player.getX(), player.getY(), player.getZ(),
                        config.getSoundVolume(),
                        config.getSoundPitch(),
                        player.getRandom().nextLong()));
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
    public void playLoginSuccessSound(ServerPlayer player) {
        playSound(player, VouchConfigManager.getInstance().getSoundLoginSuccess());
    }

    /**
     * Play register success sound
     */
    public void playRegisterSuccessSound(ServerPlayer player) {
        playSound(player, VouchConfigManager.getInstance().getSoundRegisterSuccess());
    }

    /**
     * Play wrong password sound
     */
    public void playWrongPasswordSound(ServerPlayer player) {
        playSound(player, VouchConfigManager.getInstance().getSoundWrongPassword());
    }

    /**
     * Play auth timeout sound
     */
    public void playAuthTimeoutSound(ServerPlayer player) {
        playSound(player, VouchConfigManager.getInstance().getSoundAuthTimeout());
    }

    /**
     * Play rate limited sound
     */
    public void playRateLimitedSound(ServerPlayer player) {
        playSound(player, VouchConfigManager.getInstance().getSoundRateLimited());
    }

    /**
     * Format a string with color codes and return as Text
     */
    public Component formatText(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
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
    public void sendMessage(ServerPlayer player, String message) {
        VouchConfigManager config = VouchConfigManager.getInstance();
        String prefixed = config.getBrandingPrefix() + message;
        player.sendSystemMessage(formatText(prefixed));
    }

    /**
     * Send a raw formatted message (no prefix)
     */
    public void sendRawMessage(ServerPlayer player, String message) {
        player.sendSystemMessage(formatText(message));
    }

    /**
     * Send welcome message for unregistered player
     */
    public void sendWelcomeMessageUnregistered(ServerPlayer player) {
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
    public void sendWelcomeMessageRegistered(ServerPlayer player) {
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
    private void clearChat(ServerPlayer player) {
        for (int i = 0; i < 100; i++) {
            player.sendSystemMessage(Component.empty());
        }
    }

    private void addPadding(ServerPlayer player, int lines) {
        for (int i = 0; i < lines; i++) {
            player.sendSystemMessage(Component.empty());
        }
    }

    /**
     * Parse Minecraft color codes (§ or &) and return Text
     */
    private Component parseColorCodes(String input) {
        // Replace & with § for compatibility
        String normalized = input.replace('&', '\u00A7');

        MutableComponent result = Component.empty();
        StringBuilder current = new StringBuilder();
        ChatFormatting currentFormat = null;
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
                    MutableComponent segment = Component.literal(current.toString());
                    if (currentFormat != null)
                        segment.withStyle(currentFormat);
                    if (isBold)
                        segment.withStyle(ChatFormatting.BOLD);
                    if (isItalic)
                        segment.withStyle(ChatFormatting.ITALIC);
                    if (isUnderline)
                        segment.withStyle(ChatFormatting.UNDERLINE);
                    if (isStrikethrough)
                        segment.withStyle(ChatFormatting.STRIKETHROUGH);
                    if (isObfuscated)
                        segment.withStyle(ChatFormatting.OBFUSCATED);
                    result.append(segment);
                    current = new StringBuilder();
                }

                char code = Character.toLowerCase(normalized.charAt(++i));
                ChatFormatting fmt = getFormattingByCode(code);

                if (fmt != null) {
                    if (fmt == ChatFormatting.RESET) {
                        currentFormat = null;
                        isBold = false;
                        isItalic = false;
                        isUnderline = false;
                        isStrikethrough = false;
                        isObfuscated = false;
                    } else if (fmt == ChatFormatting.BOLD) {
                        isBold = true;
                    } else if (fmt == ChatFormatting.ITALIC) {
                        isItalic = true;
                    } else if (fmt == ChatFormatting.UNDERLINE) {
                        isUnderline = true;
                    } else if (fmt == ChatFormatting.STRIKETHROUGH) {
                        isStrikethrough = true;
                    } else if (fmt == ChatFormatting.OBFUSCATED) {
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
            MutableComponent segment = Component.literal(current.toString());
            if (currentFormat != null)
                segment.withStyle(currentFormat);
            if (isBold)
                segment.withStyle(ChatFormatting.BOLD);
            if (isItalic)
                segment.withStyle(ChatFormatting.ITALIC);
            if (isUnderline)
                segment.withStyle(ChatFormatting.UNDERLINE);
            if (isStrikethrough)
                segment.withStyle(ChatFormatting.STRIKETHROUGH);
            if (isObfuscated)
                segment.withStyle(ChatFormatting.OBFUSCATED);
            result.append(segment);
        }

        return result;
    }

    private ChatFormatting getFormattingByCode(char code) {
        return switch (code) {
            case '0' -> ChatFormatting.BLACK;
            case '1' -> ChatFormatting.DARK_BLUE;
            case '2' -> ChatFormatting.DARK_GREEN;
            case '3' -> ChatFormatting.DARK_AQUA;
            case '4' -> ChatFormatting.DARK_RED;
            case '5' -> ChatFormatting.DARK_PURPLE;
            case '6' -> ChatFormatting.GOLD;
            case '7' -> ChatFormatting.GRAY;
            case '8' -> ChatFormatting.DARK_GRAY;
            case '9' -> ChatFormatting.BLUE;
            case 'a' -> ChatFormatting.GREEN;
            case 'b' -> ChatFormatting.AQUA;
            case 'c' -> ChatFormatting.RED;
            case 'd' -> ChatFormatting.LIGHT_PURPLE;
            case 'e' -> ChatFormatting.YELLOW;
            case 'f' -> ChatFormatting.WHITE;
            case 'k' -> ChatFormatting.OBFUSCATED;
            case 'l' -> ChatFormatting.BOLD;
            case 'm' -> ChatFormatting.STRIKETHROUGH;
            case 'n' -> ChatFormatting.UNDERLINE;
            case 'o' -> ChatFormatting.ITALIC;
            case 'r' -> ChatFormatting.RESET;
            default -> null;
        };
    }

    /**
     * Send complete login success feedback (title + sound + message)
     */
    public void onLoginSuccess(ServerPlayer player) {
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
    public void onRegisterSuccess(ServerPlayer player) {
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
    public void onWrongPassword(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();
        sendWrongPasswordTitle(player);
        playWrongPasswordSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.login.wrong_password"));
    }

    /**
     * Send session restored feedback
     */
    public void onSessionRestored(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();
        sendLoginSuccessTitle(player);
        playLoginSuccessSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.session_restored"));
    }

    /**
     * Send premium auto-login success feedback
     */
    public void onPremiumAutoLogin(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();
        sendTitle(player,
                lang.getRaw("vouch.ui.title.premium_login"),
                lang.getRaw("vouch.ui.subtitle.premium_login"));
        playLoginSuccessSound(player);
        sendMessage(player, lang.getRaw("vouch.auth.premium.auto_login"));
    }

    /**
     * Send premium verified but 2FA required feedback
     */
    public void onPremiumRequires2FA(ServerPlayer player) {
        LangManager lang = LangManager.getInstance();

        VouchConfigManager config = VouchConfigManager.getInstance();
        if (config.clearChatOnJoin()) {
            clearChat(player);
        }
        addPadding(player, config.getWelcomeMessagePadding());

        sendTitle(player,
                lang.getRaw("vouch.ui.title.premium_login"),
                lang.getRaw("vouch.ui.subtitle.premium_2fa"));
        sendMessage(player, lang.getRaw("vouch.auth.premium.require_2fa"));
    }

    /**
     * Clear action bar
     */
    private void clearActionBar(ServerPlayer player) {
        sendPacket(player, new ClientboundSetActionBarTextPacket(Component.empty()));
    }

    /**
     * Cleanup all UX elements for a player (on disconnect)
     */
    public void cleanupPlayer(UUID uuid) {
        removeCountdownBossBar(uuid);
    }

    public void shutdown() {
        for (ServerBossEvent bar : activeBossBars.values()) {
            bar.removeAllPlayers();
        }
        activeBossBars.clear();
        LOGGER.debug("UXManager shutdown complete");
    }
}
