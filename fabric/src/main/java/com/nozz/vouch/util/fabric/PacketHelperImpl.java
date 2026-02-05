package com.nozz.vouch.util.fabric;

import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Fabric implementation of PacketHelper.
 * Uses Yarn mappings: sendPacket
 */
public final class PacketHelperImpl {

    private PacketHelperImpl() {
    }

    /**
     * Send a packet using Fabric/Yarn mappings.
     */
    public static void sendPacket(ServerPlayerEntity player, Packet<?> packet) {
        player.networkHandler.sendPacket(packet);
    }
}
