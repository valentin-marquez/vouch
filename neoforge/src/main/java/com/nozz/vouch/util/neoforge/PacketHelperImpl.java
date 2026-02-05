package com.nozz.vouch.util.neoforge;

import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * NeoForge implementation of PacketHelper.
 * Uses Mojang mappings: send
 */
public final class PacketHelperImpl {

    private PacketHelperImpl() {
    }

    /**
     * Send a packet using NeoForge/Mojang mappings.
     */
    public static void sendPacket(ServerPlayerEntity player, Packet<?> packet) {
        player.networkHandler.send(packet);
    }
}
