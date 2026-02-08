package com.nozz.vouch.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.nozz.vouch.util.PacketHelper.sendPacket;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders QR codes onto Minecraft maps for TOTP 2FA setup.
 * 
 * The QR code is sent directly via packets to the player without
 * saving any files on disk. This provides a secure, immersive UX
 * for linking authenticator apps.
 * 
 * Handles inventory preservation - saves the original item in the player's
 * main hand slot before placing the QR map, and restores it when the map
 * is removed.
 */
public final class QRMapRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/QRMapRenderer");

    // Minecraft map dimensions (128x128 pixels)
    private static final int MAP_SIZE = 128;

    // Map color IDs (vanilla palette)
    // White = 34 (Quartz block color, base id * 4 + brightness)
    // Black = 119 (Coal block color)
    private static final byte COLOR_WHITE = (byte) 34;  // SNOW base color, brightest shade
    private static final byte COLOR_BLACK = (byte) 119; // COLOR_BLACK base color

    // QR code generation settings
    private static final int QR_SIZE = 116;  // Leave some margin
    private static final int QR_MARGIN = (MAP_SIZE - QR_SIZE) / 2;

    // Track original items that were in players' main hand slots before QR map was given
    // This allows restoration when the QR map is removed (on auth success, timeout, or disconnect)
    private static final Map<UUID, SavedInventorySlot> savedSlots = new ConcurrentHashMap<>();

    /**
     * Represents a saved inventory slot state for restoration
     */
    private record SavedInventorySlot(int slotIndex, ItemStack originalItem) {}

    private QRMapRenderer() {
    }

    /**
     * Generate a QR code from an otpauth:// URI and send it to the player as a map.
     * 
     * @param player The player to receive the QR map
     * @param otpAuthUri The otpauth:// URI to encode
     * @return true if QR was generated and sent successfully
     */
    public static boolean sendQRCodeMap(ServerPlayerEntity player, String otpAuthUri) {
        try {
            BitMatrix qrMatrix = generateQRCode(otpAuthUri);
            if (qrMatrix == null) {
                LOGGER.error("Failed to generate QR code for player {}", player.getName().getString());
                return false;
            }

            byte[] mapColors = convertToMapColors(qrMatrix);

            sendVirtualMap(player, mapColors);

            LOGGER.debug("QR code map sent to player {}", player.getName().getString());
            return true;

        } catch (Exception e) {
            LOGGER.error("Error sending QR map to player {}", player.getName().getString(), e);
            return false;
        }
    }

    /**
     * Generate a QR code BitMatrix from the given content
     */
    private static BitMatrix generateQRCode(String content) {
        try {
            QRCodeWriter writer = new QRCodeWriter();

            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            return writer.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);

        } catch (WriterException e) {
            LOGGER.error("Failed to encode QR code", e);
            return null;
        }
    }

    /**
     * Convert a QR BitMatrix to Minecraft map color bytes
     */
    private static byte[] convertToMapColors(BitMatrix qrMatrix) {
        byte[] colors = new byte[MAP_SIZE * MAP_SIZE];

        for (int i = 0; i < colors.length; i++) {
            colors[i] = COLOR_WHITE;
        }

        int qrWidth = qrMatrix.getWidth();
        int qrHeight = qrMatrix.getHeight();

        for (int y = 0; y < qrHeight && y < QR_SIZE; y++) {
            for (int x = 0; x < qrWidth && x < QR_SIZE; x++) {
                if (qrMatrix.get(x, y)) {
                    int mapX = QR_MARGIN + x;
                    int mapY = QR_MARGIN + y;

                    if (mapX < MAP_SIZE && mapY < MAP_SIZE) {
                        colors[mapY * MAP_SIZE + mapX] = COLOR_BLACK;
                    }
                }
            }
        }

        return colors;
    }

    /**
     * Send a virtual map to the player with the QR code data.
     * Uses a high map ID to avoid conflicts with real maps.
     * 
     * This method saves the player's current main hand item (if any) before
     * placing the QR map, so it can be restored later.
     */
    private static void sendVirtualMap(ServerPlayerEntity player, byte[] colors) {
        UUID uuid = player.getUuid();
        
        int virtualMapId = 32767 - player.getId();

        ItemStack mapStack = new ItemStack(Items.FILLED_MAP);
        mapStack.set(DataComponentTypes.MAP_ID, new MapIdComponent(virtualMapId));

        MapUpdateS2CPacket mapPacket = new MapUpdateS2CPacket(
                new MapIdComponent(virtualMapId),
                (byte) 0,
                true,
                Optional.empty(),
                Optional.of(new MapState.UpdateData(0, 0, MAP_SIZE, MAP_SIZE, colors))
        );

        sendPacket(player, mapPacket);

        int selectedSlot = player.getInventory().getSelectedSlot();
        ItemStack originalItem = player.getMainHandStack();
   
        if (!savedSlots.containsKey(uuid)) {
            savedSlots.put(uuid, new SavedInventorySlot(selectedSlot, originalItem.copy()));
            LOGGER.debug("Saved original item from slot {} for player {}", selectedSlot, player.getName().getString());
        }

        player.getInventory().setStack(selectedSlot, mapStack);

        LOGGER.debug("Virtual map {} sent to player {}", virtualMapId, player.getName().getString());
    }

    /**
     * Remove any QR map items from a player's inventory and restore original item.
     * Called after 2FA setup is complete, cancelled, or on player disconnect.
     */
    public static void removeQRMap(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        int virtualMapId = 32767 - player.getId();
        var inventory = player.getInventory();

        boolean mapRemoved = false;
        int mapSlot = -1;
        
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(Items.FILLED_MAP)) {
                MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
                if (mapId != null && mapId.id() == virtualMapId) {
                    inventory.setStack(i, ItemStack.EMPTY);
                    mapSlot = i;
                    mapRemoved = true;
                    LOGGER.debug("Removed QR map from player {} slot {}", player.getName().getString(), i);
                    break;
                }
            }
        }

        SavedInventorySlot saved = savedSlots.remove(uuid);
        if (saved != null && mapRemoved && mapSlot == saved.slotIndex) {
            inventory.setStack(saved.slotIndex, saved.originalItem);
            LOGGER.debug("Restored original item to slot {} for player {}", saved.slotIndex, player.getName().getString());
        } else if (saved != null) {
            if (!saved.originalItem.isEmpty()) {
                if (!inventory.insertStack(saved.originalItem)) {
                    player.dropItem(saved.originalItem, false);
                    LOGGER.debug("Dropped original item for player {} (inventory full)", player.getName().getString());
                } else {
                    LOGGER.debug("Inserted original item back to inventory for player {}", player.getName().getString());
                }
            }
        }
    }

    /**
     * Cleanup when a player disconnects.
     * Removes tracking data for the player.
     * The actual QR map removal should be handled before calling this
     * (e.g., in PreAuthManager.onPlayerDisconnect).
     */
    public static void onPlayerDisconnect(UUID uuid) {
        savedSlots.remove(uuid);
    }

    /**
     * Check if a player currently has a QR map.
     */
    public static boolean hasQRMap(UUID uuid) {
        return savedSlots.containsKey(uuid);
    }
}
