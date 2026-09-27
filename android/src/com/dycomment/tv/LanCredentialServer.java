package com.dycomment.tv;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Paired, encrypted local receiver. Connection closure never closes the listener. */
final class LanCredentialServer implements AutoCloseable {
    static final int PORT = 18765, MAX_BODY = 98304;
    interface Validator { void validate(String cookie) throws Exception; }
    interface Observer { void status(String text); }
    private final Context context;
    private final String receiverId;
    private final byte[] key;
    private final Validator validator;
    private final Observer observer;
    final LanPairingSession pairing;
    final BrowserActionBroker browserActions;
    private final SecureRandom random = new SecureRandom();
    private final Object state = new Object();
    private final Set<String> requestIds = new HashSet<>(), nonces = new HashSet<>();
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(2), new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor validation = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(1), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private ServerSocket listener;
    private String challenge;
    private long challengeStarted;

    LanCredentialServer(Context context, String receiverId, byte[] key, Validator validator, Observer observer) {
        if (!receiverId.matches("[a-f0-9]{32}") || key.length != 16) throw new IllegalArgumentException("Pairing data invalid");
        this.context = context.getApplicationContext(); this.receiverId = receiverId;
        this.key = key.clone(); this.validator = validator; this.observer = observer;
        this.pairing = new LanPairingSession(receiverId, key);
        this.browserActions = new BrowserActionBroker(this.context, receiverId, key);
    }

