package com.dycomment.tv;

import android.os.SystemClock;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/** One bounded, ephemeral comparison exchange. Approval is an in-process UI operation only. */
final class LanPairingSession {
    interface Clock { long now(); }
    static final class Failure extends Exception {
        final int status;
        Failure(int status) { this.status = status; }
    }
    static final class View {
        final String id, status, sas;
        final long seconds;
        View(String id, String status, String sas, long seconds) {
            this.id = id; this.status = status; this.sas = sas; this.seconds = seconds;
        }
    }
    private final String receiverId;
    private final byte[] pairingKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    private boolean window;
    private boolean granted;
    private String id, status, sas, serverPublic, serverNonce, transcript;
    private byte[] commitment, derived;
    private KeyPair ephemeral;
    private JSONObject approved;
    private long deadline;

    LanPairingSession(String receiverId, byte[] pairingKey) { this(receiverId, pairingKey, SystemClock::elapsedRealtime); }
    LanPairingSession(String receiverId, byte[] pairingKey, Clock clock) {
        this.receiverId = receiverId; this.pairingKey = pairingKey.clone(); this.clock = clock;
    }
    synchronized void openWindow() { expire(); window = true; }
    synchronized void closeWindow() { window = false; expire(); if (!"approved".equals(status)) clear(); }
    synchronized void destroy() { window = false; clear(); Arrays.fill(pairingKey, (byte) 0); }
    private void clearSecrets() {
        if (derived != null) Arrays.fill(derived, (byte) 0);
        derived = null; ephemeral = null; commitment = null; approved = null; transcript = null; sas = null;
    }
    private void clear() { clearSecrets(); id = null; status = null; serverPublic = null; serverNonce = null; granted = false; }
    private void expire() {
        if (id != null && clock.now() >= deadline && !"expired".equals(status)) {
            clearSecrets(); status = "expired";
        }
    }
    synchronized View view() {
        expire();
        return !window || id == null ? null : new View(id, granted && "expired".equals(status) ? "approved" : status,
                sas, Math.max(0, (deadline - clock.now() + 999) / 1000));
    }
    synchronized void decide(String shownId, boolean allow) {
        expire();
        if (!window || id == null || !id.equals(shownId) || !"pending".equals(status)) return;
        if (!allow) { clearSecrets(); status = "rejected"; return; }
        try {
            byte[] nonce = bytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(utf8("mydv-pair/v1/approved\n" + transcript));
            JSONObject plain = new JSONObject().put("receiver_id", receiverId).put("pair_id", id)
                    .put("pairing_key", LanSyncService.hex(pairingKey));
            approved = result("approved").put("nonce", encode(nonce))
                    .put("payload", encode(cipher.doFinal(utf8(plain.toString()))));
            Arrays.fill(derived, (byte) 0); derived = null; transcript = null; sas = null;
            status = "approved"; granted = true;
        } catch (Exception failed) { clearSecrets(); status = "rejected"; }
    }
    synchronized JSONObject handle(String path, JSONObject input) throws Exception {
        expire();
        if (!window && !("/v1/pair/status".equals(path) && "approved".equals(status))) throw new Failure(403);
        if ("/v1/pair/begin".equals(path)) return begin(input);
        if ("/v1/pair/reveal".equals(path)) return reveal(input);
        if ("/v1/pair/status".equals(path)) {
            fields(input, "version", "pair_id"); match(input);
            return approved != null ? new JSONObject(approved.toString()) : result("committed".equals(status) ? "pending" : status);
        }
        throw new Failure(404);
    }
    private JSONObject begin(JSONObject input) throws Exception {
        long now = clock.now();
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= 300000) attempts.removeFirst();
        if (attempts.size() >= 5) throw new Failure(429);
        attempts.addLast(now);
        fields(input, "version", "commitment");
        byte[] nextCommitment = binary(input, "commitment", 32);
        if (id != null && ("committed".equals(status) || "pending".equals(status) || "approved".equals(status)))
            throw new Failure(409);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair next = generator.generateKeyPair();
        clear(); ephemeral = next; commitment = nextCommitment;
        id = LanSyncService.hex(bytes(16)); serverPublic = encode(next.getPublic().getEncoded()); serverNonce = encode(bytes(32));
        deadline = now + 120000; status = "committed";
        return new JSONObject().put("version", 1).put("receiver_id", receiverId).put("pair_id", id)
                .put("server_public", serverPublic).put("server_nonce", serverNonce).put("expires_in", 120);
    }
    private JSONObject reveal(JSONObject input) throws Exception {
        fields(input, "version", "pair_id", "client_public", "client_nonce"); match(input);
        if (!"committed".equals(status)) throw new Failure(409);
        try {
            byte[] publicDer = binary(input, "client_public", 91), nonce = binary(input, "client_nonce", 32);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(utf8("mydv-pair/v1/commit\n")); digest.update(publicDer); digest.update(nonce);
            if (!MessageDigest.isEqual(commitment, digest.digest())) throw new Failure(400);
            ECPublicKey publicKey = p256(publicDer);
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ephemeral.getPrivate()); agreement.doPhase(publicKey, true);
            byte[] shared = agreement.generateSecret();
            if (shared.length > 32 || shared.length == 0) throw new Failure(400);
            if (shared.length < 32) {
                byte[] padded = new byte[32]; System.arraycopy(shared, 0, padded, 32 - shared.length, shared.length);
                Arrays.fill(shared, (byte) 0); shared = padded;
            }
            transcript = "mydv-pair/v1\n" + receiverId + "\n" + id + "\n" + input.getString("client_public")
                    + "\n" + input.getString("client_nonce") + "\n" + serverPublic + "\n" + serverNonce;
            byte[] prk = hmac(digest.digest(utf8(transcript)), shared);
            derived = hmac(prk, utf8("mydv-pair/v1/key\u0001"));
            Arrays.fill(prk, (byte) 0); Arrays.fill(shared, (byte) 0);
            byte[] comparison = hmac(derived, utf8("mydv-pair/v1/sas\n" + transcript));
            long number = ((comparison[0] & 255L) << 24) | ((comparison[1] & 255L) << 16)
                    | ((comparison[2] & 255L) << 8) | (comparison[3] & 255L);
            sas = String.format(Locale.US, "%06d", number % 1000000);
            ephemeral = null; commitment = null; status = "pending";
            return result("pending");
        } catch (Exception malformed) {
            clearSecrets(); status = "rejected";
            throw new Failure(400);
        }
    }
    private void match(JSONObject input) throws Exception {
        Object supplied = input.opt("pair_id");
        if (!(supplied instanceof String) || !((String) supplied).matches("[a-f0-9]{32}")) throw new Failure(400);
        if (id == null || !id.equals(supplied)) throw new Failure(404);
    }
    private static void fields(JSONObject input, String... names) throws Failure {
        if (input.length() != names.length || !(input.opt("version") instanceof Integer || input.opt("version") instanceof Long)
                || ((Number) input.opt("version")).doubleValue() != 1d) throw new Failure(400);
        for (String name : names) if (!input.has(name)) throw new Failure(400);
    }
    private static byte[] binary(JSONObject input, String name, int length) throws Failure {
        Object value = input.opt(name);
        if (!(value instanceof String) || ((String) value).length() > 128) throw new Failure(400);
        try {
            byte[] decoded = LanCredentialServer.decode((String) value);
            if (decoded.length != length) throw new Failure(400);
            return decoded;
        } catch (IllegalArgumentException malformed) { throw new Failure(400); }
    }
    static ECPublicKey p256(byte[] der) throws Exception {
        // Exactly named prime256v1, uncompressed point, canonical DER SPKI (no alternate curves/encodings).
        byte[] prefix = new byte[] {0x30,0x59,0x30,0x13,0x06,0x07,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x02,0x01,
                0x06,0x08,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00,0x04};
        if (der.length != 91) throw new Failure(400);
        for (int i = 0; i < prefix.length; i++) if (der[i] != prefix[i]) throw new Failure(400);
        PublicKey decoded = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        if (!(decoded instanceof ECPublicKey) || !Arrays.equals(der, decoded.getEncoded())) throw new Failure(400);
        ECPublicKey key = (ECPublicKey) decoded;
        ECParameterSpec spec = key.getParams();
        if (!(spec.getCurve().getField() instanceof ECFieldFp) || spec.getCurve().getField().getFieldSize() != 256)
            throw new Failure(400);
        BigInteger p = ((ECFieldFp) spec.getCurve().getField()).getP(), x = key.getW().getAffineX(), y = key.getW().getAffineY();
        if (x == null || y == null || x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                || !y.multiply(y).mod(p).equals(x.multiply(x).multiply(x).add(spec.getCurve().getA().multiply(x))
                .add(spec.getCurve().getB()).mod(p))) throw new Failure(400);
        return key;
    }
    private JSONObject result(String outcome) throws Exception {
        return new JSONObject().put("version", 1).put("pair_id", id).put("status", outcome);
    }
    private byte[] bytes(int count) { byte[] value = new byte[count]; random.nextBytes(value); return value; }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value);
    }
    private static String encode(byte[] value) { return LanCredentialServer.encode(value); }
}
