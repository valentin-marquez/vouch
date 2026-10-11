package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerInteractionMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    /**
     * Block block breaking for unauthenticated players
     */
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void vouch$onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Block item usage for unauthenticated players
     */
    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void vouch$onInteractItem(ServerPlayer player, Level world, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    /**
     * Block block interaction for unauthenticated players
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void vouch$onInteractBlock(ServerPlayer player, Level world, ItemStack stack, InteractionHand hand, net.minecraft.world.phys.BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (!AuthManager.getInstance().isAuthenticated(player)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
