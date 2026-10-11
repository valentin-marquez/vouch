package com.nozz.vouch.util.neoforge;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

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
    public static void sendPacket(ServerPlayer player, Packet<?> packet) {
        player.connection.send(packet);
    }
}
