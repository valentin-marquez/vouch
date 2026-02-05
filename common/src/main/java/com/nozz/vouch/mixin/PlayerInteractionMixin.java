package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to block all player interactions while in pre-auth jail.
 * 
 * Blocks:
 * - Block breaking/placing
 * - Item usage
 * - Entity interactions
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class PlayerInteractionMixin {

    @Shadow
    @Final
    protected ServerPlayerEntity player;

    /**
     * Block block breaking for unauthenticated players
     */
    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    private void vouch$onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Block item usage for unauthenticated players
     */
    @Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
    private void vouch$onInteractItem(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    /**
     * Block block interaction for unauthenticated players
     */
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void vouch$onInteractBlock(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, net.minecraft.util.hit.BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}
