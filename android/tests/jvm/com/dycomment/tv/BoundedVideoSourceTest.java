package com.dycomment.tv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Pure JVM fixtures. Only an in-process loopback origin is contacted; run in Actions. */
public final class BoundedVideoSourceTest {
    private static final long MAX = 10L * 1024 * 1024;
    private static final int BLOCK = 128 * 1024;

    interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        String[] names = {"ten-second cache and switch hit", "ten-MiB and two-entry budget",
                "position and explicit-seek gate", "valid and malformed client ranges",
                "cancel retains committed blocks", "superseded prefetch stays cancelled",
                "failure has no automatic retry", "stale sockets and bounded workers"};
        Case[] cases = {BoundedVideoSourceTest::timeWindow, BoundedVideoSourceTest::byteBudget,
                BoundedVideoSourceTest::playbackGate, BoundedVideoSourceTest::ranges,
                BoundedVideoSourceTest::partialCancellation, BoundedVideoSourceTest::supersededPrefetch,
                BoundedVideoSourceTest::noRetry, BoundedVideoSourceTest::socketLifecycle};
        int failures = 0;
        for (int i = 0; i < cases.length; i++) {
            try { cases[i].run(); System.out.println("PASS " + names[i]); }
            catch (Throwable failure) {
                failures++;
                System.err.println("FAIL " + names[i]);
                failure.printStackTrace(System.err);
            }
        }
        // Failed cleanup must not strand a CI JVM with non-daemon executor threads.
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void timeWindow() throws Exception {
        try (Environment env = new Environment()) {
            Route video = env.origin.add("time", new Movie(32768, 24, 7));
            env.source.prefetch(video.url);
            await(() -> env.source.cachedBytes(video.url) == video.movie.limit, "ten-second prefix");
            require(video.movie.limit < MAX - video.movie.moov.length, "time limit must win");
            int before = video.requests.size();
            String selected = env.source.select(video.url);
            try (Reply reply = get(selected, "bytes=0-8191")) {
                require(reply.status == 206, "cached range status");
                equal(reply.read(8192), video.movie.bytes(0, 8192), "selected prefix cache hit");
            }
            require(video.requests.size() == before, "selection must reuse prefetched bytes");
            Thread.sleep(150);
            require(video.requests.size() == before, "prefetch must stop at ten seconds");
            for (Request request : video.requests)
                require(request.last < video.movie.limit, "prefetch exceeded time horizon");
        }
    }

    private static void byteBudget() throws Exception {
        try (Environment env = new Environment()) {
            Route a = env.origin.add("large-a", new Movie(2 * 1024 * 1024, 20, 11));
            Route b = env.origin.add("large-b", new Movie(2 * 1024 * 1024, 20, 13));
            Route c = env.origin.add("large-c", new Movie(2 * 1024 * 1024, 20, 17));
            env.source.prefetch(a.url);
            await(() -> env.source.cachedBytes(a.url) == a.movie.limit, "byte-bounded first prefix");
            require(a.movie.limit + a.movie.moov.length == MAX, "byte limit must win");
            env.source.select(a.url);
            env.source.prefetch(b.url);
            await(() -> env.source.cachedBytes(b.url) == b.movie.limit, "byte-bounded next prefix");
            require(env.diskBytes() + a.movie.moov.length + b.movie.moov.length <= 2 * MAX,
                    "current and next exceed total twenty-MiB budget");
            env.source.prefetch(c.url);
            await(() -> env.source.cachedBytes(c.url) == c.movie.limit, "replacement prefix");
            require(env.source.cachedBytes(b.url) == 0, "evicted next entry is retained");
            require(env.fileCount() <= 2, "eviction left orphan prefix files");
            require(env.diskBytes() + a.movie.moov.length + c.movie.moov.length <= 2 * MAX,
                    "replacement exceeded total cache budget");
        }
    }