    void start() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 4);
        BrowserActionBroker.register(browserActions);
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    if (!privateAddress(socket.getInetAddress())) { socket.close(); continue; }
                    socket.setSoTimeout(5000);
                    sockets.add(socket);
                    try { clients.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException full) { sockets.remove(socket); socket.close(); }
                } catch (IOException stopped) { if (!closed) observer.status("接收连接暂不可用"); }
            }
        }, "lan-credential-listener");
        accept.setDaemon(true);
        accept.start();
    }

    static boolean privateAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] b = address.getAddress(); int first = b[0] & 255, second = b[1] & 255;
        return first == 127 || first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    static List<String> addresses() {
        List<String> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface network = all.nextElement();
                if (!network.isUp()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (privateAddress(address) && !address.isLoopbackAddress()) found.add(address.getHostAddress());
                }
            }
        } catch (SocketException unavailable) { }
        return found;
    }

    private void rotate() {
        long now = SystemClock.elapsedRealtime();
        if (challenge == null || now - challengeStarted >= 300000) {
            byte[] fresh = new byte[32]; random.nextBytes(fresh);
            challenge = encode(fresh); challengeStarted = now;
            requestIds.clear(); nonces.clear();
        }
    }

    private JSONObject currentChallenge() throws Exception {
        synchronized (state) {
            rotate();
            return new JSONObject().put("version", 1).put("receiver_id", receiverId)
                    .put("challenge", challenge).put("port", PORT);
        }
    }

    static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    static byte[] decode(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Encoding invalid");
        byte[] bytes = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (!encode(bytes).equals(value)) throw new IllegalArgumentException("Encoding invalid");
        return bytes;
    }

    private static final class HttpFailure extends Exception {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }

    private static void readTimeout(Socket socket, long deadline) throws IOException {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) throw new SocketTimeoutException();
        socket.setSoTimeout((int) Math.min(left, 5000));
    }

    private String line(Socket socket, InputStream input, int[] remaining, long deadline) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            readTimeout(socket, deadline);
            int next = input.read();
            if (next < 0 || --remaining[0] < 0) throw new HttpFailure(400);
            if (next == '\n') {
                byte[] value = bytes.toByteArray();
                if (value.length == 0 || value[value.length - 1] != '\r') throw new HttpFailure(400);
                return new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
            }
            if (next > 127 || next == 0) throw new HttpFailure(400);
            bytes.write(next);
        }
    }

    private boolean hostAllowed(String host) {
        if (host == null || !host.endsWith(":" + PORT)) return false;
        String address = host.substring(0, host.length() - (":" + PORT).length());
        if (address.equals("127.0.0.1")) return true;
        return addresses().contains(address);
    }

    private void serve(Socket socket) {
        try {
            Socket connection = socket;
            InputStream input = connection.getInputStream();
            long readDeadline = SystemClock.elapsedRealtime() + 5000;
            int[] remaining = {8192};
            String[] request = line(connection, input, remaining, readDeadline).split(" ", -1);
            if (request.length != 3 || !request[2].equals("HTTP/1.1")) throw new HttpFailure(400);
            Map<String, String> headers = new HashMap<>();
            String header;
            while (!(header = line(connection, input, remaining, readDeadline)).isEmpty()) {
                int at = header.indexOf(':');
                if (at <= 0 || header.charAt(0) == ' ' || header.charAt(0) == '\t') throw new HttpFailure(400);
                String name = header.substring(0, at).toLowerCase(Locale.US);
                if (headers.put(name, header.substring(at + 1).trim()) != null) throw new HttpFailure(400);
            }
            if (!hostAllowed(headers.get("host"))) throw new HttpFailure(403);
            if (headers.containsKey("transfer-encoding")) throw new HttpFailure(400);
            if (request[0].equals("GET") && request[1].equals("/v1/challenge")) {
                reply(connection, 200, currentChallenge().toString()); return;
            }
            boolean pairingRequest = request[1].equals("/v1/pair/begin") || request[1].equals("/v1/pair/reveal")
                    || request[1].equals("/v1/pair/status");
            boolean browserRequest = request[1].equals("/v1/browser/poll") || request[1].equals("/v1/browser/result");
            if (!request[0].equals("POST") || !(request[1].equals("/v1/credentials") || pairingRequest || browserRequest)) throw new HttpFailure(404);
            String type = headers.get("content-type");
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new HttpFailure(400);
            String length = headers.get("content-length");
            if (length == null || !length.matches("[0-9]{1,6}")) throw new HttpFailure(400);
            int count = Integer.parseInt(length);
            if (count < 1 || count > (pairingRequest ? 2048 : browserRequest ? BrowserActionBroker.MAX_BODY : MAX_BODY)) throw new HttpFailure(400);
            byte[] body = new byte[count]; int offset = 0;
            while (offset < count) {
                readTimeout(connection, readDeadline);
                int read = input.read(body, offset, count - offset);
                if (read < 0) throw new HttpFailure(400);
                offset += read;
            }
            JSONObject wrapper = browserRequest ? BrowserActionBroker.parse(body)
                    : new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (pairingRequest) reply(connection, 200, pairing.handle(request[1], wrapper).toString());
            else if (browserRequest) {
                JSONObject result;
                synchronized (state) {
                    rotate();
                    result = browserActions.handle(request[1], wrapper, challenge);
                }
                reply(connection, 200, result.toString());
            }
            else process(connection, wrapper);
        } catch (BrowserActionBroker.ProtocolFailure rejected) {
            try { reply(socket, rejected.status, "{}"); } catch (IOException gone) { }
        } catch (LanPairingSession.Failure rejected) {
            try { reply(socket, rejected.status, "{}"); } catch (IOException gone) { }
        } catch (HttpFailure rejected) {
            try { reply(socket, rejected.status, "{}"); } catch (IOException gone) { }
        } catch (Exception unavailable) {
            // No exception messages, request bodies or credentials enter logs.
            try { reply(socket, 400, "{}"); } catch (IOException gone) { }
        } finally {
            sockets.remove(socket);
            try { socket.close(); } catch (IOException gone) { }
        }
    }

    private void process(Socket socket, JSONObject wrapper) throws Exception {
        if (wrapper.length() != 4 || !(wrapper.opt("version") instanceof Number)
                || ((Number) wrapper.opt("version")).doubleValue() != 1d) { reply(socket, 400, "{}"); return; }
        String requestId = wrapper.optString("request_id"), nonce = wrapper.optString("nonce");
        if (!requestId.matches("[a-f0-9]{32}")) { reply(socket, 400, "{}"); return; }
        byte[] iv, encrypted;
        try { iv = decode(nonce); encrypted = decode(wrapper.optString("payload")); }
        catch (RuntimeException malformed) { reply(socket, 400, "{}"); return; }
        if (iv.length != 12 || encrypted.length < 16) { reply(socket, 400, "{}"); return; }
        JSONObject plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(("mydv-sync/v1\n" + receiverId + "\n" + requestId).getBytes(StandardCharsets.UTF_8));
            plaintext = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        } catch (Exception unauthenticated) { reply(socket, 401, "{}"); return; }
        synchronized (state) {
            rotate();
            if (closed || !challenge.equals(plaintext.optString("challenge")) || requestIds.size() >= 256
                    || requestIds.contains(requestId) || nonces.contains(nonce)) {
                reply(socket, 401, "{}"); return;
            }
            requestIds.add(requestId); nonces.add(nonce);
        }
        String cookie = plaintext.optString("cookie"), token = plaintext.optString("ms_token");
        if (plaintext.length() != 3 || !(plaintext.opt("cookie") instanceof String)
                || !(plaintext.opt("ms_token") instanceof String) || !validCookie(cookie)
                || !token.equals(CredentialStore.value(cookie, "msToken"))) {
            encryptedReply(socket, requestId, "rejected"); return;
        }
        final long deadline = SystemClock.elapsedRealtime() + 30000;
        final CredentialStore.Snapshot before = CredentialStore.snapshot(context);
        final long healthEpoch = CredentialHealth.observation(cookie);
        Future<?> task;
        try { task = validation.submit(() -> { validator.validate(cookie); return null; }); }
        catch (RejectedExecutionException full) { encryptedReply(socket, requestId, "rejected"); return; }
        try { task.get(25, TimeUnit.SECONDS); }
        catch (Exception failed) {
            task.cancel(true); validation.purge();
            observer.status("账号验证未通过，现有账号已保留");
            encryptedReply(socket, requestId, "rejected"); return;
        }
        String outcome = "rejected";
        try {
            synchronized (state) {
                synchronized (CredentialStore.class) {
                    CredentialStore.Snapshot now = CredentialStore.snapshot(context);
                    if (!closed && SystemClock.elapsedRealtime() < deadline && now.generation == before.generation
                            && now.cookie.equals(before.cookie)) {
                        if (now.cookie.equals(cookie) && now.msToken.equals(token)) {
                            CredentialHealth.verified(cookie, healthEpoch);
                            outcome = "unchanged";
                        }
                        else { CredentialStore.save(context, cookie); outcome = "updated"; }
                    }
                }
            }
        } catch (Exception failed) { }
        observer.status(outcome.equals("rejected") ? "同步未保存，现有账号已保留" : "账号已验证并同步");
        encryptedReply(socket, requestId, outcome);
    }

    static boolean validCookie(String cookie) {
        if (cookie == null || cookie.length() > 65536 || !CredentialStore.hasSession(cookie)) return false;
        for (int i = 0; i < cookie.length(); i++) if (cookie.charAt(i) < 32 || cookie.charAt(i) > 126) return false;
        for (String part : cookie.split(";", -1)) {
            int at = part.indexOf('=');
            if (at <= 0 || !part.substring(0, at).trim().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) return false;
        }
        return true;
    }

    private void encryptedReply(Socket socket, String requestId, String status) throws Exception {
        JSONObject result = new JSONObject().put("request_id", requestId).put("status", status)
                .put("generation", CredentialStore.snapshot(context).generation);
        byte[] iv = new byte[12]; random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("mydv-sync/v1/response\n" + receiverId + "\n" + requestId).getBytes(StandardCharsets.UTF_8));
        String payload = encode(cipher.doFinal(result.toString().getBytes(StandardCharsets.UTF_8)));
        reply(socket, 200, new JSONObject().put("version", 1).put("request_id", requestId)
                .put("nonce", encode(iv)).put("payload", payload).toString());
    }

    private void reply(Socket socket, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Rejected") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes); out.flush();
    }

    @Override public void close() {
        synchronized (state) { closed = true; }
        browserActions.close();
        pairing.destroy();
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        clients.shutdownNow(); validation.shutdownNow();
        synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { } sockets.clear(); }
    }
}
