package com.dycomment.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** API21 real TCP/AES-GCM/profile fixture; no real credentials or external account requests. */
public final class LanSyncSelfTestActivity extends Activity {
    private static final String TAG = "Android5LanSyncTest";
    private static final String KEY = "000102030405060708090a0b0c0d0e0f";
    private static final String A = "sessionid=fixture-lan-a; msToken=token-a";
    private static final String B = "sessionid=fixture-lan-b; msToken=token-b";
    private static final String C = "sessionid=fixture-lan-c; msToken=token-c";
    private static final String PROFILE = "http://127.0.0.1:18766";
    private static final String[] AUTH = {"cookie", "ms_token", "ms_token_time", "a_bogus", "credential_generation"};
    private static volatile int validations;
    private static volatile long rejectedAt;
    private static volatile boolean external;
    private static volatile int profileHealthMode;
    private static volatile FixtureService fixtureService;
    private static CountDownLatch serviceStopped;
    private static volatile CountDownLatch delayedValidation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Map<String, ?> oldAuth, oldLan;
    private String oldCookie;
    private ServerSocket profileServer;
    private volatile boolean finished;
    private static volatile LanSyncSelfTestActivity externalActivity;
    private static volatile boolean externalBrowserRequested, externalBrowserPassed;

    public static final class FixtureService extends LanSyncService {
        @Override public void onCreate() { super.onCreate(); fixtureService = this; }
        @Override LanCredentialServer.Validator validator() {
            return cookie -> {
                int call = ++validations;
                if (external) {
                    if ((call == 1 || call == 2) && !cookie.equals(A)) throw new Exception("unexpected fixture sequence");
                    if (call == 3 && !cookie.equals(B)) throw new Exception("unexpected fixture sequence");
                    if (call == 4 && !cookie.equals(C)) throw new Exception("unexpected fixture sequence");
                }
                CountDownLatch delayed = delayedValidation;
                try {
                    if (delayed != null && cookie.equals(A)) {
                        long until = SystemClock.elapsedRealtime() + 27000;
                        while (SystemClock.elapsedRealtime() < until) {
                            try { Thread.sleep(Math.max(1, until - SystemClock.elapsedRealtime())); }
                            catch (InterruptedException ignored) { }
                        }
                    }
                    SocialApi.validateAccountAt(PROFILE, cookie);
                } catch (Exception rejected) {
                    if (cookie.equals(C)) rejectedAt = SystemClock.elapsedRealtime();
                    throw rejected;
                } finally { if (delayed != null) delayed.countDown(); }
            };
        }
        @Override public void onDestroy() {
            super.onDestroy(); fixtureService = null; serviceStopped.countDown();
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this); text.setText("LAN receiver regression"); setContentView(text);
        external = getIntent().getBooleanExtra("external_sender", false);
        externalBrowserRequested = false; externalBrowserPassed = false;
        validations = 0; rejectedAt = 0; profileHealthMode = 0; delayedValidation = null; serviceStopped = new CountDownLatch(1);
        oldAuth = getSharedPreferences("dy_config", 0).getAll();
        oldLan = LanSyncService.settings(this).getAll();
        oldCookie = SocialApi.cookie();
        new Thread(() -> runScenario(), "lan-sync-regression").start();
    }

    private void runScenario() {
        boolean pass = false;
        try {
            startProfile();
            LanSyncService.ensurePairing(this);
            require(LanSyncService.settings(this).edit().putBoolean("enabled", true).putString("pair_key", KEY).commit(), "fixture settings");
            main.post(() -> startService(new Intent(this, FixtureService.class)));
            JSONObject challenge = waitReady();
            require(!challenge.toString().contains(KEY), "pairing key is not an HTTP field");
            if (external) {
                LanSyncService.openPairingWindow(this);
                externalActivity = this;
                Log.i(TAG, "LAN_SYNC_EXTERNAL_READY");
                long end = SystemClock.elapsedRealtime() + 120000;
                while (SystemClock.elapsedRealtime() < end && (rejectedAt == 0 || SystemClock.elapsedRealtime() - rejectedAt < 5000))
                    Thread.sleep(100);
                require(rejectedAt != 0 && validations == 4, "external wrong-key/A/unchanged/B/C validation sequence");
                require(externalBrowserPassed, "Python browser client confirms the synthetic Android action");
                assertCurrent(B, "token-b");
                require(exchange("GET", "/v1/challenge", null).status == 200, "listener survives external requests");
            } else {
                localScenario(challenge);
            }
            pass = true;
        } catch (Exception failure) {
            Log.e(TAG, "FAIL LAN fixture " + failure.getMessage());
        } finally {
            cleanup();
            if (pass) Log.i(TAG, external ? "PASS API21_LAN_SYNC_EXTERNAL_A_B_REJECT_C"
                    : "PASS API21_LAN_SYNC_PERSISTENT_ROTATION");
            finished = true;
            main.post(() -> finish());
        }
    }