    private static void playbackGate() throws Exception {
        try (Environment env = new Environment()) {
            Route video = env.origin.add("gate", new Movie(32768, 24, 19));
            env.source.prefetch(video.url);
            await(() -> env.source.cachedBytes(video.url) == video.movie.limit, "gate prefix");
            String selected = env.source.select(video.url);
            long offset = video.movie.limit;
            int before = video.requests.size();
            try (Reply reply = get(selected, "bytes=" + offset + "-" + (offset + 31))) {
                require(reply.status == 206, "waiting range status");
                reply.expectWaiting();
                env.source.position(4999);
                reply.expectWaiting();
                require(video.requests.size() == before, "origin was read before five-second position");
                env.source.position(5000);
                equal(reply.read(32), video.movie.bytes(offset, 32), "position must release range");
            }
            selected = env.source.select(video.url); // Every new selection starts locked again.
            offset += 4096;
            try (Reply reply = get(selected, "bytes=" + offset + "-" + (offset + 15))) {
                reply.expectWaiting();
                env.source.seek();
                equal(reply.read(16), video.movie.bytes(offset, 16), "explicit seek must release range");
            }
            require(env.source.cachedBytes(video.url) == video.movie.limit, "forwarding grew prefix cache");
        }
    }

    private static void ranges() throws Exception {
        try (Environment env = new Environment()) {
            Route video = env.origin.add("ranges", new Movie(1024, 24, 23));
            env.source.prefetch(video.url);
            await(() -> env.source.cachedBytes(video.url) == video.movie.limit, "range prefix");
            String selected = env.source.select(video.url);
            env.source.seek();
            try (Reply reply = get(selected, "bytes=7-35")) {
                require(reply.status == 206, "closed range status");
                require(("bytes 7-35/" + video.movie.size).equals(reply.headers.get("content-range")),
                        "closed Content-Range");
                equal(reply.read(29), video.movie.bytes(7, 29), "closed range bytes");
            }
            try (Reply reply = get(selected, "bytes=" + (video.movie.size - 10) + "-")) {
                require(reply.status == 206, "open range status");
                equal(reply.read(10), video.movie.bytes(video.movie.size - 10, 10), "open range bytes");
            }
            try (Reply reply = get(selected, "bytes=-13")) {
                require(reply.status == 206, "suffix range status");
                equal(reply.read(13), video.movie.bytes(video.movie.size - 13, 13), "suffix range bytes");
            }
            try (Reply reply = new Reply(selected, "HEAD", "Range: bytes=0-7\r\n")) {
                require(reply.status == 206 && "8".equals(reply.headers.get("content-length")), "HEAD metadata");
                require(reply.in.read() == -1, "HEAD streamed a body");
            }
            for (String bad : new String[]{"bytes=5-3", "bytes=-0", "bytes=0-1,3-4",
                    "bytes=invalid", "bytes=999999999999999999999999999-", "bytes=" + video.movie.size + "-"}) {
                int before = video.requests.size();
                try (Reply reply = get(selected, bad)) { reply.expectRejected(); }
                require(video.requests.size() == before, "bad client range reached origin");
            }
            Route direct = env.origin.add("no-ranges", new Movie(1024, 24, 29));
            direct.noRanges = true;
            for (String bad : new String[]{"bytes=invalid", "bytes=5-3", "bytes=-0"}) {
                try (Reply reply = get(env.source.select(direct.url), bad)) {
                    reply.expectRejected(); // No redirect may bypass syntax or semantic validation.
                }
            }
        }
    }

    private static void partialCancellation() throws Exception {
        try (Environment env = new Environment()) {
            Route video = env.origin.add("partial", new Movie(65536, 24, 31));
            video.gate = new Gate(4096 + BLOCK);
            env.source.prefetch(video.url);
            require(video.gate.entered.await(3, TimeUnit.SECONDS), "partial transfer did not reach gate");
            long committed = env.source.cachedBytes(video.url);
            require(committed == 4096 + BLOCK, "fixture must hold an uncommitted second block");
            env.source.pausePrefetch();
            video.gate.release.countDown();
            await(() -> env.executor("fetcher").getActiveCount() == 0, "cancelled fetch exits");
            require(env.source.cachedBytes(video.url) == committed, "cancellation discarded committed blocks");
            int before = video.requests.size();
            String selected = env.source.select(video.url);
            try (Reply reply = get(selected, "bytes=0-4095")) {
                equal(reply.read(4096), video.movie.bytes(0, 4096), "cancelled prefix cache hit");
            }
            require(video.requests.size() == before, "switch refetched retained prefix");
            try (Reply reply = get(selected, "bytes=" + committed + "-" + (committed + 15))) {
                equal(reply.read(16), video.movie.bytes(committed, 16), "foreground resumes partial cache");
            }
            require(video.requests.get(before).first == committed, "resume restarted from zero");
        }
    }

