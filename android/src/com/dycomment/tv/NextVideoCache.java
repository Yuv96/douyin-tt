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
import java.util.*;

/** Resolve one next item and retain its bounded, reusable MP4 prefix. */
public final class NextVideoCache {
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/120.0.0.0 Safari/537.36";
    private final BoundedVideoSource source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private long nextAllowed;
    private boolean resolving, closed;
    private String scheduledUrl;
    private Runnable delayed;

    public NextVideoCache(Context context) {
        File legacy = new File(context.getCacheDir(), "next-video-v1");
        File[] old = legacy.listFiles();
        if (old != null) for (File f : old) f.delete();
        legacy.delete();
        source = new BoundedVideoSource(new File(context.getCacheDir(), "video-prefix-v2"), USER_AGENT);
    }

    static String originalUrl(String text) {
        Uri u = Uri.parse(text);
        if ("127.0.0.1".equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/video/")) {
            try { return new String(Base64.decode(u.getPath().substring(7), Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8"); }
            catch (Exception ignored) { }
        }
        return text;
    }

    public String playbackUrl(Context context, String url) throws IOException {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url;
        String path = Uri.parse(url).getPath();
        if (path != null && (path.toLowerCase(Locale.US).endsWith(".m3u8")
                || path.toLowerCase(Locale.US).endsWith(".flv"))) return url;
        try {
            List<?> feed = (List<?>) field(context, "feedList");
            int index = (Integer) field(context, "currentIndex");
            if (index >= 0 && index < feed.size() && (Boolean) field(feed.get(index), "isLive")) return url;
        } catch (Exception ignored) { }
        long bytes = source.cachedBytes(url);
        String result = source.select(url);
        Log.i("NextVideoCache", "PREFIX_OPEN hit=" + (bytes > 0) + " bytes=" + bytes);
        return result;
    }

    public void position(long ms) { source.position(ms); }
    public void seek() { source.seek(); }

    public void onSelection() {
        suspend();
        source.deselect();
        nextAllowed = 0;
    }

    public void suspend() {
        generation++;
        scheduledUrl = null;
        resolving = false;
        nextAllowed = android.os.SystemClock.elapsedRealtime() + 250;
        if (delayed != null) { main.removeCallbacks(delayed); delayed = null; }
        source.pausePrefetch();
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
                delayed, Math.max(150, nextAllowed - android.os.SystemClock.elapsedRealtime()));
    }

    public void prefetch(String url) {
        if (closed || url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
        if (url.equals(scheduledUrl)) return;
        scheduledUrl = url;
        source.prefetch(originalUrl(url));
    }

    public void close() {
        closed = true;
        suspend();
        source.close();
    }
}