    // Called only by the explicit receiver included in SELF_TEST APKs.
    static synchronized int triggerExternalBrowserAction(Intent intent) {
        LanSyncSelfTestActivity activity = externalActivity;
        if (activity == null || !external || !"com.dycomment.tv.TEST_BROWSER_ACTION".equals(intent.getAction())
                || externalBrowserRequested || validations != 1 || !A.equals(CredentialStore.snapshot(activity).cookie)
                || !BrowserActionBroker.available("like", A)) return 2;
        externalBrowserRequested = true;
        new Thread(() -> {
            try {
                JSONObject data = BrowserActionBroker.perform(activity, "like",
                        new JSONObject().put("video_id", "123").put("enabled", true), A);
                require(BrowserActionBroker.exact(data, "confirmed", "enabled") && data.getBoolean("confirmed")
                        && data.getBoolean("enabled"), "synthetic browser action is confirmed");
                externalBrowserPassed = true;
                Log.i(TAG, "PASS API21_BROWSER_ACTION_FROM_PYTHON");
            } catch (Exception failure) { Log.e(TAG, "FAIL Python browser action fixture"); }
        }, "browser-python-fixture").start();
        return 1;
    }

    // Called only by the explicit receiver included in SELF_TEST APKs.
    static int confirmExternalPairing(Intent intent) {
        LanSyncSelfTestActivity activity = externalActivity;
        if (activity == null || !external) return pairingFailure("no-external-fixture");
        if (!"com.dycomment.tv.TEST_PAIR_CONFIRM".equals(intent.getAction())) return pairingFailure("unexpected-action");
        LanPairingSession.View view = LanSyncService.pairingView(activity);
        if (view == null || !"pending".equals(view.status)) return pairingFailure("no-pending-request");
        if (!view.id.equals(intent.getStringExtra("pair_id"))) return pairingFailure("request-id-mismatch");
        if (!view.sas.equals(intent.getStringExtra("sas"))) return pairingFailure("comparison-mismatch");
        LanSyncService.decidePairing(activity, view.id, true);
        LanPairingSession.View after = LanSyncService.pairingView(activity);
        if (after == null || !"approved".equals(after.status)) return pairingFailure("local-approval-failed");
        Log.i(TAG, "PASS API21_LAN_PAIRING_COMPARISON"); return 1;
    }
    private static int pairingFailure(String category) {
        Log.e(TAG, "FAIL LAN pairing " + category); return 2;
    }

