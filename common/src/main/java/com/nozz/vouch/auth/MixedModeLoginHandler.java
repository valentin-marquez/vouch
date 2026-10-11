package com.nozz.vouch.auth;

import com.mojang.authlib.GameProfile;
import com.nozz.vouch.util.PremiumVerifier;
import com.nozz.vouch.util.PremiumVerifier.PremiumLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Drives the mixed online/offline login handshake.
 *
 * <p>On an offline-mode server with {@code premium_auto_login} enabled, players
 * whose usernames belong to premium accounts are sent a real Mojang encryption
 * challenge. Answering it correctly proves account ownership and earns the
 * player their online UUID; everyone else continues through the ordinary
 * offline flow.
 *
 * <h2>Threading</h2>
 * Everything here is called from a netty event loop, and that loop is shared by
 * every connection on the server — {@code ServerNetworkIo} memoizes a single
 * {@code NioEventLoopGroup}. Blocking it does not delay one login, it stops
 * reads and writes for players already in the world. So the cheap, purely
 * local work (nonce check, secret decryption, server-id hash, enabling the
 * cipher) happens inline exactly as vanilla does it, and the Mojang round trip
 * is returned as a future the caller completes off-loop. Vanilla reaches the
 * same arrangement by spawning a {@code "User Authenticator #n"} thread, and
 * calls {@code startVerify} and {@code disconnect} from it, so completing this
 * future off the main thread is the vanilla-sanctioned path.
 */
