package com.dycomment.tv;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local user intent queue. The paired official browser owns the actual write and verification. */
public final class BrowserActionBroker {
    static final int MAX_BODY = 131072;
    // The desktop executes one bounded job synchronously before polling again. Capability
    // freshness must cover that lease plus result delivery and the next polling round.
    private static final long DEADLINE = 35000, HEARTBEAT = DEADLINE + 10000, WRITE_COOLDOWN = 120000;
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(
            "like", "collect", "recommend", "follow", "dislike", "share", "comments"));
    private static final Set<String> ERRORS = new HashSet<>(Arrays.asList(
            "unsupported", "unavailable", "login_required", "account_changed", "expired",
            "rejected", "unconfirmed", "busy", "malformed", "cooldown"));
    private static volatile BrowserActionBroker active;
    interface Clock { long now(); }
    private final Clock clock;
    private final Context context;
    private final String receiver;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    private final List<Job> jobs = new ArrayList<>();
    private final Map<String, Long> uncertain = new HashMap<>();
    private final Set<String> requestIds = new HashSet<>(), nonces = new HashSet<>();
    private final Set<String> capabilities = new HashSet<>();
    private String challenge = "", cookie = "", binding = "";
    private long generation = -1, heartbeat;
    private String cooldownBinding = "";
    private long cooldownUntil;
    private boolean closed;

    public static final class Failure extends Exception {
        public final String code;
        Failure(String code) { super(message(code)); this.code = code; }
        private static String message(String code) {
            if (code.equals("unsupported")) return "电脑网页暂不支持此操作";
            if (code.equals("login_required")) return "请先在电脑登录并同步账号";
            if (code.equals("account_changed")) return "账号已变化，请重新打开视频";
            if (code.equals("busy")) return "上一项操作尚未完成";
            if (code.equals("cooldown")) return "互动暂缓，请稍后再试";
            if (code.equals("unconfirmed")) return "操作结果未确认，请先在电脑核对";
            if (code.equals("expired")) return "操作已超时，请在电脑核对后再试";
            if (code.equals("rejected")) return "官网未接受此操作";
            if (code.equals("malformed")) return "操作信息暂不可用";
            return "操作暂不可用，请稍后再试";
        }
    }

    static final class ProtocolFailure extends Exception {
        final int status;
        ProtocolFailure(int status) { this.status = status; }
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final String kind, binding, fingerprint;
        final JSONObject args;
        final long expires, generation;
        boolean claimed;
        JSONObject data;
        String error;
        Job(String kind, JSONObject args, String binding, long generation, long now) {
            this.kind = kind; this.args = args; this.binding = binding; this.generation = generation;
            expires = now + DEADLINE;
            fingerprint = binding + "/" + kind + "/" + args.optString(kind.equals("follow") ? "user_id" : "video_id")
                    + (kind.equals("share") ? "/" + args.optString("friend_id") : "");
        }
    }

    BrowserActionBroker(Context context, String receiver, byte[] key) {
        this(context, receiver, key, SystemClock::elapsedRealtime);
    }

    BrowserActionBroker(Context context, String receiver, byte[] key, Clock clock) {
        this.context = context.getApplicationContext(); this.receiver = receiver; this.key = key.clone();
        this.clock = clock;
    }

    static synchronized void register(BrowserActionBroker broker) {
        if (active != null && active != broker) active.close();
        active = broker;
    }

    synchronized void close() {
        closed = true;
        cancelAll("unavailable");
        capabilities.clear(); heartbeat = 0;
        if (active == this) active = null;
        Arrays.fill(key, (byte) 0);
        notifyAll();
    }

    /** Worker-only, one explicit user intent. Successful return contains verified data only. */
    public static JSONObject perform(Context context, String kind, JSONObject args, String expectedCookie) throws Failure {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new Failure("unavailable");
        BrowserActionBroker broker = active;
        if (broker == null) throw new Failure("unavailable");
        return broker.await(kind, args, expectedCookie);
    }

    public static boolean available(String kind, String expectedCookie) {
        BrowserActionBroker broker = active;
        if (broker == null) return false;
        synchronized (broker) {
            broker.maintain(broker.clock.now());
            return !broker.closed && broker.cookie.equals(expectedCookie) && !broker.binding.isEmpty()
                    && (!write(kind) || !broker.coolingDown(broker.clock.now()))
                    && broker.heartbeat > 0 && broker.capabilities.contains(kind);
        }
    }

    private synchronized JSONObject await(String kind, JSONObject args, String expectedCookie) throws Failure {
        long now = clock.now();
        maintain(now);
        if (closed) throw new Failure("unavailable");
        if (!cookie.equals(expectedCookie)) throw new Failure("account_changed");
        if (binding.isEmpty()) throw new Failure("login_required");
        if (!validArgs(kind, args)) throw new Failure("malformed");
        if (write(kind) && coolingDown(now)) throw new Failure("cooldown");
        if (heartbeat == 0) throw new Failure("unavailable");
        if (!capabilities.contains(kind)) throw new Failure("unsupported");
        JSONObject copy;
        try { copy = new JSONObject(args.toString()); } catch (Exception malformed) { throw new Failure("malformed"); }
        Job job = new Job(kind, copy, binding, generation, now);
        if (uncertain.containsKey(job.fingerprint)) throw new Failure("unconfirmed");
        for (Job other : jobs) if (other.fingerprint.equals(job.fingerprint)) throw new Failure("busy");
        if (jobs.size() >= 4) throw new Failure("busy");
        jobs.add(job);
        while (job.data == null && job.error == null) {
            maintain(clock.now());
            if (job.error != null) break;
            try { wait(Math.min(250, Math.max(1, job.expires - clock.now()))); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                finish(job, job.claimed && write(job.kind) ? "unconfirmed" : "unavailable", null);
            }
        }
        if (job.error != null) throw new Failure(job.error);
        return job.data;
    }

    private void maintain(long now) {
        CredentialStore.Snapshot current = CredentialStore.snapshot(context);
        if (current.generation != generation || !current.cookie.equals(cookie)) {
            cancelAll("account_changed");
            String nextBinding = sessionBinding(current.cookie);
            if (!nextBinding.equals(binding)) { cooldownBinding = ""; cooldownUntil = 0; }
            cookie = current.cookie; generation = current.generation; binding = nextBinding;
            capabilities.clear(); heartbeat = 0;
        }
        if (heartbeat > 0 && now - heartbeat >= HEARTBEAT) {
            // Capability freshness gates new writes. A claimed browser write keeps its own lease.
            for (Job job : new ArrayList<>(jobs)) if (!job.claimed) finish(job, "unavailable", null);
            capabilities.clear(); heartbeat = 0;
        }
        for (Job job : new ArrayList<>(jobs))
            if (now >= job.expires) finish(job, job.claimed && write(job.kind) ? "unconfirmed" : "expired", null);
        Iterator<Map.Entry<String, Long>> entries = uncertain.entrySet().iterator();
        while (entries.hasNext()) if (entries.next().getValue() <= now) entries.remove();
    }

    private void cancelAll(String reason) {
        for (Job job : new ArrayList<>(jobs))
            finish(job, job.claimed && write(job.kind) && !reason.equals("account_changed") ? "unconfirmed" : reason, null);
    }

    private void finish(Job job, String error, JSONObject data) {
        if (job.error != null || job.data != null) return;
        job.error = error; job.data = data;
        if (job.claimed && write(job.kind) && error != null)
            uncertain.put(job.fingerprint, clock.now() + DEADLINE);
        jobs.remove(job);
        if (!closed && job.claimed && write(job.kind) && ("rejected".equals(error) || "unconfirmed".equals(error) || "expired".equals(error))) {
            cooldownBinding = job.binding;
            cooldownUntil = Math.max(cooldownUntil, clock.now() + WRITE_COOLDOWN);
            for (Job queued : new ArrayList<>(jobs))
                if (!queued.claimed && write(queued.kind) && queued.binding.equals(job.binding)) finish(queued, "cooldown", null);
        }
        notifyAll();
    }

    private boolean coolingDown(long now) {
        return binding.equals(cooldownBinding) && now < cooldownUntil;
    }

    private static boolean write(String kind) { return !"comments".equals(kind); }

    static String sessionBinding(String cookie) {
        if (cookie == null) return "";
        String session = "", fallback = "";
        boolean hasSession = false, hasFallback = false;
        for (String part : cookie.split(";")) {
            int at = part.indexOf('=');
            if (at <= 0) continue;
            String name = part.substring(0, at).trim(), value = part.substring(at + 1).trim();
            if (name.equals("sessionid")) {
                if (hasSession && !session.equals(value)) return "";
                session = value; hasSession = true;
            } else if (name.equals("sessionid_ss")) {
                if (hasFallback && !fallback.equals(value)) return "";
                fallback = value; hasFallback = true;
            }
        }
        if (session.isEmpty()) session = fallback;
        if (session.isEmpty()) return "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    ("mydv-browser/session/v1\n" + session).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) hex.append(String.format(java.util.Locale.US, "%02x", value & 255));
            return hex.toString();
        } catch (Exception unavailable) { return ""; }
    }

    static boolean exact(JSONObject object, String... keys) {
        if (object == null || object.length() != keys.length) return false;
        for (String key : keys) if (!object.has(key)) return false;
        return true;
    }

    /** Android's JSONObject parser is lenient; the authenticated protocol accepts strict JSON only. */
    static JSONObject parse(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object object = value(reader, 0);
            if (!(object instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) throw new ProtocolFailure(400);
            return (JSONObject) object;
        }
    }

    private static Object value(JsonReader reader, int depth) throws Exception {
        if (depth > 8) throw new ProtocolFailure(400);
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name) || object.length() >= 8) throw new ProtocolFailure(400);
                    object.put(name, value(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (array.length() >= 20) throw new ProtocolFailure(400);
                    array.put(value(reader, depth + 1));
                }
                reader.endArray();
                return array;
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NUMBER:
                String number = reader.nextString();
                return number.matches("-?(0|[1-9][0-9]*)")
                        ? new java.math.BigInteger(number) : Double.valueOf(number);
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new ProtocolFailure(400);
        }
    }

    private static boolean string(JSONObject object, String key, String pattern) {
        return object.opt(key) instanceof String && ((String) object.opt(key)).matches(pattern);
    }

    private static boolean integer(Object value, long min, long max) {
        java.math.BigInteger number = whole(value);
        return number != null && number.compareTo(java.math.BigInteger.valueOf(min)) >= 0
                && number.compareTo(java.math.BigInteger.valueOf(max)) <= 0;
    }

    private static java.math.BigInteger whole(Object value) {
        if (value instanceof java.math.BigInteger) return (java.math.BigInteger) value;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            return java.math.BigInteger.valueOf(((Number) value).longValue());
        return null;
    }

    private static boolean boundedText(Object value, int max) {
        if (!(value instanceof String)) return false;
        String text = (String) value;
        return text.codePointCount(0, text.length()) <= max;
    }

    static boolean validComments(JSONObject data, long requestedCursor) {
        if (!exact(data, "comments", "cursor", "has_more") || !(data.opt("comments") instanceof JSONArray)
                || !integer(data.opt("cursor"), 0, Integer.MAX_VALUE) || !(data.opt("has_more") instanceof Boolean)) return false;
        if (data.optBoolean("has_more") && ((Number) data.opt("cursor")).longValue() <= requestedCursor) return false;
        JSONArray comments = data.optJSONArray("comments");
        if (comments.length() > 20) return false;
        for (int i = 0; i < comments.length(); i++) {
            JSONObject row = comments.optJSONObject(i);
            if (!exact(row, "cid", "text", "user", "digg_count") || !string(row, "cid", "[0-9]{1,32}")
                    || !boundedText(row.opt("text"), 1000)) return false;
            java.math.BigInteger likes = whole(row.opt("digg_count"));
            if (likes == null || likes.compareTo(java.math.BigInteger.valueOf(-1)) < 0) return false;
            JSONObject user = row.optJSONObject("user");
            if (!exact(user, "nickname", "avatar_thumb") || !boundedText(user.opt("nickname"), 64)) return false;
            JSONObject avatar = user.optJSONObject("avatar_thumb");
            if (!exact(avatar, "url_list") || !(avatar.opt("url_list") instanceof JSONArray)) return false;
            JSONArray urls = avatar.optJSONArray("url_list");
            if (urls.length() > 1) return false;
            if (urls.length() == 1) {
                if (!(urls.opt(0) instanceof String)) return false;
                String url = (String) urls.opt(0);
                if (!url.startsWith("https://") || url.length() > 512) return false;
                for (int j = 0; j < url.length(); j++) if (url.charAt(j) < 33 || url.charAt(j) > 126) return false;
                try {
                    java.net.URI parsed = new java.net.URI(url);
                    if (parsed.getHost() == null || parsed.getRawUserInfo() != null) return false;
                } catch (Exception malformed) { return false; }
            }
        }
        return true;
    }

    static boolean validArgs(String kind, JSONObject args) {
        if (!KINDS.contains(kind) || args == null) return false;
        if (kind.equals("comments")) return exact(args, "video_id", "cursor", "count")
                && string(args, "video_id", "[0-9]{1,32}") && integer(args.opt("cursor"), 0, Integer.MAX_VALUE)
                && integer(args.opt("count"), 20, 20);
        if (kind.equals("follow")) {
            if (!exact(args, "user_id", "sec_uid", "enabled") || !string(args, "user_id", "[0-9]{1,32}")
                    || !(args.opt("sec_uid") instanceof String) || !(args.opt("enabled") instanceof Boolean)) return false;
            String sec = (String) args.opt("sec_uid");
            if (sec.length() < 1 || sec.length() > 256) return false;
            for (int i = 0; i < sec.length(); i++) if (Character.isISOControl(sec.charAt(i))) return false;
            return true;
        }
        if (!string(args, "video_id", "[0-9]{1,32}")) return false;
        if (kind.equals("dislike")) return exact(args, "video_id");
        if (kind.equals("share")) return exact(args, "video_id", "friend_id") && string(args, "friend_id", "[0-9]{1,32}");
        return exact(args, "video_id", "enabled") && args.opt("enabled") instanceof Boolean;
    }

    /** The receiver supplies its current challenge under the receiver state lock. */
    synchronized JSONObject handle(String route, JSONObject wrapper, String currentChallenge) throws Exception {
        if (closed) throw new ProtocolFailure(401);
        if (!exact(wrapper, "version", "request_id", "nonce", "payload")
                || !(wrapper.opt("version") instanceof Number) || ((Number) wrapper.opt("version")).doubleValue() != 1
                || !string(wrapper, "request_id", "[a-f0-9]{32}")
                || !(wrapper.opt("nonce") instanceof String) || !(wrapper.opt("payload") instanceof String))
            throw new ProtocolFailure(400);
        String requestId = wrapper.getString("request_id"), nonce = wrapper.getString("nonce");
        byte[] iv, encrypted;
        try { iv = LanCredentialServer.decode(nonce); encrypted = LanCredentialServer.decode(wrapper.getString("payload")); }
        catch (Exception malformed) { throw new ProtocolFailure(400); }
        if (iv.length != 12 || encrypted.length < 16 || encrypted.length > MAX_BODY) throw new ProtocolFailure(400);
        JSONObject plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad("request", requestId));
            plain = parse(cipher.doFinal(encrypted));
        } catch (Exception invalid) { throw new ProtocolFailure(401); }
        if (!challenge.equals(currentChallenge)) { challenge = currentChallenge; requestIds.clear(); nonces.clear(); }
        if (!string(plain, "challenge", "[A-Za-z0-9_-]+") || !currentChallenge.equals(plain.getString("challenge"))
                || requestIds.size() >= 1024 || requestIds.contains(requestId) || nonces.contains(nonce))
            throw new ProtocolFailure(401);
        requestIds.add(requestId); nonces.add(nonce);
        JSONObject result;
        synchronized (CredentialStore.class) {
            maintain(clock.now());
            if (route.equals("/v1/browser/poll")) result = poll(plain);
            else if (route.equals("/v1/browser/result")) result = result(plain);
            else throw new ProtocolFailure(404);
        }
        result.put("request_id", requestId);
        byte[] responseIv = new byte[12]; random.nextBytes(responseIv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, responseIv));
        cipher.updateAAD(aad("response", requestId));
        return new JSONObject().put("version", 1).put("request_id", requestId)
                .put("nonce", LanCredentialServer.encode(responseIv))
                .put("payload", LanCredentialServer.encode(cipher.doFinal(result.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private byte[] aad(String direction, String id) {
        return ("mydv-browser/v1/" + direction + "\n" + receiver + "\n" + id).getBytes(StandardCharsets.UTF_8);
    }

    private JSONObject poll(JSONObject plain) throws Exception {
        if (!exact(plain, "challenge", "session_binding", "capabilities")
                || !string(plain, "session_binding", "[a-f0-9]{64}") || !(plain.opt("capabilities") instanceof JSONArray))
            throw new ProtocolFailure(400);
        JSONArray list = plain.getJSONArray("capabilities");
        Set<String> advertised = new HashSet<>();
        if (list.length() > KINDS.size()) throw new ProtocolFailure(400);
        for (int i = 0; i < list.length(); i++) {
            if (!(list.opt(i) instanceof String) || !KINDS.contains(list.getString(i)) || !advertised.add(list.getString(i)))
                throw new ProtocolFailure(400);
        }
        JSONObject response = new JSONObject().put("job", JSONObject.NULL);
        if (binding.isEmpty() || !binding.equals(plain.getString("session_binding"))) return response;
        heartbeat = clock.now(); capabilities.clear(); capabilities.addAll(advertised);
        for (Job job : jobs) if (job.claimed) return response;
        for (Job job : jobs) if (capabilities.contains(job.kind)) {
            job.claimed = true;
            return response.put("job", new JSONObject().put("job_id", job.id).put("kind", job.kind)
                    .put("args", job.args).put("session_binding", job.binding)
                    .put("expires_in_ms", Math.max(1, job.expires - clock.now())));
        }
        return response;
    }

    private JSONObject result(JSONObject plain) throws Exception {
        if (!exact(plain, "challenge", "job_id", "session_binding", "ok", "data", "error_code")
                || !string(plain, "job_id", "[a-f0-9]{32}") || !string(plain, "session_binding", "[a-f0-9]{64}")
                || !(plain.opt("ok") instanceof Boolean) || !(plain.opt("data") instanceof JSONObject)
                || !(plain.opt("error_code") instanceof String)) throw new ProtocolFailure(400);
        JSONObject response = new JSONObject().put("accepted", false);
        JSONObject data = plain.getJSONObject("data");
        String code = plain.getString("error_code");
        if (plain.getBoolean("ok")) {
            if (data.has("comments")) {
                if (!code.isEmpty() || !validComments(data, -1)) throw new ProtocolFailure(400);
            } else if (!code.isEmpty() || !(data.opt("confirmed") instanceof Boolean) || !data.getBoolean("confirmed")
                    || !(exact(data, "confirmed") || exact(data, "confirmed", "enabled"))
                    || (data.has("enabled") && !(data.opt("enabled") instanceof Boolean))) throw new ProtocolFailure(400);
        } else if (data.length() != 0 || !ERRORS.contains(code)) throw new ProtocolFailure(400);
        Job found = null;
        for (Job job : jobs) if (job.id.equals(plain.getString("job_id"))) found = job;
        if (found == null || !found.claimed || found.generation != generation || !binding.equals(found.binding)
                || !found.binding.equals(plain.getString("session_binding"))) return response;
        if (plain.getBoolean("ok")) {
            if (found.kind.equals("comments")) {
                if (!validComments(data, found.args.getLong("cursor"))) throw new ProtocolFailure(400);
            } else {
                boolean enabled = !found.kind.equals("dislike") && !found.kind.equals("share");
                if (!code.isEmpty() || !(data.opt("confirmed") instanceof Boolean) || !data.getBoolean("confirmed")
                        || !(enabled ? exact(data, "confirmed", "enabled") : exact(data, "confirmed"))
                        || (enabled && (!(data.opt("enabled") instanceof Boolean)
                        || data.getBoolean("enabled") != found.args.getBoolean("enabled")))) throw new ProtocolFailure(400);
            }
            finish(found, null, new JSONObject(data.toString()));
        } else {
            if (data.length() != 0 || !ERRORS.contains(code)) throw new ProtocolFailure(400);
            finish(found, code, null);
        }
        return response.put("accepted", true);
    }
}
