package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PickFromInventoryC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
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
@Mixin(ServerPlayNetworkHandler.class)
public abstract class PreAuthInventoryMixin {

    @Shadow
    public ServerPlayerEntity player;

    /**
     * Block inventory/container slot clicks (moving, stacking, dropping via the screen).
     */
    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void vouch$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block creative-mode slot writes (an item spawn and a drop vector in one packet).
     */
    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void vouch$onCreativeInventoryAction(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block recipe-book crafting requests, which fill input slots server-side.
     */
    @Inject(method = "onCraftRequest", at = @At("HEAD"), cancellable = true)
    private void vouch$onCraftRequest(CraftRequestC2SPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block container buttons (enchanting table, lectern, stonecutter, loom, …).
     */
    @Inject(method = "onButtonClick", at = @At("HEAD"), cancellable = true)
    private void vouch$onButtonClick(ButtonClickC2SPacket packet, CallbackInfo ci) {
        if (vouch$denyInventoryAction()) {
            ci.cancel();
        }
    }

    /**
     * Block creative middle-click pick-block, which writes to the hotbar.
     */
    @Inject(method = "onPickFromInventory", at = @At("HEAD"), cancellable = true)
    private void vouch$onPickFromInventory(PickFromInventoryC2SPacket packet, CallbackInfo ci) {
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
    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void vouch$onPlayerAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        PlayerActionC2SPacket.Action action = packet.getAction();
        if (action != PlayerActionC2SPacket.Action.DROP_ITEM
                && action != PlayerActionC2SPacket.Action.DROP_ALL_ITEMS
                && action != PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
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
        ServerPlayerEntity currentPlayer = this.player;
        if (currentPlayer == null || AuthManager.getInstance().isAuthenticated(currentPlayer)) {
            return false;
        }

        MinecraftServer server = currentPlayer.getServer();
        if (server != null) {
            server.execute(() -> {
                if (!currentPlayer.isDisconnected() && currentPlayer.currentScreenHandler != null) {
                    currentPlayer.currentScreenHandler.syncState();
                }
            });
        }
        return true;
    }
}