public final class MixedModeLoginHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vouch/MixedModeLogin");

    /**
     * How long an unanswered encryption challenge is kept. Vanilla drops a
     * login that has not finished within 600 ticks (30 seconds), so nothing
     * legitimate ever outlives this.
     */
    public static final Duration PENDING_TTL = Duration.ofSeconds(30);

    /**
     * How long a successful verification is remembered so the join handler can
     * ask about it. Generous, because the configuration phase in between can
     * include a resource-pack download. Expiry is a safe failure: the player
     * simply falls back to password authentication.
     */
    public static final Duration VERIFIED_LOGIN_TTL = Duration.ofMinutes(5);

    /**
     * Ceilings on remotely-triggerable state. Far above any real concurrent
     * login count, but finite.
     */
    private static final int MAX_PENDING = 512;
    private static final int MAX_VERIFIED = 512;

    private static MixedModeLoginHandler instance;

    private final TtlStore<SocketAddress, PendingVerification> pendingVerifications;
    private final TtlStore<UUID, VerifiedLogin> verifiedLogins;

    MixedModeLoginHandler(LongSupplier clockMillis) {
        this.pendingVerifications = new TtlStore<>(MAX_PENDING, PENDING_TTL, clockMillis);
        this.verifiedLogins = new TtlStore<>(MAX_VERIFIED, VERIFIED_LOGIN_TTL, clockMillis);
    }

    public static synchronized MixedModeLoginHandler getInstance() {
        if (instance == null) {
            instance = new MixedModeLoginHandler(System::currentTimeMillis);
        }
        return instance;
    }

    /**
     * How a premium verification ended.
     */
    public enum Outcome {
        /** The client proved it owns the account. */
        VERIFIED,
        /** The client failed the challenge, or Mojang says it never authenticated. */
        REJECTED,
        /** Mojang could not be reached, so nothing was proved either way. */
        UNAVAILABLE
    }

    /**
     * @param outcome how the verification ended
     * @param profile the authenticated profile, present only when {@code outcome} is {@link Outcome#VERIFIED}
     */
    public record VerificationResult(Outcome outcome, Optional<GameProfile> profile) {
        static VerificationResult verified(GameProfile profile) {
            return new VerificationResult(Outcome.VERIFIED, Optional.of(profile));
        }

        static VerificationResult rejected() {
            return new VerificationResult(Outcome.REJECTED, Optional.empty());
        }

        static VerificationResult unavailable() {
            return new VerificationResult(Outcome.UNAVAILABLE, Optional.empty());
        }
    }

    /**
     * Send an encryption challenge and remember what we challenged with.
     *
     * @return {@code false} if the pending-challenge store is full, in which
     *         case nothing was sent and the caller must disconnect the client
     */
    public boolean initiatePremiumVerification(Connection connection, String username, KeyPair keyPair) {
        SecureRandom random = new SecureRandom();
        byte[] nonce = new byte[4];
        random.nextBytes(nonce);
        String serverId = Long.toHexString(random.nextLong());

        PendingVerification pending = new PendingVerification(username, serverId, nonce, keyPair);
        if (!pendingVerifications.put(connection.getRemoteAddress(), pending)) {
            LOGGER.warn("Refusing premium verification for {}: {} challenges already in flight",
                    username, pendingVerifications.size());
            return false;
        }

        connection.send(new ClientboundHelloPacket(serverId, keyPair.getPublic().getEncoded(), nonce, true));
        LOGGER.debug("Sent encryption request to {} for premium verification", username);
        return true;
    }

    /**
     * Handle a client's answer to our encryption challenge.
     *
     * <p>The local cryptography runs inline on the calling netty thread — it is
     * microseconds of CPU and no I/O, and enabling the cipher must happen
     * before any further packet is read, which is exactly where vanilla does
     * it. Only the Mojang session call is deferred; the returned future
     * completes on the HTTP executor and never on the event loop.
     *
     * <p>The future always completes normally; failures arrive as
     * {@link Outcome#REJECTED} or {@link Outcome#UNAVAILABLE}.
     */
    public CompletableFuture<VerificationResult> handleEncryptionResponse(
            Connection connection, ServerboundKeyPacket packet) {
        SocketAddress address = connection.getRemoteAddress();
        Optional<PendingVerification> maybePending = pendingVerifications.take(address);

        if (maybePending.isEmpty()) {
            // No challenge outstanding: a replayed key packet, one that arrived
            // after the 30s TTL, or a client that was never challenged at all.
            LOGGER.warn("Received encryption response from {} with no challenge outstanding", address);
            return CompletableFuture.completedFuture(VerificationResult.rejected());
        }

        PendingVerification pending = maybePending.get();
        String serverHash;

        try {
            PrivateKey privateKey = pending.keyPair().getPrivate();

            if (!packet.isChallengeValid(pending.nonce(), privateKey)) {
                LOGGER.warn("Nonce verification failed for {}", pending.username());
                return CompletableFuture.completedFuture(VerificationResult.rejected());
            }

            SecretKey sharedSecret = packet.getSecretKey(privateKey);
            serverHash = computeServerIdHash(pending.serverId(), sharedSecret, pending.keyPair().getPublic());

            // Vanilla enables the cipher on the netty thread, before it starts
            // authenticating, and so do we.
            Cipher decryptCipher = Crypt.getCipher(Cipher.DECRYPT_MODE, sharedSecret);
            Cipher encryptCipher = Crypt.getCipher(Cipher.ENCRYPT_MODE, sharedSecret);
            connection.setEncryptionKey(decryptCipher, encryptCipher);
        } catch (CryptException e) {
            LOGGER.warn("Encryption error during premium verification for {}: {}",
                    pending.username(), e.getMessage());
            return CompletableFuture.completedFuture(VerificationResult.rejected());
        }

        return PremiumVerifier.getInstance()
                .verifySession(pending.username(), serverHash)
                .thenApply(session -> toResult(pending.username(), session))
                .exceptionally(throwable -> {
                    // verifySession already absorbs its own failures; this is a
                    // belt-and-braces guard so the caller never sees a failed future.
                    LOGGER.warn("Premium verification for {} completed exceptionally: {}",
                            pending.username(), throwable.toString());
                    return VerificationResult.unavailable();
                });
    }

    private VerificationResult toResult(String username, PremiumVerifier.SessionResult session) {
        if (session.outcome() == PremiumLookup.PREMIUM && session.profile().isPresent()) {
            GameProfile profile = session.profile().get();
            LOGGER.info("Premium verification successful for {} (UUID: {})", profile.name(), profile.id());
            recordVerifiedPremiumLogin(profile.id(), profile.name());
            return VerificationResult.verified(profile);
        }

        if (session.outcome() == PremiumLookup.UNAVAILABLE) {
            LOGGER.warn("Could not reach Mojang to verify {} — failing closed", username);
            return VerificationResult.unavailable();
        }

        LOGGER.debug("Premium session verification failed for {}", username);
        return VerificationResult.rejected();
    }

    /**
     * Forget any challenge outstanding for a connection.
     *
     * <p>Must be called when a login connection closes, otherwise every client
     * that takes a challenge and walks away leaks an RSA key pair. The
     * expected rejection path — a cracked client picking a premium username —
     * goes exactly that way.
     */
    public void cleanup(Connection connection) {
        pendingVerifications.discard(connection.getRemoteAddress());
    }

    // ------------------------------------------------------------------
    // The verified-premium fact
    // ------------------------------------------------------------------

    /**
     * Remember that this exact {@code (uuid, username)} pair was verified
     * cryptographically during the login handshake.
     */
    public void recordVerifiedPremiumLogin(UUID uuid, String username) {
        if (!verifiedLogins.put(uuid, new VerifiedLogin(username))) {
            LOGGER.warn("Could not record the premium verification of {}: too many pending joins", username);
        }
    }

    /**
     * Ask whether this player completed a Mojang encryption handshake with us
     * during login, and consume the record.
     *
     * <p>This is the only trustworthy answer to "is this player premium". The
     * shape of a UUID is not: a Geyser/Floodgate player, or any UUID a proxy
     * forwards, is equally unlike an offline UUID, and treating that as proof
     * grants a password-less login.
     *
     * <p>Returns {@code false} for an unknown player, an expired record, a
     * record already consumed, or a username that does not match the one that
     * was verified. A mismatched username does <em>not</em> consume the record.
     *
     * @return {@code true} exactly once per verified handshake
     */
    public boolean wasVerifiedAtLogin(UUID uuid, String username) {
        if (uuid == null || username == null) {
            return false;
        }

        Optional<VerifiedLogin> record = verifiedLogins.peek(uuid);
        if (record.isEmpty()) {
            return false;
        }

        if (!record.get().matches(username)) {
            LOGGER.warn("Verified-login record for {} is under the name {}, not {} — refusing",
                    uuid, record.get().username(), username);
            return false;
        }

        verifiedLogins.discard(uuid);
        return true;
    }

    /**
     * Compute the Minecraft server ID hash: {@code SHA1(serverId + sharedSecret + publicKey)},
     * read as a signed big-endian integer and rendered as lowercase hex.
     */
    private static String computeServerIdHash(String serverId, SecretKey sharedSecret, PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            digest.update(sharedSecret.getEncoded());
            digest.update(publicKey.getEncoded());
            return new BigInteger(digest.digest()).toString(16);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute server ID hash", e);
        }
    }

    private record PendingVerification(String username, String serverId, byte[] nonce, KeyPair keyPair) {
    }

    private record VerifiedLogin(String username) {
        boolean matches(String candidate) {
            return username.toLowerCase(Locale.ROOT).equals(candidate.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A size-capped map whose entries expire.
     *
     * <p>Both of the maps this class keeps are filled by unauthenticated remote
     * peers, so neither may grow without bound and neither may hold an entry
     * forever. Expired entries are swept on every write, which is the only
     * moment the map can grow, so no background timer is needed.
     *
     * <p>A full store refuses new entries rather than evicting an existing one:
     * evicting would let a flood of fresh connections knock a legitimate
     * handshake out mid-flight.
     *
     * <p>The clock is injected so expiry can be tested without sleeping.
     */
    static final class TtlStore<K, V> {
        private final int maxEntries;
        private final long ttlMillis;
        private final LongSupplier clockMillis;
        private final Map<K, Entry<V>> entries = new HashMap<>();

        TtlStore(int maxEntries, Duration ttl, LongSupplier clockMillis) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries must be positive");
            }
            this.maxEntries = maxEntries;
            this.ttlMillis = ttl.toMillis();
            this.clockMillis = clockMillis;
        }

        /**
         * @return {@code false} if the store is full, in which case nothing was stored
         */
        synchronized boolean put(K key, V value) {
            sweep();
            if (!entries.containsKey(key) && entries.size() >= maxEntries) {
                return false;
            }
            entries.put(key, new Entry<>(value, clockMillis.getAsLong()));
            return true;
        }

        /**
         * Read and remove an entry. Returns empty if absent or expired.
         */
        synchronized Optional<V> take(K key) {
            Entry<V> entry = entries.remove(key);
            if (entry == null || isExpired(entry)) {
                return Optional.empty();
            }
            return Optional.of(entry.value);
        }

        /**
         * Read an entry without removing it. Returns empty if absent or expired.
         */
        synchronized Optional<V> peek(K key) {
            Entry<V> entry = entries.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (isExpired(entry)) {
                entries.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.value);
        }

        synchronized void discard(K key) {
            entries.remove(key);
        }

        /**
         * @return how many expired entries were removed
         */
        synchronized int sweep() {
            int removed = 0;
            Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (isExpired(iterator.next().getValue())) {
                    iterator.remove();
                    removed++;
                }
            }
            return removed;
        }

        synchronized int size() {
            return entries.size();
        }

        private boolean isExpired(Entry<V> entry) {
            return clockMillis.getAsLong() - entry.storedAt > ttlMillis;
        }

        private record Entry<V>(V value, long storedAt) {
        }
    }
}
