package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to make unauthenticated players invulnerable.
 * 
 * Prevents damage from all sources while in pre-auth jail.
 */
@Mixin(ServerPlayer.class)
public abstract class EntityDamageMixin {

    /**
     * Block all damage to unauthenticated players
     */
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void vouch$onDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self)) {
            // Player is invulnerable while in jail
            cir.setReturnValue(false);
        }
    }
}
