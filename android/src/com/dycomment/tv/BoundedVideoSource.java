package com.dycomment.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.*;

/** Loopback range source. Only the current and next MP4 prefixes are retained. */
final class BoundedVideoSource implements Closeable {
    static final long BYTE_LIMIT = 10L * 1024 * 1024;
    static final long OPEN_AFTER_MS = 5000;
    private static final int BLOCK = 128 * 1024, MAX_MOOV = 1024 * 1024;
    private final File directory;
    private final String agent;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final ThreadPoolExecutor fetcher = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1),
            new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(2),
            new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService canceller = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8),
            new ThreadPoolExecutor.AbortPolicy());
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Set<HttpURLConnection> cancelling = Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private ServerSocket server;
    private volatile Entry active;
    private volatile boolean closed, prefetchAllowed;
    private volatile int selection, prefetchGeneration;
    private Entry wanted;
    private String accessId;
    private final ThreadLocal<Integer> requestEpoch = new ThreadLocal<Integer>();

    BoundedVideoSource(File directory, String agent) {
        this.directory = directory;
        this.agent = agent;
        directory.mkdirs();
        File[] old = directory.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private synchronized Entry entry(String url) throws IOException {
        String key = key(url);
        Entry e = entries.get(key);
        if (e != null) return e;
        new URL(url); // fail before creating a cache entry
        if (entries.size() >= 2) {
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry old = iterator.next();
                if (old != active) {
                    old.disposed = true;
                    old.file.delete();
                    iterator.remove();
                    cancel(old);
                    break;
                }
            }
        }
        e = new Entry(url);
        entries.put(key, e);
        return e;
    }

    private static String key(String url) { return url.replaceFirst("^https?://", ""); }

    synchronized String select(String url) throws IOException {
        deselect();
        active = entry(url);
        accessId = UUID.randomUUID().toString();
        active.unlocked = false;
        ensureServer();
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + accessId;
    }

    void position(long ms) {
        Entry e = active;
        if (e != null && ms >= e.resumeAfterMs) e.unlocked = true;
    }

    void seek() {
        Entry e = active;
        if (e != null) e.unlocked = true; // explicit seek is interest, never deadlock a seek beyond 10 s
    }

    synchronized void deselect() {
        selection++;
        Entry previous = active;
        active = null;
        pausePrefetch();
        if (previous != null) cancel(previous);
        final Socket[] old;
        synchronized (sockets) { old = sockets.toArray(new Socket[0]); }
        // Loopback sockets have no linger; close is bounded and wakes any blocked writer.
        for (Socket socket : old) try { socket.close(); } catch (IOException ignored) { }
    }

    synchronized void pausePrefetch() {
        prefetchAllowed = false;
        wanted = null;
        prefetchGeneration++;
        synchronized (this) {
            for (Entry e : entries.values()) if (e != active) cancel(e);
        }
    }

    synchronized void prefetch(String url) {
        if (closed) return;
        try {
            Entry e = entry(url);
            wanted = e;
            prefetchAllowed = true;
            enqueue(e);
        } catch (IOException ignored) { }
    }

    private void enqueue(final Entry e) {
        if (e == active || e.running || e.failed || e.disposed
                || (e.initialized && (e.direct || e.prefix >= e.limit))) return;
        e.running = true;
        e.backgroundTicket = prefetchGeneration;
        try { fetcher.execute(() -> {
            try {
                e.initialize(true);
                while (e.prefix < e.limit && !e.direct) e.fill(true);
            } catch (InterruptedIOException cancelled) {
                // Cancellation keeps committed blocks and does not consume a retry.
                if (!closed && !e.disposed && prefetchAllowed
                        && e.backgroundTicket == prefetchGeneration) e.failed = true;
            } catch (IOException failure) {
                if (!closed && !e.disposed && prefetchAllowed
                        && e.backgroundTicket == prefetchGeneration) e.failed = true;
            } catch (RuntimeException malformed) {
                e.failed = true;
            } finally {
                synchronized (BoundedVideoSource.this) {
                    e.running = false;
                    if (wanted != null && prefetchAllowed && !closed) enqueue(wanted);
                }
            }
        }); } catch (RejectedExecutionException busy) { e.running = false; }
    }

    private void cancel(Entry e) {
        final HttpURLConnection[] pending;
        synchronized (e.connections) {
            List<HttpURLConnection> fresh = new ArrayList<HttpURLConnection>();
            for (HttpURLConnection c : e.connections) if (cancelling.add(c)) fresh.add(c);
            pending = fresh.toArray(new HttpURLConnection[0]);
        }
        if (pending.length > 0) {
            try {
                canceller.execute(() -> {
                    for (HttpURLConnection c : pending) {
                        try { c.disconnect(); } finally { cancelling.remove(c); }
                    }
                });
            } catch (RejectedExecutionException busy) {
                // Upstream reads/connects retain finite deadlines. Never run disconnect on UI.
                for (HttpURLConnection c : pending) cancelling.remove(c);
            }
        }
    }

    private void ensureServer() throws IOException {
        if (server != null) return;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    final Socket socket = server.accept();
                    socket.setSoTimeout(5000);
                    socket.setSendBufferSize(32 * 1024);
                    sockets.add(socket);
                    try { clients.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException busy) { sockets.remove(socket); socket.close(); }
                } catch (IOException e) { break; }
            }
        }, "video-range-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int n = 0; n < 4096; n++) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            if (b == '\n') return new String(out.toByteArray(), StandardCharsets.US_ASCII).trim();
            out.write(b);
        }
        throw new IOException("header too large");
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            String[] request = line(in).split(" ");
            String range = null;
            int count = 0;
            for (String header = line(in); !header.isEmpty(); header = line(in)) {
                if (++count > 32) throw new IOException("too many headers");
                if (header.toLowerCase(Locale.US).startsWith("range:")) range = header.substring(6).trim();
            }
            final Entry e;
            final int token;
            synchronized (this) {
                e = active;
                token = selection;
                if (request.length != 3 || e == null || !request[1].equals("/" + accessId)
                        || !(request[0].equals("GET") || request[0].equals("HEAD"))) return;
            }
            Matcher requested = null;
            if (range != null) {
                requested = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                if (!requested.matches() || (requested.group(1).isEmpty() && requested.group(2).isEmpty())) return;
                if (!requested.group(1).isEmpty()) Long.parseLong(requested.group(1));
                if (!requested.group(2).isEmpty()) Long.parseLong(requested.group(2));
                if (requested.group(1).isEmpty() && Long.parseLong(requested.group(2)) == 0) return;
                if (!requested.group(1).isEmpty() && !requested.group(2).isEmpty()
                        && Long.parseLong(requested.group(1)) > Long.parseLong(requested.group(2))) return;
            }
            requestEpoch.set(token);
            e.initialize(false);
            if (!current(e, token)) return;
            OutputStream out = client.getOutputStream();
            if (e.direct) {
                out.write(("HTTP/1.1 302 Found\r\nLocation: " + e.url
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return;
            }
            long start = 0, end = e.size - 1;
            if (range != null) {
                Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) return;
                if (m.group(1).isEmpty()) start = Math.max(0, e.size - Long.parseLong(m.group(2)));
                else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
                }
            }
            if (start < 0 || start > end || start >= e.size) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + e.size
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String headers = "HTTP/1.1 " + (range == null ? "200 OK" : "206 Partial Content")
                + "\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: " + (end-start+1)
                + (range == null ? "" : "\r\nContent-Range: bytes " + start + "-" + end + "/" + e.size)
                + "\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (request[0].equals("HEAD")) return;
            byte[] block = new byte[BLOCK];
            while (start <= end && current(e, token)) {
                int n;
                if (start >= e.metadataAt && start < e.metadataAt + e.metadata.length) {
                    n = (int) Math.min(end-start+1, e.metadataAt+e.metadata.length-start);
                    out.write(e.metadata, (int) (start-e.metadataAt), n);
                } else if (start < e.limit) {
                    while (e.prefix <= start) e.fill(false);
                    n = (int) Math.min(Math.min(end-start+1, e.prefix-start), block.length);
                    try (RandomAccessFile file = new RandomAccessFile(e.file, "r")) {
                        file.seek(start);
                        file.readFully(block, 0, n);
                    }
                    out.write(block, 0, n);
                } else {
                    while (!e.unlocked && e.resumeAfterMs > 0 && current(e, token)) Thread.sleep(50);
                    if (!current(e, token)) break;
                    e.forward(start, end, out, token);
                    return;
                }
                start += n;
                out.flush();
            }
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            // A disconnected range produces a native player error. No recursive retry here.
        } finally { requestEpoch.remove(); sockets.remove(socket); }
    }

    private boolean current(Entry e, int token) {
        return !closed && !e.disposed && active == e && selection == token;
    }

    long cachedBytes(String url) {
        synchronized (this) { Entry e = entries.get(key(url)); return e == null ? 0 : e.prefix; }
    }

    boolean prefixReady(String url) {
        synchronized (this) {
            Entry e = entries.get(key(url));
            return e != null && e.initialized && !e.direct && e.prefix >= e.limit;
        }
    }

    public synchronized void close() {
        closed = true;
        deselect();
        for (Entry e : entries.values()) { e.disposed = true; cancel(e); }
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        fetcher.shutdownNow();
        clients.shutdownNow();
        canceller.shutdown();
    }

    private final class Entry {
        final String url, id = UUID.randomUUID().toString();
        final File file = new File(directory, id + ".prefix");
        final ReentrantLock io = new ReentrantLock(true);
        final Set<HttpURLConnection> connections = new HashSet<HttpURLConnection>();
        volatile boolean disposed, unlocked, initialized, direct, running, failed;
        volatile int backgroundTicket;
        volatile long size = -1, prefix, limit;
        volatile long resumeAfterMs = OPEN_AFTER_MS;
        long moovAt = -1;
        byte[] moov = new byte[0];
        long metadataAt;
        byte[] metadata = new byte[0];
        String validator;
        Entry(String url) { this.url = url; }

        void allowed(boolean background) throws InterruptedIOException {
            if (closed || disposed || Thread.currentThread().isInterrupted()
                    || (background ? (active != this && (!prefetchAllowed || backgroundTicket != prefetchGeneration))
                        : (active != this || requestEpoch.get() == null || requestEpoch.get() != selection)))
                throw new InterruptedIOException("selection ended");
        }

        HttpURLConnection connect(long first, long last, boolean background) throws IOException {
            allowed(background);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            synchronized (connections) { connections.add(c); }
            try {
                c.setConnectTimeout(4000);
                c.setReadTimeout(3000);
                c.setRequestProperty("User-Agent", agent);
                c.setRequestProperty("Referer", "https://www.douyin.com/");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=" + first + "-" + last);
                if (validator != null) c.setRequestProperty("If-Range", validator);
                int code = c.getResponseCode();
                allowed(background);
                if (code == 200 && size < 0 && first == 0) {
                    direct = true; // streaming formats or origins without byte ranges stay native
                    return c;
                }
                if (code != 206) throw new IOException("range rejected");
                Matcher m = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)")
                    .matcher(String.valueOf(c.getHeaderField("Content-Range")));
                if (!m.matches()) throw new IOException("invalid range");
                long begin = Long.parseLong(m.group(1)), finish = Long.parseLong(m.group(2));
                long total = Long.parseLong(m.group(3));
                if (total <= 0 || begin != first || finish != Math.min(last, total-1)
                        || (size >= 0 && size != total)) throw new IOException("changed range");
                String tag = c.getHeaderField("ETag");
                if (validator != null && tag != null && !validator.equals(tag)) throw new IOException("changed media");
                if (tag != null && !tag.startsWith("W/")) validator = tag;
                size = total;
                return c;
            } catch (IOException | RuntimeException error) {
                disconnect(c);
                throw error;
            }
        }

        void disconnect(HttpURLConnection c) {
            c.disconnect();
            synchronized (connections) { connections.remove(c); }
        }

        byte[] fetch(long offset, int count, boolean background) throws IOException {
            HttpURLConnection c = connect(offset, offset + count - 1L, background);
            try {
                if (direct) return new byte[0];
                count = (int) Math.min(count, size-offset);
                byte[] bytes = new byte[count];
                try (InputStream in = c.getInputStream()) {
                    int done = 0;
                    while (done < count) {
                        allowed(background);
                        int n = in.read(bytes, done, count-done);
                        if (n < 0) throw new EOFException();
                        done += n;
                    }
                }
                return bytes;
            } finally { disconnect(c); }
        }

        void initialize(boolean background) throws IOException {
            io.lock();
            try {
                allowed(background);
                if (initialized) return;
                byte[] head = fetch(0, 4096, background);
                if (direct) { initialized = true; return; }
                if (head.length < 12 || type(head, 4) != 0x66747970) {
                    direct = true; initialized = true; return;
                }
                long offset = 0;
                for (int boxes = 0; boxes < 32 && offset <= size - 8; boxes++) {
                    byte[] header = offset <= head.length - 16
                        ? Arrays.copyOfRange(head, (int)offset, (int)offset+16)
                        : fetch(offset, (int)Math.min(16, size-offset), background);
                    long length = uint(header, 0);
                    if (length == 1) {
                        if (header.length < 16 || (header[8] & 128) != 0) throw new IOException("box overflow");
                        length = (uint(header, 8) << 32) | uint(header, 12);
                    } else if (length == 0) length = size-offset;
                    if (length < 8 || length > size-offset) throw new IOException("invalid box");
                    if (type(header, 4) == 0x6d6f6f76) {
                        if (length > MAX_MOOV) { direct = true; break; }
                        moovAt = offset;
                        moov = offset+length <= head.length
                            ? Arrays.copyOfRange(head, (int)offset, (int)(offset+length))
                            : fetch(offset, (int)length, background);
                        break;
                    }
                    offset += length;
                }
                if (moov.length == 0) { direct = true; initialized = true; return; }
                long end;
                try { end = Mp4Window.prefixEnd(moov, size, 10); }
                catch (IOException unsupported) { direct = true; initialized = true; return; }
                metadataAt = moovAt;
                metadata = moov;
                if (moovAt >= head.length) {
                    // Demuxers may probe a suffix beginning just before the tail index.
                    // Serve this small complete metadata window before the interest gate.
                    metadataAt = Math.max(0, moovAt - 65536);
                    long metadataEnd = moovAt + moov.length;
                    metadataEnd += Math.min(65536, size - metadataEnd);
                    int count = (int)(metadataEnd - metadataAt);
                    metadata = fetch(metadataAt, count, background);
                }
                // Account for the separate metadata copy as well as all prefix disk bytes.
                limit = Math.min(Math.min(end, size), BYTE_LIMIT-metadata.length);
                if (limit <= 0) throw new IOException("no media window");
                if (Mp4Window.prefixEnd(moov, size, 5) > limit) {
                    // A high bitrate can exhaust the byte budget before 5 s. Resume before
                    // that playable window runs out, rather than deadlocking on an unreachable time.
                    resumeAfterMs = 0;
                    for (int seconds = 1; seconds < 5; seconds++) {
                        if (Mp4Window.prefixEnd(moov, size, seconds) > limit) break;
                        resumeAfterMs = seconds * 500L;
                    }
                }
                moov = new byte[0];
                int initial = (int)Math.min(head.length, limit);
                if (prefix == 0) {
                    try (OutputStream out = new FileOutputStream(file)) { out.write(head, 0, initial); }
                    prefix = initial;
                }
                initialized = true;
            } finally {
                if (disposed) file.delete();
                io.unlock();
            }
        }

        void fill(boolean background) throws IOException {
            io.lock();
            try {
                allowed(background);
                if (prefix >= limit) return;
                byte[] data = fetch(prefix, (int)Math.min(BLOCK, limit-prefix), background);
                allowed(background);
                try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                    out.seek(prefix);
                    out.write(data);
                }
                prefix += data.length;
            } finally {
                if (disposed) file.delete();
                io.unlock();
            }
        }

        void forward(long offset, long end, OutputStream out, int token) throws IOException {
            HttpURLConnection c = connect(offset, end, false);
            try (InputStream in = c.getInputStream()) {
                byte[] bytes = new byte[32768];
                long remaining = end-offset+1;
                while (remaining > 0 && current(this, token)) {
                    int n = in.read(bytes, 0, (int)Math.min(bytes.length, remaining));
                    if (n < 0) throw new EOFException();
                    out.write(bytes, 0, n);
                    remaining -= n;
                }
            } finally { disconnect(c); }
        }
    }

    private static int type(byte[] bytes, int offset) throws IOException {
        return (int)uint(bytes, offset);
    }
    private static long uint(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || bytes.length-offset < 4) throw new IOException("short box");
        return ((long)(bytes[offset]&255)<<24) | ((long)(bytes[offset+1]&255)<<16)
            | ((long)(bytes[offset+2]&255)<<8) | (bytes[offset+3]&255);
    }
}
