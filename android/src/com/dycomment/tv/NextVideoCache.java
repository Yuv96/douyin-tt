package com.dycomment.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** One next MP4, up to 32 MiB; two completed entries, capped at 64 MiB. */
public final class NextVideoCache {
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/120.0.0.0 Safari/537.36";
    private static final long MAX_FILE = 32L * 1024 * 1024, TTL = 30L * 60 * 1000;
    private final File directory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(1),
                    new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ExecutorService canceller = Executors.newSingleThreadExecutor();
    private volatile HttpURLConnection cancelling;
    private long nextAllowed;
    private boolean resolving;
    private volatile int generation;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private String scheduledUrl;
    private File activeFile;
    private Runnable delayed;

    public NextVideoCache(Context context) {
        directory = new File(context.getCacheDir(), "next-video-v1");
        directory.mkdirs();
        File[] stale = directory.listFiles();
        if (stale != null)
            for (File f : stale)
                if (f.getName().endsWith(".part")
                        || System.currentTimeMillis() - f.lastModified() > TTL) f.delete();
    }

    static String originalUrl(String text) {
        Uri u = Uri.parse(text);
        if ("127.0.0.1".equals(u.getHost())
                && u.getPath() != null
                && u.getPath().startsWith("/video/")) {
            try {
                return new String(
                        Base64.decode(u.getPath().substring(7), Base64.URL_SAFE | Base64.NO_WRAP),
                        "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return text;
    }

    private File fileFor(String url) {
        try {
            // The original UI changes some HTTPS URLs to HTTP before passing them to PlayerView.
            String identity = url.replaceFirst("^https?://", "");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8"));
            StringBuilder key = new StringBuilder();
            for (byte b : hash) key.append(String.format(Locale.US, "%02x", b & 255));
            return new File(directory, key + ".mp4");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized File lookup(String url) {
        File f = fileFor(url);
        if (f.isFile() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < TTL) {
            activeFile = f;
            f.setLastModified(System.currentTimeMillis());
            Log.i("NextVideoCache", "CACHE_HIT bytes=" + f.length());
            return f;
        }
        activeFile = null;
        return null;
    }

    public void onSelection() {
        suspend();
        activeFile = null;
        nextAllowed = 0;
    }

    public void suspend() {
        generation++;
        scheduledUrl = null;
        resolving = false;
        worker.getQueue().clear();
        nextAllowed = android.os.SystemClock.elapsedRealtime() + 5000;
        if (delayed != null) {
            main.removeCallbacks(delayed);
            delayed = null;
        }
        final HttpURLConnection c = connection;
        // One cancellation per connection, never one new thread per buffering callback.
        if (c != null && c != cancelling && !canceller.isShutdown()) {
            cancelling = c;
            canceller.execute(
                    () -> {
                        try {
                            c.disconnect();
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    public void scheduleNext(Context context) {
        if (closed || delayed != null || scheduledUrl != null || resolving) return;
        final WeakReference<Context> ref = new WeakReference<Context>(context);
        final int token = generation;
        delayed =
                () -> {
                    delayed = null;
                    if (token != generation || closed) return;
                    Context c = ref.get();
                    if (c == null) return;
                    try {
                        List<?> feed = (List<?>) field(c, "feedList");
                        int index = ((Integer) field(c, "currentIndex")) + 1;
                        boolean vertical =
                                (Boolean) field(c, "filterVertical")
                                        && !(Boolean) field(c, "cameFromProfile");
                        Object item = null;
                        for (; index < feed.size(); index++) {
                            Object candidate = feed.get(index);
                            if (vertical
                                    && (Integer) field(candidate, "videoWidth") > 0
                                    && (Integer) field(candidate, "videoHeight")
                                            > (Integer) field(candidate, "videoWidth")) continue;
                            item = candidate;
                            break;
                        }
                        if (item == null
                                || (Boolean) field(item, "isLive")
                                || !((List<?>) field(item, "imageUrls")).isEmpty()) return;
                        String url = (String) field(item, "videoUrl");
                        if (url != null && !url.isEmpty()) {
                            prefetch(url);
                            return;
                        }
                        // Resolve a missing next-item URL before it is selected, using the app's
                        // existing API.
                        resolving = true;
                        final Object next = item;
                        Class<?> callback =
                                Class.forName("com.dycomment.tv.DouyinApi$DetailCallback");
                        Object listener =
                                Proxy.newProxyInstance(
                                        callback.getClassLoader(),
                                        new Class<?>[] {callback},
                                        (proxy, method, args) -> {
                                            if ((method.getName().equals("onResult")
                                                            || method.getName().equals("onError"))
                                                    && args != null)
                                                main.post(
                                                        () -> {
                                                            if (token != generation || closed)
                                                                return;
                                                            resolving = false;
                                                            if (!method.getName()
                                                                    .equals("onResult")) return;
                                                            try {
                                                                String resolved =
                                                                        (String)
                                                                                field(
                                                                                        args[0],
                                                                                        "videoUrl");
                                                                if (resolved != null
                                                                        && !resolved.isEmpty()) {
                                                                    Field f =
                                                                            next.getClass()
                                                                                    .getDeclaredField(
                                                                                            "videoUrl");
                                                                    f.setAccessible(true);
                                                                    f.set(next, resolved);
                                                                    prefetch(resolved);
                                                                }
                                                            } catch (Exception ignored) {
                                                            }
                                                        });
                                            return null;
                                        });
                        Class.forName("com.dycomment.tv.DouyinApi")
                                .getMethod("getVideoDetail", String.class, callback)
                                .invoke(null, (String) field(item, "awemeId"), listener);
                    } catch (Exception ignored) {
                        resolving = false; /* Non-feed contexts, such as the offline self-test. */
                    }
                };
        main.postDelayed(
                delayed, Math.max(1500, nextAllowed - android.os.SystemClock.elapsedRealtime()));
    }

    public void prefetch(String url) {
        if (closed || url == null || (!url.startsWith("http://") && !url.startsWith("https://")))
            return;
        if (url.equals(scheduledUrl)) return;
        scheduledUrl = url;
        File target = fileFor(url);
        if (target.isFile() && System.currentTimeMillis() - target.lastModified() < TTL) return;
        final int token = generation;
        worker.getQueue().clear();
        worker.execute(() -> download(url, target, token));
    }

    private void download(String url, File target, int token) {
        if (token != generation || closed) return;
        // Reserve room before creating the temporary file: active + download <= 64 MiB.
        synchronized (this) {
            File[] files = directory.listFiles((d, n) -> n.endsWith(".mp4"));
            if (files != null)
                for (File f : files) if (!f.equals(activeFile) && !f.equals(target)) f.delete();
            if (target.exists() && !target.equals(activeFile)) target.delete();
        }
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        File part = new File(target.getPath() + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            connection = c;
            if (token != generation || closed) return;
            c.setConnectTimeout(4000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (c.getResponseCode() != 200) return;
            long size;
            try {
                size = Long.parseLong(c.getHeaderField("Content-Length"));
            } catch (Exception unknown) {
                return;
            }
            if (token != generation || closed) return;
            if (size <= 0 || size > MAX_FILE) {
                Log.i("NextVideoCache", "SKIP_SIZE bytes=" + size);
                return;
            }
            if (directory.getUsableSpace() < size + 16L * 1024 * 1024) return;
            long total = 0, started = android.os.SystemClock.elapsedRealtime();
            try (InputStream in = c.getInputStream();
                    OutputStream out = new FileOutputStream(part)) {
                byte[] bytes = new byte[32768];
                int n;
                while ((n = in.read(bytes)) != -1) {
                    if (token != generation
                            || closed
                            || android.os.SystemClock.elapsedRealtime() - started > 20000) return;
                    total += n;
                    if (total > MAX_FILE) return;
                    out.write(bytes, 0, n);
                    // Limit background traffic to 512 KiB/s, and yield whenever foreground buffers.
                    long wait =
                            total * 1000 / (512 * 1024)
                                    - (android.os.SystemClock.elapsedRealtime() - started);
                    while (wait > 0) {
                        if (token != generation
                                || closed
                                || android.os.SystemClock.elapsedRealtime() - started > 20000)
                            return;
                        Thread.sleep(Math.min(wait, 50));
                        wait =
                                total * 1000 / (512 * 1024)
                                        - (android.os.SystemClock.elapsedRealtime() - started);
                    }
                }
            }
            if (total != size || token != generation || closed) return;
            try (RandomAccessFile f = new RandomAccessFile(part, "r")) {
                if (f.length() < 12) return;
                f.seek(4);
                if (f.readInt() != 0x66747970) return; // Only complete ISO BMFF/MP4 files.
            }
            synchronized (this) {
                if (token != generation || closed) return;
                if (part.renameTo(target)) {
                    prune(target);
                    Log.i("NextVideoCache", "CACHE_READY bytes=" + total);
                }
            }
        } catch (Exception e) {
            if (token == generation)
                Log.i("NextVideoCache", "PREFETCH_SKIPPED " + e.getClass().getSimpleName());
        } finally {
            part.delete();
            if (c != null) c.disconnect();
            if (connection == c) connection = null;
        }
    }

    private void prune(File keep) {
        File[] files = directory.listFiles((d, n) -> n.endsWith(".mp4"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int count = 0;
        long size = 0;
        if (activeFile != null && activeFile.exists() && !activeFile.equals(keep)) {
            count++;
            size += activeFile.length();
        }
        count++;
        size += keep.length();
        for (File f : files) {
            if (f.equals(keep) || f.equals(activeFile)) continue;
            if (count >= 2 || size + f.length() > MAX_FILE * 2) f.delete();
            else {
                count++;
                size += f.length();
            }
        }
    }

    public void close() {
        closed = true;
        suspend();
        worker.shutdownNow();
        canceller.shutdown();
    }
}