    private void localScenario(JSONObject challenge) throws Exception {
        LanPairingSelfTest.run(this);
        pairingScenario();
        String id = challenge.getString("receiver_id"), proof = challenge.getString("challenge");
        long before = CredentialStore.snapshot(this).generation;
        Frame first = frame(id, proof, A, "token-a", LanSyncService.unhex(KEY));
        JSONObject a = decoded(exchange("POST", "/v1/credentials", first.body), first, id);
        require(a.getString("status").equals("updated") && a.getLong("generation") == before + 1, "A accepted");
        assertCurrent(A, "token-a");
        int calls = validations;
        require(exchange("POST", "/v1/credentials", first.body).status == 401, "replayed envelope rejected");
        byte[] wrong = LanSyncService.unhex(KEY); wrong[0] ^= 1;
        Frame invalidKey = frame(id, proof, B, "token-b", wrong);
        require(exchange("POST", "/v1/credentials", invalidKey.body).status == 401, "wrong key rejected");
        require(validations == calls, "authentication rejects occur before account requests");
        Frame same = frame(id, proof, A, "token-a", LanSyncService.unhex(KEY));
        CredentialHealth.observe(OfficialLoginPolicy.SELF_PATH, A, CredentialHealth.observation(A), 401, null);
        require(CredentialHealth.needsRefresh(), "strict self rejection marks account unauthenticated");
        require(SocialApi.probeAccountAt(PROFILE, A) == CredentialHealth.AUTHENTICATED,
                "real self probe verifies strict valid profile");
        require(CredentialHealth.needsRefresh(), "internal probe has no recursive or premature health hooks");
        try {
            for (int mode : new int[] {1, 2, 4, 5}) {
                profileHealthMode = mode;
                require(SocialApi.probeAccountAt(PROFILE, A) == CredentialHealth.UNKNOWN,
                        "malformed WAF schema and generic business self responses remain inconclusive");
            }
            profileHealthMode = 3;
            require(SocialApi.probeAccountAt(PROFILE, A) == CredentialHealth.UNAUTHENTICATED,
                    "real self HTTP401 confirms unauthenticated");
            profileHealthMode = 0;
            require(SocialApi.probeAccountAt(PROFILE, C) == CredentialHealth.UNAUTHENTICATED,
                    "real self business8 confirms unauthenticated");
        } finally { profileHealthMode = 0; }
        JSONObject unchanged = decoded(exchange("POST", "/v1/credentials", same.body), same, id);
        require(unchanged.getString("status").equals("unchanged") && unchanged.getLong("generation") == before + 1, "unchanged generation");
        require(!CredentialHealth.needsRefresh(), "validated unchanged sync clears obsolete account rejection without saving");
        Frame second = frame(id, proof, B, "token-b", LanSyncService.unhex(KEY));
        JSONObject b = decoded(exchange("POST", "/v1/credentials", second.body), second, id);
        require(b.getString("status").equals("updated") && b.getLong("generation") == before + 2, "B replaces A");
        assertCurrent(B, "token-b");
        Frame third = frame(id, proof, C, "token-c", LanSyncService.unhex(KEY));
        JSONObject c = decoded(exchange("POST", "/v1/credentials", third.body), third, id);
        require(c.getString("status").equals("rejected") && c.getLong("generation") == before + 2, "failed C preserves B");
        assertCurrent(B, "token-b");
        Frame mismatch = frame(id, proof, B, "not-token-b", LanSyncService.unhex(KEY));
        calls = validations;
        require(decoded(exchange("POST", "/v1/credentials", mismatch.body), mismatch, id).getString("status").equals("rejected"), "mismatched token rejected");
        require(validations == calls, "mismatched token is not sent to account API");
        try (Socket idle = new Socket("127.0.0.1", LanCredentialServer.PORT)) {
            idle.setSoTimeout(8000);
            idle.getOutputStream().write(("POST /v1/credentials HTTP/1.1\r\nHost: 127.0.0.1:18765\r\n").getBytes(StandardCharsets.US_ASCII));
            BufferedReader input = new BufferedReader(new InputStreamReader(idle.getInputStream(), StandardCharsets.US_ASCII));
            require(input.readLine().startsWith("HTTP/1.1 400"), "incomplete HTTP request times out");
        }
        assertCurrent(B, "token-b");
        delayedValidation = new CountDownLatch(1);
        Frame timed = frame(id, proof, A, "token-a", LanSyncService.unhex(KEY));
        long started = SystemClock.elapsedRealtime();
        JSONObject timeout = decoded(exchange("POST", "/v1/credentials", timed.body), timed, id);
        long elapsed = SystemClock.elapsedRealtime() - started;
        require(timeout.getString("status").equals("rejected") && timeout.getLong("generation") == before + 2,
                "account validation timeout preserves B");
        require(elapsed >= 24000 && elapsed < 35000, "account validation has a bounded timeout");
        assertCurrent(B, "token-b");
        require(delayedValidation.await(6, TimeUnit.SECONDS), "late account validation completes");
        delayedValidation = null;
        assertCurrent(B, "token-b");
        require(CredentialStore.snapshot(this).generation == before + 2, "late validation cannot publish credentials");
        JSONObject stillOpen = new JSONObject(exchange("GET", "/v1/challenge", null).body);
        require(stillOpen.getString("receiver_id").equals(id) && stillOpen.getString("challenge").equals(proof), "listener and timed challenge persist");
        require(!LanCredentialServer.privateAddress(InetAddress.getByName("203.0.113.2")), "documentation-range peers rejected");
        BrowserActionsSelfTest.run(this, stillOpen);
    }

