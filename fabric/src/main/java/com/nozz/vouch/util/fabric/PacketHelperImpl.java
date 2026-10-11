package com.nozz.vouch.util.fabric;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

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
    public static void sendPacket(ServerPlayer player, Packet<?> packet) {
        player.connection.send(packet);
    }
}
