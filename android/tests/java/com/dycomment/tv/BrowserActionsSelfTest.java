package com.dycomment.tv;

import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** CI-only: encrypted real receiver routes plus deterministic queue lifecycle scenarios. */
final class BrowserActionsSelfTest {
    private static final String RECEIVER = "00112233445566778899aabbccddeeff";
    private static final String CHALLENGE = "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2M";
    private static final byte[] KEY = LanSyncService.unhex("000102030405060708090a0b0c0d0e0f");
    private static final String POLL = "/v1/browser/poll", RESULT = "/v1/browser/result";
    private static void require(boolean value, String detail) {
        if (!value) throw new IllegalStateException("browser actions: " + detail);
    }
    private static final class Time implements BrowserActionBroker.Clock {
        volatile long value = 1000;
        @Override public long now() { return value; }
    }
    private static JSONObject args(String video) throws Exception {
        return new JSONObject().put("video_id", video).put("enabled", true);
    }
    private static JSONObject poll(String cookie) throws Exception {
        return new JSONObject().put("challenge", CHALLENGE)
                .put("session_binding", BrowserActionBroker.sessionBinding(cookie))
                .put("capabilities", new JSONArray().put("like").put("dislike").put("comments"));
    }
    private static JSONObject result(JSONObject job, boolean ok, String error) throws Exception {
        return new JSONObject().put("challenge", CHALLENGE).put("job_id", job.getString("job_id"))
                .put("session_binding", job.getString("session_binding")).put("ok", ok)
                .put("data", ok ? new JSONObject().put("confirmed", true).put("enabled", true) : new JSONObject())
                .put("error_code", error);
    }
    private static JSONObject commentData(int count) throws Exception {
        char[] chars = new char[1000]; java.util.Arrays.fill(chars, 'x');
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) rows.put(new JSONObject().put("cid", String.valueOf(900 + i))
                .put("text", new String(chars)).put("digg_count", -1)
                .put("user", new JSONObject().put("nickname", "评论作者😀")
                        .put("avatar_thumb", new JSONObject().put("url_list", new JSONArray().put("https://example.test/avatar.jpg")))));
        return new JSONObject().put("comments", rows).put("cursor", count).put("has_more", count > 0);
    }
    private static JSONObject commentsResult(JSONObject job, int count) throws Exception {
        return result(job, true, "").put("data", commentData(count));
    }
    private static final class Frame {
        final String id = UUID.randomUUID().toString().replace("-", "");
        JSONObject body;
    }
    private static Frame frame(String receiver, String direction, JSONObject plain) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        return frame(receiver, direction, plain, nonce);
    }
    private static Frame frame(String receiver, String direction, JSONObject plain, byte[] nonce) throws Exception {
        Frame frame = new Frame();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("mydv-browser/v1/" + direction + "\n" + receiver + "\n" + frame.id).getBytes(StandardCharsets.UTF_8));
        frame.body = new JSONObject().put("version", 1).put("request_id", frame.id)
                .put("nonce", LanCredentialServer.encode(nonce))
                .put("payload", LanCredentialServer.encode(cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8))));
        return frame;
    }
    private static JSONObject decode(String receiver, Frame frame, JSONObject envelope) throws Exception {
        require(BrowserActionBroker.exact(envelope, "version", "request_id", "nonce", "payload"), "response envelope schema");
        require(envelope.getString("request_id").equals(frame.id), "response request binding");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                new GCMParameterSpec(128, LanCredentialServer.decode(envelope.getString("nonce"))));
        cipher.updateAAD(("mydv-browser/v1/response\n" + receiver + "\n" + frame.id).getBytes(StandardCharsets.UTF_8));
        JSONObject decoded = new JSONObject(new String(cipher.doFinal(LanCredentialServer.decode(envelope.getString("payload"))), StandardCharsets.UTF_8));
        require(decoded.getString("request_id").equals(frame.id), "authenticated request binding");
        return decoded;
    }
    private static JSONObject send(BrowserActionBroker broker, String route, JSONObject plain) throws Exception {
        Frame request = frame(RECEIVER, "request", plain);
        return decode(RECEIVER, request, broker.handle(route, request.body, CHALLENGE));
    }
    private static final class Waiting {
        final CountDownLatch done = new CountDownLatch(1);
        volatile JSONObject data;
        volatile String error;
        Waiting(Context context, BrowserActionBroker broker, String cookie, String video) {
            this(context, broker, cookie, video, "like");
        }
        Waiting(Context context, BrowserActionBroker broker, String cookie, String video, String kind) {
            new Thread(() -> {
                try {
                    JSONObject arguments = kind.equals("dislike") ? new JSONObject().put("video_id", video) : args(video);
                    if (kind.equals("comments")) arguments = new JSONObject().put("video_id", video).put("cursor", 0).put("count", 20);
                    if (broker == null) data = BrowserActionBroker.perform(context, kind, arguments, cookie);
                    else {
                        java.lang.reflect.Method await = BrowserActionBroker.class.getDeclaredMethod("await", String.class, JSONObject.class, String.class);
                        await.setAccessible(true);
                        data = (JSONObject) await.invoke(broker, kind, arguments, cookie);
                    }
                } catch (Exception failure) {
                    Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
                    error = cause instanceof BrowserActionBroker.Failure ? ((BrowserActionBroker.Failure) cause).code : "unexpected";
                } finally { done.countDown(); }
            }, "browser-actions-fixture").start();
        }
        void error(String expected) throws Exception {
            require(done.await(2, TimeUnit.SECONDS) && expected.equals(error) && data == null, "bounded failure: " + expected);
        }
        void success() throws Exception {
            require(done.await(2, TimeUnit.SECONDS) && error == null && data != null && data.getBoolean("confirmed"), "verified result releases waiter");
        }
        void comments(int count) throws Exception {
            require(done.await(2, TimeUnit.SECONDS) && error == null && data != null && !data.has("confirmed")
                    && data.getJSONArray("comments").length() == count, "bounded read data releases waiter without write confirmation");
        }
    }
    private static JSONObject claim(BrowserActionBroker broker, String cookie) throws Exception {
        long until = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < until) {
            JSONObject response = send(broker, POLL, poll(cookie));
            if (!response.isNull("job")) return response.getJSONObject("job");
            Thread.sleep(10);
        }
        throw new IllegalStateException("browser actions: fixture job was not queued");
    }

    static void run(Context context, JSONObject challenge) throws Exception {
        String cookie = CredentialStore.snapshot(context).cookie;
        require(!BrowserActionBroker.sessionBinding(cookie).isEmpty(), "fixture has a session");
        require(BrowserActionBroker.sessionBinding("sessionid=x").equals(BrowserActionBroker.sessionBinding("sessionid_ss=x")), "fallback uses same hash domain");
        require(BrowserActionBroker.sessionBinding("sessionid=x; sessionid=y").isEmpty(), "conflicting duplicate sessions rejected");
        require(BrowserActionBroker.validArgs("like", args("123")), "strict supported operation");
        require(!BrowserActionBroker.validArgs("like", args("123").put("extra", true)), "extra args rejected");
        require(!BrowserActionBroker.validArgs("like", args("123").put("enabled", "true")), "boolean coercion rejected");
        require(!BrowserActionBroker.validArgs("like", args("not-an-id")), "nondigit targets rejected");
        JSONObject readArgs = new JSONObject().put("video_id", "123").put("cursor", 0).put("count", 20);
        require(BrowserActionBroker.validArgs("comments", readArgs), "bounded comments read args");
        require(!BrowserActionBroker.validArgs("comments", new JSONObject(readArgs.toString()).put("cursor", 0.5)), "fractional cursor rejected");
        require(!BrowserActionBroker.validArgs("comments", new JSONObject(readArgs.toString()).put("count", 21)), "fixed read count enforced");
        require(!BrowserActionBroker.validComments(commentData(1).put("cursor", 0), 0), "read cursor must advance when more exists");
        JSONObject badAvatar = commentData(1);
        badAvatar.getJSONArray("comments").getJSONObject(0).getJSONObject("user").getJSONObject("avatar_thumb")
                .put("url_list", new JSONArray().put("http://example.test/avatar.jpg"));
        require(!BrowserActionBroker.validComments(badAvatar, 0), "read avatar requires HTTPS");
        for (String invalid : new String[] {"{\"a\":1,\"a\":2}", "{\"a\":1}{}", "{unquoted:true}"}) {
            boolean rejected = false;
            try { BrowserActionBroker.parse(invalid.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception expected) { rejected = true; }
            require(rejected, "strict JSON rejects duplicate keys, trailing content and lenient syntax");
        }
        realTransport(context, cookie, challenge);
        Time time = new Time();
        BrowserActionBroker broker = new BrowserActionBroker(context, RECEIVER, KEY, time);
        try {
            Frame first = frame(RECEIVER, "request", poll(cookie));
            decode(RECEIVER, first, broker.handle(POLL, first.body, CHALLENGE));
            rejected(broker, POLL, first.body, 401);
            byte[] repeated = LanCredentialServer.decode(first.body.getString("nonce"));
            rejected(broker, POLL, frame(RECEIVER, "request", poll(cookie), repeated).body, 401);
            rejected(broker, POLL, frame(RECEIVER, "response", poll(cookie)).body, 401);
            rejected(broker, POLL, frame(RECEIVER, "request", poll(cookie).put("extra", 1)).body, 400);
            rejected(broker, POLL, frame(RECEIVER, "request", poll(cookie).put("capabilities", new JSONArray().put("like").put("like"))).body, 400);
            Waiting confirmed = new Waiting(context, broker, cookie, "101");
            JSONObject job = claim(broker, cookie);
            require(send(broker, POLL, poll(cookie)).isNull("job"), "claimed operation is never replayed");
            JSONObject invalid = result(job, true, ""); invalid.getJSONObject("data").put("extra", true);
            rejected(broker, RESULT, frame(RECEIVER, "request", invalid).body, 400);
            require(confirmed.done.getCount() == 1, "malformed result does not update user action");
            // A delayed browser result retains its original bounded write lease.
            time.value += 9000;
            require(send(broker, RESULT, result(job, true, "")).getBoolean("accepted"), "9s claimed result retains its lease");
            confirmed.success();
            require(!send(broker, RESULT, result(job, true, "")).getBoolean("accepted"), "duplicate result ignored");
            JSONObject unknown = result(job, true, "").put("job_id", "00000000000000000000000000000000");
            require(!send(broker, RESULT, unknown).getBoolean("accepted"), "unknown job result ignored");

            send(broker, POLL, poll(cookie));
            Waiting slow = new Waiting(context, broker, cookie, "401");
            JSONObject slowJob = claim(broker, cookie);
            time.value += 20000;
            Waiting following = new Waiting(context, broker, cookie, "402");
            queued(broker, 2);
            require(send(broker, RESULT, result(slowJob, true, "")).getBoolean("accepted"),
                    "normal 20s execution does not make the connected desktop unavailable");
            slow.success();
            JSONObject followingJob = claim(broker, cookie);
            require(send(broker, RESULT, result(followingJob, true, "")).getBoolean("accepted"),
                    "queued request survives the serial execution heartbeat gap");
            following.success();
            send(broker, POLL, poll(cookie));
            time.value += 45000;
            new Waiting(context, broker, cookie, "403").error("unavailable");
            require(!new BrowserActionBroker.Failure("unavailable").getMessage().contains("在线"),
                    "generic SDK or transport unavailability is not reported as proof the computer is offline");

            send(broker, POLL, poll(cookie));
            boolean readHealth = CredentialHealth.needsRefresh();
            Waiting readFailure = new Waiting(context, broker, cookie, "501", "comments");
            JSONObject readJob = claim(broker, cookie);
            JSONObject malformedRead = commentsResult(readJob, 1);
            malformedRead.getJSONObject("data").getJSONArray("comments").getJSONObject(0).put("unexpected", true);
            rejected(broker, RESULT, frame(RECEIVER, "request", malformedRead).body, 400);
            require(readFailure.done.getCount() == 1 && readHealth == CredentialHealth.needsRefresh(), "malformed comments neither complete nor poison account health");
            require(send(broker, RESULT, result(readJob, false, "rejected")).getBoolean("accepted"), "read failure uses fixed error schema");
            readFailure.error("rejected");
            Waiting readRetry = new Waiting(context, broker, cookie, "501", "comments");
            JSONObject retriedRead = claim(broker, cookie);
            require(send(broker, RESULT, commentsResult(retriedRead, 20)).getBoolean("accepted"), "20-row payload larger than old 16KiB limit accepted");
            readRetry.comments(20);
            Waiting expiredRead = new Waiting(context, broker, cookie, "502", "comments");
            JSONObject expiredReadJob = claim(broker, cookie);
            time.value += 35001;
            require(!send(broker, RESULT, commentsResult(expiredReadJob, 1)).getBoolean("accepted"), "expired read result ignored");
            expiredRead.error("expired");

            send(broker, POLL, poll(cookie));
            Waiting timed = new Waiting(context, broker, cookie, "102");
            JSONObject late = claim(broker, cookie);
            time.value += 35001;
            require(!send(broker, RESULT, result(late, true, "")).getBoolean("accepted"), "expired result ignored");
            timed.error("unconfirmed");
            send(broker, POLL, poll(cookie));
            new Waiting(context, broker, cookie, "102").error("cooldown");
            new Waiting(context, broker, cookie, "201", "dislike").error("cooldown");
            require(send(broker, POLL, poll(cookie)).isNull("job"), "uncertain write cannot immediately execute again");
            Waiting pausedRead = new Waiting(context, broker, cookie, "503", "comments");
            JSONObject pausedReadJob = claim(broker, cookie);
            require(send(broker, RESULT, commentsResult(pausedReadJob, 1)).getBoolean("accepted"), "comments remain available during write pause");
            pausedRead.comments(1);
            long pausedGeneration = CredentialStore.snapshot(context).generation;
            CredentialStore.save(context, cookie);
            require(CredentialStore.snapshot(context).generation == pausedGeneration + 1
                    && CredentialStore.snapshot(context).cookie.equals(cookie), "write pause does not prevent credential refresh");
            send(broker, POLL, poll(cookie));
            new Waiting(context, broker, cookie, "202", "dislike").error("cooldown");
            SocialApi.validateAccountAt("http://127.0.0.1:18766", cookie);
            time.value += 119999;
            new Waiting(context, broker, cookie, "203").error("cooldown");
            time.value++;
            send(broker, POLL, poll(cookie));
            Waiting resumed = new Waiting(context, broker, cookie, "204");
            JSONObject resumedJob = claim(broker, cookie);
            require(send(broker, RESULT, result(resumedJob, true, "")).getBoolean("accepted"), "writes resume after the full 120s pause");
            resumed.success();

            Waiting changed = new Waiting(context, broker, cookie, "103");
            JSONObject old = claim(broker, cookie);
            CredentialStore.save(context, cookie); // Same account, new credential generation must also fence results.
            require(!send(broker, RESULT, result(old, true, "")).getBoolean("accepted"), "credential generation fences in-flight result");
            changed.error("account_changed");
            send(broker, POLL, poll(cookie));
            Waiting rejected = new Waiting(context, broker, cookie, "104");
            JSONObject rejectedJob = claim(broker, cookie);
            Waiting pausedQueue = new Waiting(context, broker, cookie, "205", "dislike");
            Waiting retainedRead = new Waiting(context, broker, cookie, "504", "comments");
            queued(broker, 3);
            boolean healthBefore = CredentialHealth.needsRefresh();
            require(send(broker, RESULT, result(rejectedJob, false, "rejected")).getBoolean("accepted"), "fixed error result accepted");
            rejected.error("rejected");
            pausedQueue.error("cooldown");
            JSONObject retainedReadJob = claim(broker, cookie);
            require(retainedReadJob.getString("kind").equals("comments")
                    && send(broker, RESULT, commentsResult(retainedReadJob, 1)).getBoolean("accepted"), "write rejection retains queued reads");
            retainedRead.comments(1);
            new Waiting(context, broker, cookie, "206", "dislike").error("cooldown");
            require(healthBefore == CredentialHealth.needsRefresh() && cookie.equals(CredentialStore.snapshot(context).cookie),
                    "write rejection neither invalidates account health nor changes credentials");
            // A truly different logged-in session clears the previous session's pause.
            CredentialStore.save(context, "sessionid=fixture-browser-new; msToken=fixture-new");
            send(broker, POLL, poll("sessionid=fixture-browser-new; msToken=fixture-new"));
            CredentialStore.save(context, cookie);
            send(broker, POLL, poll(cookie));

            Waiting switched = new Waiting(context, broker, cookie, "107");
            JSONObject switchedJob = claim(broker, cookie);
            CredentialStore.save(context, "sessionid=fixture-browser-other; msToken=fixture-other");
            require(!send(broker, RESULT, result(switchedJob, true, "")).getBoolean("accepted"), "changed account rejects the old browser result");
            switched.error("account_changed");
            CredentialStore.save(context, cookie);
            send(broker, POLL, poll(cookie));

            Waiting closing = new Waiting(context, broker, cookie, "105");
            claim(broker, cookie);
            Waiting queuedOne = new Waiting(context, broker, cookie, "108");
            Waiting queuedTwo = new Waiting(context, broker, cookie, "109");
            Waiting queuedThree = new Waiting(context, broker, cookie, "110");
            queued(broker, 4);
            new Waiting(context, broker, cookie, "111").error("busy");
            require(send(broker, POLL, poll(cookie)).isNull("job"), "one global claimed job even with queued work");
            broker.close();
            closing.error("unconfirmed");
            queuedOne.error("unavailable"); queuedTwo.error("unavailable"); queuedThree.error("unavailable");
            new Waiting(context, broker, cookie, "106").error("unavailable");
        } finally {
            broker.close();
            if (!cookie.equals(CredentialStore.snapshot(context).cookie)) CredentialStore.save(context, cookie);
        }
        android.util.Log.i("Android5LanSyncTest", "PASS API21_BROWSER_COMMENTS_READ_ISOLATION");
        android.util.Log.i("Android5LanSyncTest", "PASS API21_BROWSER_ACTIONS_ENCRYPTION_LEASE_FENCE");
    }

    private static void queued(BrowserActionBroker broker, int count) throws Exception {
        java.lang.reflect.Field field = BrowserActionBroker.class.getDeclaredField("jobs");
        field.setAccessible(true);
        long until = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < until) {
            synchronized (broker) { if (((java.util.List<?>) field.get(broker)).size() == count) return; }
            Thread.sleep(10);
        }
        throw new IllegalStateException("browser actions: bounded queue did not fill");
    }

    private static void rejected(BrowserActionBroker broker, String route, JSONObject envelope, int expected) throws Exception {
        try { broker.handle(route, envelope, CHALLENGE); }
        catch (BrowserActionBroker.ProtocolFailure failure) { require(failure.status == expected, "expected protocol rejection"); return; }
        throw new IllegalStateException("browser actions: malformed request accepted");
    }

    private static final class Response { int status; JSONObject body; }
    private static Response http(String route, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:18765" + route).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            connection.getOutputStream().write(bytes);
            Response response = new Response(); response.status = connection.getResponseCode();
            InputStream stream = response.status == 200 ? connection.getInputStream() : connection.getErrorStream();
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while (input != null && (count = input.read(buffer)) != -1) {
                    require(output.size() + count <= 16384, "bounded transport result"); output.write(buffer, 0, count);
                }
                response.body = new JSONObject(output.toString("UTF-8"));
            }
            return response;
        } finally { connection.disconnect(); }
    }
    private static void realTransport(Context context, String cookie, JSONObject challenge) throws Exception {
        String receiver = challenge.getString("receiver_id"), proof = challenge.getString("challenge");
        JSONObject poll = poll(cookie).put("challenge", proof);
        Frame advertised = frame(receiver, "request", poll);
        Response ready = http(POLL, advertised.body);
        require(ready.status == 200 && decode(receiver, advertised, ready.body).isNull("job"), "real encrypted polling route");
        require(http(POLL, advertised.body).status == 401, "real replay rejected");
        Waiting waiting = new Waiting(context, null, cookie, "100");
        JSONObject job = null;
        long until = SystemClock.elapsedRealtime() + 2000;
        while (job == null && SystemClock.elapsedRealtime() < until) {
            Frame request = frame(receiver, "request", poll);
            Response response = http(POLL, request.body);
            require(response.status == 200, "real claim response");
            job = decode(receiver, request, response.body).optJSONObject("job");
            if (job == null) Thread.sleep(10);
        }
        require(job != null, "real route claims local user intent");
        Frame complete = frame(receiver, "request", result(job, true, "").put("challenge", proof));
        Response accepted = http(RESULT, complete.body);
        require(accepted.status == 200 && decode(receiver, complete, accepted.body).getBoolean("accepted"), "real encrypted result route");
        waiting.success();
    }
}
