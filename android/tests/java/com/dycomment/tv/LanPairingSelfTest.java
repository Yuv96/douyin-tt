package com.dycomment.tv;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/** Source fixture executed exclusively by the GitHub API21 job. */
final class LanPairingSelfTest {
    static final String KEY = "000102030405060708090a0b0c0d0e0f";
    static final String RECEIVER = "00112233445566778899aabbccddeeff";
    static final class Client {
        final KeyPair keys;
        final byte[] nonce = new byte[32];
        String pairId, receiver, transcript, sas;
        byte[] derived;
        Client() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1")); keys = generator.generateKeyPair();
            new SecureRandom().nextBytes(nonce);
        }
        JSONObject begin() throws Exception {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(bytes("mydv-pair/v1/commit\n")); hash.update(keys.getPublic().getEncoded()); hash.update(nonce);
            return new JSONObject().put("version", 1).put("commitment", encode(hash.digest()));
        }
        void accept(JSONObject response) throws Exception {
            pairId = response.getString("pair_id"); receiver = response.getString("receiver_id");
            String serverPublic = response.getString("server_public"), serverNonce = response.getString("server_nonce");
            transcript = "mydv-pair/v1\n" + receiver + "\n" + pairId + "\n" + encode(keys.getPublic().getEncoded())
                    + "\n" + encode(nonce) + "\n" + serverPublic + "\n" + serverNonce;
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH"); agreement.init(keys.getPrivate());
            agreement.doPhase(LanPairingSession.p256(LanCredentialServer.decode(serverPublic)), true);
            byte[] shared = agreement.generateSecret();
            if (shared.length < 32) { byte[] padded = new byte[32]; System.arraycopy(shared, 0, padded, 32-shared.length, shared.length); shared = padded; }
            byte[] salt = MessageDigest.getInstance("SHA-256").digest(bytes(transcript));
            byte[] prk = mac(salt, shared); derived = mac(prk, bytes("mydv-pair/v1/key\u0001"));
            byte[] proof = mac(derived, bytes("mydv-pair/v1/sas\n" + transcript));
            long number = ((proof[0] & 255L) << 24) | ((proof[1] & 255L) << 16) | ((proof[2] & 255L) << 8) | (proof[3] & 255L);
            sas = String.format(Locale.US, "%06d", number % 1000000);
        }
        JSONObject reveal() throws Exception {
            return query().put("client_public", encode(keys.getPublic().getEncoded())).put("client_nonce", encode(nonce));
        }
        JSONObject query() throws Exception { return new JSONObject().put("version", 1).put("pair_id", pairId); }
        String decrypt(JSONObject response) throws Exception {
            require("approved".equals(response.getString("status")) && pairId.equals(response.getString("pair_id")), "approved identity");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"),
                    new GCMParameterSpec(128, LanCredentialServer.decode(response.getString("nonce"))));
            cipher.updateAAD(bytes("mydv-pair/v1/approved\n" + transcript));
            JSONObject plain = new JSONObject(new String(cipher.doFinal(LanCredentialServer.decode(response.getString("payload"))), StandardCharsets.UTF_8));
            require(receiver.equals(plain.getString("receiver_id")) && pairId.equals(plain.getString("pair_id")), "encrypted identities");
            return plain.getString("pairing_key");
        }
    }
    static void run(Context context) throws Exception {
        CredentialStore.Snapshot before = CredentialStore.snapshot(context);
        String existingKey = LanSyncService.pairingKey(context);
        final long[] now = {1000};
        LanPairingSession session = session(now);
        Client client = new Client();
        failure(session, "begin", client.begin(), 403, "closed window");
        session.openWindow(); client.accept(session.handle(path("begin"), client.begin()));
        failure(session, "begin", new Client().begin(), 409, "parallel does not overwrite");
        require(client.pairId.equals(session.view().id), "pending id preserved");
        session.handle(path("reveal"), client.reveal());
        require(client.sas.equals(session.view().sas), "ECDH comparison matches");
        JSONObject pending = session.handle(path("status"), client.query());
        require(pending.length() == 3 && "pending".equals(pending.getString("status")), "pending exposes no key or SAS");
        session.decide("ffffffffffffffffffffffffffffffff", true);
        require("pending".equals(session.view().status), "wrong shown id cannot approve");
        session.decide(client.pairId, false);
        require("rejected".equals(session.handle(path("status"), client.query()).getString("status")), "explicit rejection");
        session.decide(client.pairId, true);
        require("rejected".equals(session.view().status), "rejected cannot later approve");
        session.destroy();

        session = session(now); session.openWindow(); client = new Client();
        client.accept(session.handle(path("begin"), client.begin()));
        byte[] changed = client.nonce.clone(); changed[0] ^= 1;
        failure(session, "reveal", client.reveal().put("client_nonce", encode(changed)), 400, "commit mismatch");
        require("rejected".equals(session.view().status), "commit mismatch terminal");
        session.decide(client.pairId, true);
        require(!session.handle(path("status"), client.query()).has("payload"), "bad commitment cannot disclose key");
        session.destroy();

        session = session(now); session.openWindow(); client = new Client();
        client.accept(session.handle(path("begin"), client.begin()));
        failure(session, "reveal", client.reveal().put("client_public", "bad="), 400, "noncanonical encoding");
        require("rejected".equals(session.view().status), "malformed reveal terminal");
        session.destroy();

        session = session(now); session.openWindow(); client = new Client();
        byte[] badDer = client.keys.getPublic().getEncoded().clone(); badDer[22] ^= 1;
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); hash.update(bytes("mydv-pair/v1/commit\n")); hash.update(badDer); hash.update(client.nonce);
        JSONObject beginning = session.handle(path("begin"), new JSONObject().put("version", 1).put("commitment", encode(hash.digest())));
        client.accept(beginning);
        failure(session, "reveal", client.reveal().put("client_public", encode(badDer)), 400, "wrong curve DER despite matching commitment");
        session.destroy();

        session = session(now); session.openWindow(); client = new Client();
        failure(session, "begin", client.begin().put("unexpected", true), 400, "extra request field");
        failure(session, "begin", new JSONObject().put("version", true).put("commitment", "AA"), 400, "invalid field types");
        client.accept(session.handle(path("begin"), client.begin()));
        session.handle(path("reveal"), client.reveal()); now[0] += 120000;
        session.decide(client.pairId, true);
        require("expired".equals(session.handle(path("status"), client.query()).getString("status")), "expiry prevents approval");
        session.destroy();

        session = session(now); session.openWindow(); client = new Client();
        client.accept(session.handle(path("begin"), client.begin()));
        session.handle(path("reveal"), client.reveal()); session.closeWindow();
        failure(session, "status", client.query(), 403, "Back cancels pending");
        session.openWindow(); failure(session, "status", client.query(), 404, "old request remains cancelled after resume");
        session.destroy();

        session = session(now); session.openWindow(); client = new Client();
        client.accept(session.handle(path("begin"), client.begin())); session.handle(path("reveal"), client.reveal());
        session.decide(client.pairId, true);
        JSONObject approved = session.handle(path("status"), client.query());
        require(KEY.equals(client.decrypt(approved)), "AES256 grant contains original random AES128 key");
        session.closeWindow();
        JSONObject afterBack = session.handle(path("status"), client.query());
        require(approved.getString("payload").equals(afterBack.getString("payload"))
                && approved.getString("nonce").equals(afterBack.getString("nonce")), "approved response cached after Back");
        now[0] += 120000;
        failure(session, "status", client.query(), 403, "closed approved exchange expires");
        session.destroy();

        session = session(now); session.openWindow();
        for (int i = 0; i < 5; i++) {
            client = new Client(); client.accept(session.handle(path("begin"), client.begin()));
            session.handle(path("reveal"), client.reveal()); session.decide(client.pairId, false);
        }
        failure(session, "begin", new Client().begin(), 429, "begin rate bounded");
        session.closeWindow(); session.openWindow();
        failure(session, "begin", new Client().begin(), 429, "window reopen cannot bypass rate limit");
        now[0] += 300000; session.handle(path("begin"), new Client().begin()); session.destroy();
        CredentialStore.Snapshot after = CredentialStore.snapshot(context);
        require(before.generation == after.generation && before.cookie.equals(after.cookie)
                && before.msToken.equals(after.msToken) && existingKey.equals(LanSyncService.pairingKey(context)), "all pairing outcomes preserve account and persistent key");
    }
    private static LanPairingSession session(long[] now) { return new LanPairingSession(RECEIVER, LanSyncService.unhex(KEY), () -> now[0]); }
    private static String path(String route) { return "/v1/pair/" + route; }
    private static void failure(LanPairingSession session, String route, JSONObject input, int status, String message) throws Exception {
        try { session.handle(path(route), input); }
        catch (LanPairingSession.Failure rejected) { require(rejected.status == status, message); return; }
        throw new Exception(message);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String encode(byte[] value) { return LanCredentialServer.encode(value); }
    private static byte[] mac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value);
    }
    private static void require(boolean value, String message) throws Exception { if (!value) throw new Exception(message); }
}
