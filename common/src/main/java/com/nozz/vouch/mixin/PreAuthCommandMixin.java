package com.nozz.vouch.mixin;

import com.nozz.vouch.auth.AuthManager;
import com.nozz.vouch.util.Messages;
import com.nozz.vouch.util.PreAuthCommandGate;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Closes the pre-auth jail's biggest hole: commands.
 *
 * <p>Since 1.19 commands do not travel on the chat packet, so blocking
 * {@code onChatMessage} leaves every command a player has permission for reachable
 * before they authenticate — including vanilla {@code /me}, {@code /msg}, {@code /tell}
 * and {@code /teammsg}, which defeat the chat block outright, and whatever a modpack
 * ships ({@code /tpa}, {@code /home}, {@code /back}, {@code /pay}, {@code /kit}, …).
 *
 * <p>Two entry points carry commands and both are gated here:
 * <ul>
 *   <li>{@code onCommandExecution} — the plain {@code CommandExecutionC2SPacket}</li>
 *   <li>{@code onChatCommandSigned} — {@code ChatCommandSignedC2SPacket}, used when the
 *       command takes signable (message) arguments</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <b>These injections run on the Netty I/O thread.</b> Vanilla's hop to the main thread
 * happens later, inside {@code validateMessage} ({@code this.server.execute(callback)}),
 * so at {@code HEAD} we are still off-thread. The gate therefore only touches
 * {@link PreAuthCommandGate} (pure) and {@link AuthManager}'s concurrent maps, and any
 * player feedback is marshalled onto the main thread before it is sent.
 *
 * <h2>Extension point</h2>
 * Every decision comes from {@link PreAuthCommandGate#evaluate(String)}. A later
 * typo-correction pass installs a {@link PreAuthCommandGate.Corrector} and its result
 * arrives here as {@link PreAuthCommandGate.Decision#suggestion()} — no change to this
 * mixin is required.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PreAuthCommandMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Gate unsigned command packets (the common case, e.g. {@code /login secret}).
     */
    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void vouch$gateCommandExecution(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (vouch$shouldBlock(packet.command())) {
            ci.cancel();
        }
    }

    /**
     * Gate signed command packets (commands carrying signable message arguments).
     */
    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void vouch$gateSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (vouch$shouldBlock(packet.command())) {
            ci.cancel();
        }
    }

    /**
     * @return {@code true} when the command must not run. Safe to call off-thread.
     */
    @Unique
    private boolean vouch$shouldBlock(String rawCommand) {
        ServerPlayer currentPlayer = this.player;
        if (currentPlayer == null || AuthManager.getInstance().isAuthenticated(currentPlayer)) {
            return false;
        }

        PreAuthCommandGate.Decision decision = PreAuthCommandGate.evaluate(rawCommand);
        if (decision.allowed()) {
            return false;
        }

        vouch$notifyBlocked(currentPlayer, decision.suggestion());
        return true;
    }

    /**
     * Marshal the "command blocked" feedback onto the main thread; we are on the Netty
     * I/O thread here.
     */
    @Unique
    private void vouch$notifyBlocked(ServerPlayer target, Optional<String> suggestion) {
        MinecraftServer server = target.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (target.hasDisconnected()) {
                return;
            }
            Component message = Messages.commandBlocked();
            if (suggestion.isPresent()) {
                message = Component.empty()
                        .append(message)
                        .append(Component.literal(" /" + suggestion.get()).withStyle(ChatFormatting.GRAY));
            }
            target.displayClientMessage(message, false);
        });
    }
}
