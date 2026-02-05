package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.util.Messages;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
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
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    /**
     * Intercept chat messages and block if player is not authenticated
     */
    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void vouch$onChatMessage(ChatMessageC2SPacket packet, CallbackInfo ci) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            // Block chat messages for unauthenticated players
            player.sendMessage(Messages.chatBlocked(), false);
            ci.cancel();
        }
    }
}
