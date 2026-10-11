package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.util.Messages;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to block chat messages from unauthenticated players.
 * 
 * Only allows /login and /register commands to pass through.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercept chat messages and block if player is not authenticated
     */
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void vouch$onChatMessage(ServerboundChatPacket packet, CallbackInfo ci) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            // Block chat messages for unauthenticated players
            player.displayClientMessage(Messages.chatBlocked(), false);
            ci.cancel();
        }
    }
}