    private void pairingScenario() throws Exception {
        LanPairingSelfTest.Client client = new LanPairingSelfTest.Client();
        require(exchange("POST", "/v1/pair/begin", client.begin().toString()).status == 403, "pairing closed by default");
        LanSyncService.openPairingWindow(this);
        Response beginning = exchange("POST", "/v1/pair/begin", client.begin().toString());
        require(beginning.status == 200, "pairing begin over real HTTP");
        client.accept(new JSONObject(beginning.body));
        require(exchange("POST", "/v1/pair/begin", client.begin().toString()).status == 409, "parallel request cannot overwrite");
        require(exchange("POST", "/v1/pair/reveal", client.reveal().toString()).status == 200, "commitment reveal over HTTP");
        LanPairingSession.View view = LanSyncService.pairingView(this);
        require(view != null && "pending".equals(view.status) && client.sas.equals(view.sas), "real API21 ECDH comparison matches");
        JSONObject pending = new JSONObject(exchange("POST", "/v1/pair/status", client.query().toString()).body);
        require("pending".equals(pending.getString("status")) && !pending.has("payload") && !pending.has("sas"), "no network autoapproval or SAS leak");
        LanSyncService.decidePairing(this, "00000000000000000000000000000000", true);
        require("pending".equals(LanSyncService.pairingView(this).status), "stale UI cannot approve");
        LanSyncService.decidePairing(this, view.id, true);
        LanSyncService.closePairingWindow(this);
        JSONObject approved = new JSONObject(exchange("POST", "/v1/pair/status", client.query().toString()).body);
        require(KEY.equals(client.decrypt(approved)), "only local approval delivers existing key, including after Back");
        require(exchange("POST", "/v1/pair/begin", client.begin().toString()).status == 403, "Back closes new pairing");
        Log.i(TAG, "PASS API21_LAN_PAIRING_COMPARISON");
    }

    private void assertCurrent(String cookie, String token) throws Exception {
        CredentialStore.Snapshot snapshot = CredentialStore.snapshot(this);
        require(snapshot.cookie.equals(cookie) && snapshot.msToken.equals(token) && snapshot.aBogus.isEmpty(), "coherent credential snapshot");
        require(SocialApi.cookie().equals(cookie), "subsequent requests see new cookie");
        final String[] current = {null};
        MsTokenHelper.getTokens(this, new MsTokenHelper.TokenCallback() {
            @Override public void onToken(MsTokenHelper.TokenPair pair) { current[0] = pair.msToken; }
            @Override public void onFailed(String reason) { current[0] = "failed"; }
        });
        require(token.equals(current[0]), "subsequent token reader sees new token");
        OfficialLoginPolicy.verified(SocialApi.requestAt(PROFILE, OfficialLoginPolicy.SELF_PATH,
                SocialApi.params(), false, SocialApi.cookie()));
    }

