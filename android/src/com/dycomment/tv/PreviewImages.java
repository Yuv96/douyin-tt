package com.dycomment.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;

/** Small, recycled list thumbnails; no credentials are sent to image hosts. */
final class PreviewImages {
    private static final int REQUEST = 0x7f0f7a65;
    private static final class Request {
        volatile boolean cancelled;
        Runnable work;
        ThreadPoolExecutor executor;
    }
    private final boolean profile;
    PreviewImages() { this(false); }
    PreviewImages(boolean profile) {
        this.profile = profile;
        cache = new LruCache<String, Bitmap>((profile ? 4 : 2) * 1024 * 1024) {
            protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
        };
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor work =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(24),
                    new ThreadPoolExecutor.DiscardPolicy());
    private final LruCache<String, Bitmap> cache;
    private volatile int epoch;
    private volatile boolean closed;

    void bind(ImageView view, String url) {
        if (closed) return;
        release(view);
        final Request request = new Request();
        request.executor = work;
        view.setTag(REQUEST, request);
        view.setTag(url);
        view.setImageDrawable(null);
        view.setBackgroundColor(0x26ffffff);
        if (url == null || url.isEmpty()) return;
        int width = 320, height = 180;
        if (profile) {
            android.view.View parent = view.getParent() instanceof android.view.View
                    ? (android.view.View) view.getParent() : view;
            android.view.ViewGroup.LayoutParams box = parent.getLayoutParams();
            width = box != null && box.width > 0 ? box.width : ModernMenuHelper.dp(view.getContext(), 176);
            height = box != null && box.height > 0 ? box.height : Math.round(width * 4f / 3f);
        }
        final int targetWidth = Math.min(320, width), targetHeight = Math.min(432, height);
        final String cacheKey = url + "#" + targetWidth + "x" + targetHeight;
        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        final int token = epoch;
        final java.lang.ref.WeakReference<ImageView> target =
                new java.lang.ref.WeakReference<>(view);
        request.work = () -> {
                    if (closed || token != epoch || request.cancelled) return;
                    Bitmap bitmap = null;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(4000);
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", SocialApi.UA);
                        if (c.getResponseCode() != 200 || c.getContentLength() > 2 * 1024 * 1024)
                            return;
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (InputStream in = c.getInputStream()) {
                            byte[] buffer = new byte[8192];
                            int n;
                            long deadline = android.os.SystemClock.elapsedRealtime() + 6000;
                            while ((n = in.read(buffer)) != -1) {
                                if (bytes.size() + n > 2 * 1024 * 1024
                                        || request.cancelled || token != epoch
                                        || android.os.SystemClock.elapsedRealtime() > deadline)
                                    return;
                                bytes.write(buffer, 0, n);
                            }
                        }
                        byte[] data = bytes.toByteArray();
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                        if (opts.outWidth <= 0 || opts.outHeight <= 0 || opts.outWidth > 16384 || opts.outHeight > 16384) return;
                        opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, targetWidth, targetHeight);
                        opts.inJustDecodeBounds = false;
                        opts.inPreferredConfig = profile ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                        if (profile && bitmap != null) {
                            float scale = Math.min(1f, Math.min((float) targetWidth / bitmap.getWidth(), (float) targetHeight / bitmap.getHeight()));
                            int fittedWidth = Math.max(1, Math.round(bitmap.getWidth() * scale));
                            int fittedHeight = Math.max(1, Math.round(bitmap.getHeight() * scale));
                            if (fittedWidth != bitmap.getWidth() || fittedHeight != bitmap.getHeight()) {
                                Bitmap fitted = Bitmap.createScaledBitmap(bitmap, fittedWidth, fittedHeight, true);
                                if (fitted != bitmap) bitmap.recycle();
                                bitmap = fitted;
                            }
                        }
                    } catch (OutOfMemoryError memoryPressure) {
                        if (bitmap != null) bitmap.recycle();
                        bitmap = null;
                        cache.evictAll();
                    } catch (Exception ignored) {
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    final Bitmap result = bitmap;
                    main.post(
                            () -> {
                                if (result == null) return;
                                if (token != epoch || closed || request.cancelled) { result.recycle(); return; }
                                ImageView image = target.get();
                                cache.put(cacheKey, result);
                                if (image != null && image.getTag(REQUEST) == request)
                                    image.setImageBitmap(result);
                            });
                };
        work.execute(request.work);
    }

    static void release(ImageView view) {
        Object value = view.getTag(REQUEST);
        if (value instanceof Request) {
            Request request = (Request) value;
            request.cancelled = true;
            if (request.work != null) request.executor.remove(request.work);
        }
        view.setTag(REQUEST, null);
        view.setTag(null);
        view.setImageDrawable(null);
    }

    /** Decode enough pixels for a centered aspect-preserving fit, including portrait covers. */
    static int sampleSize(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        float fit = Math.min(1f, Math.min((float) targetWidth / sourceWidth, (float) targetHeight / sourceHeight));
        int width = Math.max(1, Math.round(sourceWidth * fit));
        int height = Math.max(1, Math.round(sourceHeight * fit));
        int sample = 1;
        while (sourceWidth / (sample * 2) >= width && sourceHeight / (sample * 2) >= height) sample *= 2;
        return sample;
    }

    void cancelPending() {
        epoch++;
        work.getQueue().clear();
    }

    void clear() {
        cancelPending();
        cache.evictAll();
    }

    void close() {
        closed = true;
        clear();
        work.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
