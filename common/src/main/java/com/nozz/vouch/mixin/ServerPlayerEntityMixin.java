package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to freeze player movement while in pre-auth jail.
 * 
 * Players cannot move, look around, or change position until authenticated.
 * This is a server-side only restriction.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {

    @Unique
    private Vec3 vouch$jailPosition;

    @Unique
    private float vouch$jailYaw;

    @Unique
    private float vouch$jailPitch;

    /**
     * Capture the player's position when they first join (before tick processing)
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void vouch$onTickStart(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self)) {
            // Capture initial jail position if not set
            if (vouch$jailPosition == null) {
                vouch$jailPosition = self.position();
                vouch$jailYaw = self.getYRot();
                vouch$jailPitch = self.getXRot();
            }
        } else {
            // Clear jail position once authenticated
            vouch$jailPosition = null;
        }
    }

    /**
     * Reset position at the end of tick if player is not authenticated
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void vouch$onTickEnd(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self) && vouch$jailPosition != null) {
            // Teleport back to jail position using requestTeleport
            self.teleportTo(
                vouch$jailPosition.x,
                vouch$jailPosition.y,
                vouch$jailPosition.z
            );
            
            // Reset head rotation
            self.setYRot(vouch$jailYaw);
            self.setXRot(vouch$jailPitch);
            
            // Cancel any velocity
            self.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Block player movement packets by resetting position on move
     */
    @Inject(method = "doTick", at = @At("HEAD"))
    private void vouch$onPlayerTick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self) && vouch$jailPosition != null) {
            // Ensure player stays in jail during player-specific tick
            self.fallDistance = 0.0f;
        }
    }
}
