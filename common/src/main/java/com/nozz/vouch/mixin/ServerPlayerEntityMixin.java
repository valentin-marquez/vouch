package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
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
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Unique
    private Vec3d vouch$jailPosition;

    @Unique
    private float vouch$jailYaw;

    @Unique
    private float vouch$jailPitch;

    /**
     * Capture the player's position when they first join (before tick processing)
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void vouch$onTickStart(CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self)) {
            // Capture initial jail position if not set
            if (vouch$jailPosition == null) {
                vouch$jailPosition = self.getPos();
                vouch$jailYaw = self.getYaw();
                vouch$jailPitch = self.getPitch();
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
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self) && vouch$jailPosition != null) {
            // Teleport back to jail position using requestTeleport
            self.requestTeleport(
                vouch$jailPosition.x,
                vouch$jailPosition.y,
                vouch$jailPosition.z
            );
            
            // Reset head rotation
            self.setYaw(vouch$jailYaw);
            self.setPitch(vouch$jailPitch);
            
            // Cancel any velocity
            self.setVelocity(Vec3d.ZERO);
        }
    }

    /**
     * Block player movement packets by resetting position on move
     */
    @Inject(method = "playerTick", at = @At("HEAD"))
    private void vouch$onPlayerTick(CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self) && vouch$jailPosition != null) {
            // Ensure player stays in jail during player-specific tick
            self.fallDistance = 0.0f;
        }
    }
}