    private static void supersededPrefetch() throws Exception {
        try (Environment env = new Environment()) {
            Route a = env.origin.add("cancel-a", new Movie(65536, 24, 37));
            Route b = env.origin.add("cancel-b", new Movie(32768, 24, 41));
            a.gate = new Gate(4096);
            env.source.prefetch(a.url);
            require(a.gate.entered.await(3, TimeUnit.SECONDS), "old prefetch did not reach gate");
            long committed = env.source.cachedBytes(a.url);
            int before = a.requests.size();
            env.source.pausePrefetch();
            env.source.prefetch(b.url);
            a.gate.release.countDown();
            await(() -> env.source.cachedBytes(b.url) == b.movie.limit, "new prefetch completes");
            require(env.source.cachedBytes(a.url) == committed, "new prefetch re-enabled cancelled writer");
            require(a.requests.size() == before, "cancelled entry issued another origin request");
        }
    }

    private static void noRetry() throws Exception {
        try (Environment env = new Environment()) {
            Route video = env.origin.add("failure", new Movie(32768, 24, 43));
            video.fail = true;
            env.source.prefetch(video.url);
            await(() -> video.requests.size() == 1, "failed origin request");
            await(() -> env.executor("fetcher").getActiveCount() == 0, "failed prefetch exits");
            env.source.prefetch(video.url);
            Thread.sleep(200);
            require(video.requests.size() == 1, "failed prefetch retried automatically");
            require(env.source.cachedBytes(video.url) == 0, "failed response entered cache");
        }
        try (Environment env = new Environment()) {
            Route video = env.origin.add("timeout", new Movie(32768, 24, 59));
            video.gate = new Gate(0);
            env.source.prefetch(video.url);
            require(video.gate.entered.await(3, TimeUnit.SECONDS), "timeout fixture not reached");
            // A real read timeout is an InterruptedIOException, but is not a user cancellation.
            await(() -> env.executor("fetcher").getActiveCount() == 0, "timed-out prefetch exits");
            env.source.prefetch(video.url);
            Thread.sleep(150);
            require(video.requests.size() == 1, "read timeout triggered a hidden retry");
            require(env.source.cachedBytes(video.url) == 0, "timed-out body entered cache");
        }
    }

    private static void socketLifecycle() throws Exception {
        try (Environment env = new Environment()) {
            Route a = env.origin.add("socket-a", new Movie(32768, 24, 47));
            Route b = env.origin.add("socket-b", new Movie(32768, 24, 53));
            env.source.prefetch(a.url);
            await(() -> env.source.cachedBytes(a.url) == a.movie.limit, "old socket prefix");
            String old = env.source.select(a.url);
            int before = a.requests.size();
            try (Reply stale = get(old, "bytes=" + a.movie.limit + "-" + (a.movie.limit + 15))) {
                stale.expectWaiting();
                String current = env.source.select(b.url);
                stale.expectDisconnected();
                try (Reply reply = get(current, "bytes=0-31")) {
                    equal(reply.read(32), b.movie.bytes(0, 32), "new selection served wrong video");
                }
                try (Reply reply = get(old, "bytes=0-31")) { reply.expectRejected(); }
                require(a.requests.size() == before, "stale socket resumed origin read");
                URL local = new URL(current);
                List<Socket> held = new ArrayList<>();
                try {
                    for (int i = 0; i < 8; i++) held.add(new Socket(local.getHost(), local.getPort()));
                    Thread.sleep(100);
                    require(env.executor("clients").getMaximumPoolSize() <= 2, "unbounded client pool");
                    require(env.executor("clients").getLargestPoolSize() <= 2, "client thread growth");
                    require(env.executor("clients").getQueue().size() <= 2, "unbounded client queue");
                    require(env.executor("fetcher").getMaximumPoolSize() == 1, "parallel prefetch workers");
                    env.source.close();
                    for (Socket socket : held) {
                        socket.setSoTimeout(2000);
                        try { require(socket.getInputStream().read() == -1, "closed client still readable"); }
                        catch (SocketException expected) { }
                    }
                } finally { for (Socket socket : held) socket.close(); }
            }
        }
    }