    private JSONObject waitReady() throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            try {
                Response response = exchange("GET", "/v1/challenge", null);
                if (response.status == 200) return new JSONObject(response.body);
            } catch (IOException waiting) { }
            Thread.sleep(100);
        }
        throw new Exception("receiver did not bind fixed port");
    }

    private static final class Frame { String requestId, body; }
    private static Frame frame(String receiver, String challenge, String cookie, String token, byte[] key) throws Exception {
        Frame frame = new Frame(); frame.requestId = UUID.randomUUID().toString().replace("-", "");
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("mydv-sync/v1\n" + receiver + "\n" + frame.requestId).getBytes(StandardCharsets.UTF_8));
        byte[] plain = new JSONObject().put("challenge", challenge).put("cookie", cookie).put("ms_token", token).toString().getBytes(StandardCharsets.UTF_8);
        frame.body = new JSONObject().put("version", 1).put("request_id", frame.requestId)
                .put("nonce", LanCredentialServer.encode(nonce)).put("payload", LanCredentialServer.encode(cipher.doFinal(plain))).toString();
        return frame;
    }
    private static JSONObject decoded(Response response, Frame frame, String receiver) throws Exception {
        require(response.status == 200, "authenticated response HTTP status");
        JSONObject wrapper = new JSONObject(response.body);
        require(wrapper.getString("request_id").equals(frame.requestId), "response request binding");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(LanSyncService.unhex(KEY), "AES"),
                new GCMParameterSpec(128, LanCredentialServer.decode(wrapper.getString("nonce"))));
        cipher.updateAAD(("mydv-sync/v1/response\n" + receiver + "\n" + frame.requestId).getBytes(StandardCharsets.UTF_8));
        JSONObject plain = new JSONObject(new String(cipher.doFinal(LanCredentialServer.decode(wrapper.getString("payload"))), StandardCharsets.UTF_8));
        require(plain.getString("request_id").equals(frame.requestId), "authenticated response request id");
        return plain;
    }
    private static final class Response { int status; String body; }
    private static Response exchange(String method, String path, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", LanCredentialServer.PORT)) {
            socket.setSoTimeout(40000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String header = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:18765\r\nConnection: close\r\n"
                    + (body == null ? "" : "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n") + "\r\n";
            OutputStream out = socket.getOutputStream(); out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String first = input.readLine(); if (first == null) throw new IOException("empty fixture response");
            Response response = new Response(); response.status = Integer.parseInt(first.split(" ")[1]);
            String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
            StringBuilder result = new StringBuilder(); char[] buffer = new char[4096]; int count;
            while ((count = input.read(buffer)) != -1) { result.append(buffer, 0, count); if (result.length() > 98304) throw new IOException("fixture response too large"); }
            response.body = result.toString(); return response;
        }
    }

    private void startProfile() throws IOException {
        profileServer = new ServerSocket(); profileServer.setReuseAddress(true);
        profileServer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 18766));
        final ServerSocket listener = profileServer;
        Thread thread = new Thread(() -> {
            while (!listener.isClosed()) try (Socket socket = listener.accept()) {
                socket.setSoTimeout(3000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String first = input.readLine(), cookie = "", line; int size = 0;
                while ((line = input.readLine()) != null && !line.isEmpty()) {
                    if ((size += line.length()) > 8192) throw new IOException("fixture header too long");
                    if (line.regionMatches(true, 0, "Cookie:", 0, 7)) cookie = line.substring(7).trim();
                }
                boolean success = first != null && first.startsWith("GET " + OfficialLoginPolicy.SELF_PATH)
                        && (cookie.equals(A) || cookie.equals(B));
                String response = success ? "{\"status_code\":0,\"user\":{\"uid\":\"" + (cookie.equals(A) ? "101" : "202") + "\"}}"
                        : "{\"status_code\":8}";
                int mode = profileHealthMode;
                int http = mode == 2 ? 403 : mode == 3 ? 401 : 200;
                if (mode == 1) response = "fixture-not-json";
                if (mode == 4) response = "{\"status_code\":0,\"user\":{}}";
                if (mode == 5) response = "{\"status_code\":4}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 " + http + " Fixture\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
            } catch (Exception unavailable) { }
        }, "lan-sync-account-fixture");
        thread.setDaemon(true); thread.start();
    }

    private void cleanup() {
        if (externalActivity == this) externalActivity = null;
        LanSyncService.closePairingWindow(this);
        main.post(() -> stopService(new Intent(this, FixtureService.class)));
        try { serviceStopped.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try { if (profileServer != null) profileServer.close(); } catch (IOException ignored) { }
        synchronized (CredentialStore.class) {
            SharedPreferences.Editor auth = getSharedPreferences("dy_config", 0).edit();
            for (String name : AUTH) restoreValue(auth, name, oldAuth.get(name));
            auth.commit();
            try {
                java.lang.reflect.Field field = Class.forName("com.dycomment.tv.DouyinApi").getDeclaredField("cookie");
                field.setAccessible(true); field.set(null, oldCookie);
            } catch (Exception ignored) { }
        }
        SharedPreferences.Editor lan = LanSyncService.settings(this).edit();
        for (String name : new String[] {"enabled", "pair_key", "receiver_id"}) restoreValue(lan, name, oldLan.get(name));
        lan.commit(); CredentialHealth.reset();
    }
    private static void restoreValue(SharedPreferences.Editor editor, String name, Object value) {
        if (value instanceof String) editor.putString(name, (String) value);
        else if (value instanceof Long) editor.putLong(name, (Long) value);
        else if (value instanceof Boolean) editor.putBoolean(name, (Boolean) value);
        else editor.remove(name);
    }
    private static void require(boolean value, String message) throws Exception { if (!value) throw new Exception(message); }
}
