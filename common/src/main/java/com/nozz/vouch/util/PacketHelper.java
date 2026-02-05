package com.nozz.vouch.util;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Platform-agnostic packet sending utility.
 * 
 * Handles differences between Fabric (Yarn mappings: sendPacket)
 * and NeoForge (Mojang mappings: send).
 */
public final class PacketHelper {

    private PacketHelper() {
    }

    /**
     * Send a packet to a player.
     * Implementation is platform-specific due to mapping differences.
     *
     * @param player The player to send the packet to
     * @param packet The packet to send
     */
    @ExpectPlatform
    public static void sendPacket(ServerPlayerEntity player, Packet<?> packet) {
        throw new AssertionError("Platform implementation not loaded");
    }
}
