package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to make unauthenticated players invulnerable.
 * 
 * Prevents damage from all sources while in pre-auth jail.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class EntityDamageMixin {

    /**
     * Block all damage to unauthenticated players
     */
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void vouch$onDamage(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        
        if (!AuthManager.getInstance().isAuthenticated(self)) {
            // Player is invulnerable while in jail
            cir.setReturnValue(false);
        }
    }
}
