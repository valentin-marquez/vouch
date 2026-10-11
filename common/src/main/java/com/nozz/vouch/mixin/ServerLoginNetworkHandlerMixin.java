package com.nozz.vouch.mixin;

import com.mojang.authlib.GameProfile;
import com.nozz.vouch.auth.MixedModeLoginHandler;
import com.nozz.vouch.config.VouchConfigManager;
import com.nozz.vouch.db.DatabaseManager;
import com.nozz.vouch.db.PremiumOverrideStatus;
import com.nozz.vouch.util.PremiumVerifier;
import com.nozz.vouch.util.PremiumVerifier.PremiumLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mixin for mixed online/offline mode (premium auto-login).
 *
 * <p>On an offline-mode server with {@code premium_auto_login} enabled, this
 * intercepts the login handshake and forces encryption for players whose
 * usernames belong to premium accounts, so their ownership of the account is
 * proved cryptographically and they receive their real online UUID. Everyone
 * else continues through the ordinary offline flow.
 *
 * <h2>Threading</h2>
 * Both injected handlers run on a netty event loop that is shared by every
 * connection on the server, so nothing here may block. All the decision-making
 * lives in {@link MixedModeLoginHandler} and {@link PremiumVerifier} and comes
 * back as a future; this class only dispatches the answer. {@code startClientVerification}
 * is called from the continuation, off the event loop, which is where vanilla
 * calls it from too (its {@code "User Authenticator #n"} thread); disconnects
 * are handed to the server thread, as vanilla's own login timeout does.
 *
 * <h2>Failing closed</h2>
 * When Mojang cannot be reached, the player is disconnected rather than let in
 * offline. Letting them in would give a premium player an offline UUID — a
 * different world save — and would leave their username claimable by a cracked
 * client for as long as the outage lasts. Operators who would rather keep a
 * cracked population joining through an outage can set
 * {@code premium_offline_by_default}, which skips the Mojang lookup entirely.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetworkHandlerMixin {

    /**
     * Deadline for the whole pre-handshake decision (database lookup plus, if
     * needed, the Mojang profile lookup). Vanilla drops a login after 600 ticks
     * (30 seconds); this leaves room for the handshake that follows.
     */
    @Unique
    private static final long VOUCH$HELLO_DEADLINE_MILLIS = 12_000L;

    @Shadow
    @Final
    MinecraftServer server;

    @Shadow
    @Final
    Connection connection;

    @Shadow
    String requestedUsername;

    @Shadow
    abstract void startClientVerification(GameProfile profile);

    @Shadow
    @Final
    static Logger LOGGER;

    /**
     * Whether Vouch challenged this connection for encryption. Written from a
     * netty thread and read from the HTTP executor's continuations, hence volatile.
     */
    @Unique
    private volatile boolean vouch$forcedEncryption = false;

    /**
     * Whether this connection has already had a hello packet handled by Vouch.
     *
     * <p>Vanilla's {@code onHello} opens with
     * {@code Validate.validState(this.state == State.HELLO)}; cancelling at HEAD
     * removes that guard, so without a replacement one socket could send hello
     * repeatedly and spawn a database query, a Mojang request, a keypair and an
     * outbound packet each time.
     */
    @Unique
    private volatile boolean vouch$helloHandled = false;

    /**
     * Decide, before the login proper, whether this username should be made to
     * prove it owns a Mojang account.
     */
    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void vouch$onHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        // Online-mode servers already do all of this; leave them alone.
        if (server.usesAuthentication()) {
            return;
        }

        VouchConfigManager config;
        try {
            config = VouchConfigManager.getInstance();
        } catch (IllegalStateException e) {
            // Config not initialised yet — let vanilla handle the login.
            return;
        }

        if (!config.isPremiumAutoLogin()) {
            return;
        }

        String username = packet.name();

        // Anything vanilla would reject, let vanilla reject: its own
        // Validate.validState runs as soon as we decline to cancel.
        if (username == null || !username.matches("[a-zA-Z0-9_]{3,16}")) {
            return;
        }

        // Replay guard, standing in for the vanilla state check we cancel past.
        // Packets for one channel are serialised on its event loop, so this
        // check-then-set cannot interleave with itself.
        if (vouch$helloHandled) {
            ci.cancel();
            LOGGER.warn("Ignoring repeated hello packet from {}", connection.getRemoteAddress());
            vouch$disconnect(vouch$unexpectedHello());
            return;
        }
        vouch$helloHandled = true;

        // From here on the login is ours.
        ci.cancel();
        this.requestedUsername = username;

        vouch$decidePremium(config, username)
                .orTimeout(VOUCH$HELLO_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
                .thenAccept(outcome -> vouch$applyHelloDecision(username, outcome))
                .exceptionally(throwable -> {
                    LOGGER.warn("Premium check for {} did not complete ({}) — failing closed",
                            username, throwable.toString());
                    vouch$disconnect(vouch$authUnavailable());
                    return null;
                });
    }

    /**
     * Resolve the admin override first, and only ask Mojang when there is no
     * override and the server is not configured to assume offline.
     */
    @Unique
    private CompletableFuture<PremiumLookup> vouch$decidePremium(VouchConfigManager config, String username) {
        return DatabaseManager.getInstance().getPremiumOverrideStatus(username).thenCompose(status -> {
            if (status == PremiumOverrideStatus.FORCED_OFFLINE) {
                LOGGER.debug("Player {} is forced-offline by admin, skipping premium check", username);
                return CompletableFuture.completedFuture(PremiumLookup.NOT_PREMIUM);
            }
            if (status == PremiumOverrideStatus.MARKED_ONLINE) {
                LOGGER.debug("Player {} is marked-online, will send encryption challenge", username);
                return CompletableFuture.completedFuture(PremiumLookup.PREMIUM);
            }
            if (config.isPremiumOfflineByDefault()) {
                LOGGER.debug("Player {} has no override and offline-by-default is enabled, treating as offline",
                        username);
                return CompletableFuture.completedFuture(PremiumLookup.NOT_PREMIUM);
            }
            return PremiumVerifier.getInstance().lookupPremiumUsername(username);
        });
    }

    @Unique
    private void vouch$applyHelloDecision(String username, PremiumLookup outcome) {
        if (!connection.isConnected()) {
            return;
        }

        switch (outcome) {
            case PREMIUM -> {
                LOGGER.info("Username {} is premium, initiating encryption for mixed-mode login", username);
                vouch$forcedEncryption = true;
                boolean sent = MixedModeLoginHandler.getInstance()
                        .initiatePremiumVerification(connection, username, server.getKeyPair());
                if (!sent) {
                    vouch$forcedEncryption = false;
                    vouch$disconnect(vouch$serverBusy());
                }
            }
            case NOT_PREMIUM -> {
                LOGGER.debug("Username {} is not premium, proceeding with offline login", username);
                startClientVerification(UUIDUtil.createOfflineProfile(username));
            }
            case UNAVAILABLE -> {
                // Never downgrade to an offline profile on an unknown answer:
                // that hands a premium player's name to whoever asks next.
                LOGGER.warn("Could not determine whether {} is premium — refusing the login", username);
                vouch$disconnect(vouch$authUnavailable());
            }
        }
    }

    /**
     * Handle the client's answer to the encryption challenge we sent.
     */
    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void vouch$onKey(ServerboundKeyPacket packet, CallbackInfo ci) {
        if (!vouch$forcedEncryption) {
            // Not our handshake. Vanilla's own state check rejects a key packet
            // that was never asked for.
            return;
        }

        ci.cancel();
        // Consume the flag immediately: a second key packet on this connection
        // then falls through to vanilla, which rejects it.
        vouch$forcedEncryption = false;

        String username = requestedUsername;

        MixedModeLoginHandler.getInstance()
                .handleEncryptionResponse(connection, packet)
                .thenAccept(result -> {
                    if (!connection.isConnected()) {
                        return;
                    }
                    switch (result.outcome()) {
                        case VERIFIED -> {
                            GameProfile profile = result.profile().orElseThrow();
                            LOGGER.info("Premium auto-login: {} verified as {} (UUID: {})",
                                    username, profile.name(), profile.id());
                            startClientVerification(profile);
                        }
                        case REJECTED -> {
                            LOGGER.warn("Premium verification failed for {}, disconnecting", username);
                            vouch$disconnect(vouch$authFailed());
                        }
                        case UNAVAILABLE -> {
                            LOGGER.warn("Could not verify {} with Mojang, disconnecting", username);
                            vouch$disconnect(vouch$authUnavailable());
                        }
                    }
                })
                .exceptionally(throwable -> {
                    LOGGER.warn("Premium verification for {} completed exceptionally: {}",
                            username, throwable.toString());
                    vouch$disconnect(vouch$authUnavailable());
                    return null;
                });
    }

    /**
     * Release the pending encryption challenge when the connection goes away.
     *
     * <p>Without this, every client that takes a challenge and then drops —
     * a TCP reset, a crash, or a cracked client that picked a premium username
     * and cannot answer — leaves an entry holding an RSA key pair behind. That
     * is remotely triggerable and unbounded.
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void vouch$onDisconnected(DisconnectionDetails info, CallbackInfo ci) {
        MixedModeLoginHandler.getInstance().cleanup(connection);
    }

    /** Vanilla's own key, so every client already has a translation for it. */
    @Unique
    private static Component vouch$authFailed() {
        return Component.translatable("multiplayer.disconnect.unverified_username");
    }

    /** Vanilla's own key, so every client already has a translation for it. */
    @Unique
    private static Component vouch$authUnavailable() {
        return Component.translatable("multiplayer.disconnect.authservers_down");
    }

    @Unique
    private static Component vouch$unexpectedHello() {
        return Component.literal("Unexpected hello packet");
    }

    @Unique
    private static Component vouch$serverBusy() {
        return Component.literal("Too many logins in progress, please try again in a moment.");
    }

    /**
     * Disconnect from the server thread.
     *
     * <p>{@code ClientConnection#disconnect} ends in
     * {@code channel.close().awaitUninterruptibly()}, so it blocks whichever
     * thread calls it. Some of our callers are threads we do not own — the JVM
     * -wide timer thread behind {@code orTimeout} in particular — so the call is
     * handed to the server thread, which is exactly where vanilla's own
     * {@code slow_login} timeout disconnects from.
     */
    @Unique
    private void vouch$disconnect(Component reason) {
        server.execute(() -> ((ServerLoginPacketListenerImpl) (Object) this).disconnect(reason));
    }
}
