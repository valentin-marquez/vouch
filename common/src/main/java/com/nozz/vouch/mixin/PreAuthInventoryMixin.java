package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps an unauthenticated player's inventory inert.
 *
 * <p>{@code PlayerInteractionMixin} only covers block breaking, block use and item use,
 * so slot clicks, item drops, creative slot writes, crafting and container buttons all
 * went through while a player sat in the jail. Every one of those is an inventory write
 * performed by an unverified identity, so they are all cancelled here.
 *
 * <h2>Threading</h2>
 * These handlers call {@code NetworkThreadUtils.forceMainThread(...)} as their first
 * statement, which means an {@code @At("HEAD")} injection runs <b>on the Netty I/O
 * thread</b> the first time round (the packet is only rescheduled onto the main thread
 * once {@code forceMainThread} throws). The check itself is therefore limited to
 * {@link AuthManager}'s concurrent maps, and the screen-handler resync — which does
 * touch game state — is marshalled onto the main thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PreAuthInventoryMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Block inventory/container slot clicks (moving, stacking, dropping via the screen).
     */
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void vouch$onClickSlot(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block creative-mode slot writes (an item spawn and a drop vector in one packet).
     */
    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void vouch$onCreativeInventoryAction(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block recipe-book crafting requests, which fill input slots server-side.
     */
    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void vouch$onCraftRequest(ServerboundPlaceRecipePacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block container buttons (enchanting table, lectern, stonecutter, loom, …).
     */
    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void vouch$onButtonClick(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block creative middle-click pick-block, which writes to the hotbar.
     */
    @Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void vouch$onPickFromBlock(ServerboundPickItemFromBlockPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
    private void vouch$onPickFromEntity(ServerboundPickItemFromEntityPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block item drops and offhand swaps.
     *
     * <p>Only the inventory-mutating actions are cancelled here; block-breaking actions
     * keep their vanilla path so the client's break prediction stays acknowledged —
     * {@code PlayerInteractionMixin} already refuses the break itself.
     */
    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void vouch$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action != ServerboundPlayerActionPacket.Action.DROP_ITEM
                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                && action != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            return;
        }
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * @return {@code true} when the packet must be cancelled. Safe to call off-thread;
     * schedules a client resync on the main thread so the cancelled action does not
     * leave the player's screen desynced.
     */
    @Unique
    private boolean vouch$denyInventoryAction() {
        ServerPlayer currentPlayer = this.player;
        if (currentPlayer == null || AuthManager.getInstance().isAuthenticated(currentPlayer)) {
            return false;
        }

        MinecraftServer server = currentPlayer.level().getServer();
        if (server != null) {
            server.execute(() -> {
                if (!currentPlayer.hasDisconnected() && currentPlayer.containerMenu != null) {
                    currentPlayer.containerMenu.sendAllDataToRemote();
                }
            });
        }
        return true;
    }
}