    private static Reply get(String url, String range) throws IOException {
        return new Reply(url, "GET", range == null ? "" : "Range: " + range + "\r\n");
    }

    private static final class Reply implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final Map<String, String> headers = new HashMap<>();
        final int status;
        Reply(String address, String method, String extra) throws IOException {
            URL url = new URL(address);
            socket = new Socket(url.getHost(), url.getPort());
            socket.setSoTimeout(5000);
            in = socket.getInputStream();
            socket.getOutputStream().write((method + " " + url.getPath() + " HTTP/1.1\r\nHost: "
                    + url.getAuthority() + "\r\n" + extra + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String first = readLine(in);
            status = first.isEmpty() ? 0 : Integer.parseInt(first.split(" ")[1]);
            if (status != 0) for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                require(colon > 0, "malformed response header");
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        byte[] read(int count) throws IOException {
            socket.setSoTimeout(5000);
            byte[] bytes = new byte[count];
            new DataInputStream(in).readFully(bytes);
            return bytes;
        }
        void expectWaiting() throws IOException {
            socket.setSoTimeout(180);
            try { in.read(); throw new AssertionError("range was not held at budget"); }
            catch (SocketTimeoutException expected) { }
            finally { socket.setSoTimeout(5000); }
        }
        void expectRejected() throws IOException {
            require(status == 0 || status == 400 || status == 404 || status == 416, "invalid request streamed or redirected");
            require(in.read() == -1, "rejected request has video body");
        }
        void expectDisconnected() throws IOException {
            socket.setSoTimeout(2000);
            try { require(in.read() == -1, "stale socket streamed bytes"); }
            catch (SocketException expected) { }
        }
        public void close() throws IOException { socket.close(); }
    }

    private static final class Environment implements AutoCloseable {
        final Path directory = Files.createTempDirectory("bounded-video-fixture");
        final Origin origin = new Origin();
        final BoundedVideoSource source = new BoundedVideoSource(directory.toFile(), "synthetic-fixture");
        Environment() throws IOException { }
        ThreadPoolExecutor executor(String name) {
            try { return (ThreadPoolExecutor) pool(name); }
            catch (Exception failure) { throw new AssertionError("missing bounded executor", failure); }
        }
        ExecutorService pool(String name) throws Exception {
            Field field = BoundedVideoSource.class.getDeclaredField(name);
            field.setAccessible(true);
            return (ExecutorService) field.get(source);
        }
        long diskBytes() throws IOException {
            long sum = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) if (Files.isRegularFile(file)) sum += Files.size(file);
            }
            return sum;
        }
        int fileCount() throws IOException {
            int count = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) if (Files.isRegularFile(file)) count++;
            }
            return count;
        }
        public void close() throws Exception {
            source.close();
            origin.releaseGates();
            try {
                for (String name : new String[]{"fetcher", "clients", "canceller"})
                    require(pool(name).awaitTermination(7, TimeUnit.SECONDS), "source worker survived close: " + name);
            } finally {
                origin.close();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                    for (Path file : files) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(directory);
            }
        }
    }

    private static final class Request {
        final long first, last;
        Request(long first, long last) { this.first = first; this.last = last; }
    }
    private static final class Gate {
        final long first;
        final AtomicBoolean used = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate(long first) { this.first = first; }
    }
    private static final class Route {
        final Movie movie;
        final String url;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        volatile Gate gate;
        volatile boolean fail, noRanges;
        Route(Movie movie, String url) { this.movie = movie; this.url = url; }
    }
    private static final class Origin implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        final List<Route> routes = new CopyOnWriteArrayList<>();
        Origin() throws IOException { server.setExecutor(workers); server.start(); }
        Route add(String name, Movie movie) {
            Route route = new Route(movie, "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name);
            routes.add(route);
            server.createContext("/" + name, exchange -> serve(route, exchange));
            return route;
        }
        void serve(Route route, HttpExchange exchange) throws IOException {
            try {
                Matcher match = Pattern.compile("bytes=(\\d+)-(\\d+)")
                        .matcher(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
                if (!match.matches()) { exchange.sendResponseHeaders(400, -1); return; }
                long first = Long.parseLong(match.group(1));
                long last = Math.min(Long.parseLong(match.group(2)), route.movie.size - 1);
                route.requests.add(new Request(first, last));
                if (route.fail) { exchange.sendResponseHeaders(503, -1); return; }
                if (route.noRanges) { first = 0; last = route.movie.size - 1; }
                if (first > last) { exchange.sendResponseHeaders(416, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.getResponseHeaders().set("ETag", "\"synthetic-v1\"");
                if (!route.noRanges) exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + first + "-" + last + "/" + route.movie.size);
                exchange.sendResponseHeaders(route.noRanges ? 200 : 206, last - first + 1);
                Gate gate = route.gate;
                if (gate != null && first == gate.first && gate.used.compareAndSet(false, true)) {
                    gate.entered.countDown();
                    try { gate.release.await(6, TimeUnit.SECONDS); }
                    catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
                }
                OutputStream out = exchange.getResponseBody();
                while (first <= last) {
                    int count = (int) Math.min(32768, last - first + 1);
                    out.write(route.movie.bytes(first, count));
                    first += count;
                }
            } catch (IOException disconnected) {
                // Cancellation deliberately closes the peer before its body is complete.
            } finally { exchange.close(); }
        }
        void releaseGates() { for (Route route : routes) if (route.gate != null) route.gate.release.countDown(); }
        public void close() throws InterruptedException {
            releaseGates(); server.stop(0); workers.shutdownNow();
            require(workers.awaitTermination(3, TimeUnit.SECONDS), "fixture origin workers survived close");
        }
    }

    /** Fixed one-second samples; expected byte horizons do not call Mp4Window. */
    private static final class Movie {
        final byte[] head, moov;
        final long size, limit;
        final int seed;
        Movie(int sampleSize, int samples, int seed) throws IOException {
            this.seed = seed;
            byte[] ftyp = box("ftyp", ints(0x69736f6d, 0));
            byte[] initial = moov(sampleSize, samples, 0);
            long data = ftyp.length + initial.length + 8L;
            moov = moov(sampleSize, samples, data);
            head = concat(ftyp, moov, ints(8 + sampleSize * samples, 0x6d646174));
            size = data + (long) sampleSize * samples;
            limit = Math.min(data + (long) Math.min(10, samples) * sampleSize, MAX - moov.length);
        }
        byte[] bytes(long offset, int count) {
            byte[] result = new byte[count];
            for (int i = 0; i < count; i++) {
                long at = offset + i;
                result[i] = at < head.length ? head[(int) at] : (byte) (at * 31 + seed);
            }
            return result;
        }
        static byte[] moov(int sampleSize, int samples, long offset) throws IOException {
            byte[] mdhd = box("mdhd", ints(0, 0, 0, 1000, samples * 1000, 0));
            byte[] hdlr = box("hdlr", ints(0, 0, 0x76696465, 0, 0, 0));
            byte[] stbl = box("stbl", concat(box("stts", ints(0, 1, samples, 1000)),
                    box("stsz", ints(0, sampleSize, samples)), box("stsc", ints(0, 1, 1, samples, 1)),
                    box("stco", ints(0, 1, (int) offset))));
            return box("moov", box("trak", box("mdia", concat(mdhd, hdlr, box("minf", stbl)))));
        }
    }
    private static byte[] ints(int... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (int value : values) out.writeInt(value);
        return bytes.toByteArray();
    }
    private static byte[] box(String kind, byte[] body) throws IOException {
        return concat(ints(body.length + 8), kind.getBytes(StandardCharsets.US_ASCII), body);
    }
    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) bytes.write(part);
        return bytes.toByteArray();
    }
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int value = in.read();
            if (value < 0 || value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).trim();
            bytes.write(value);
        }
        throw new IOException("fixture response header too large");
    }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), "timed out: " + description);
    }
    private static void equal(byte[] actual, byte[] expected, String description) {
        require(Arrays.equals(actual, expected), description);
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
