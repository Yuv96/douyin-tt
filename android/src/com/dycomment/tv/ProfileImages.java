package com.dycomment.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/**
 * Per-profile image ownership, reused by the upstream ABI without retaining an Activity globally.
 */
public final class ProfileImages {
    private static final int TAG = 0x7f0f7a59;

    public static void bind(Activity a, String url, ImageView view) {
        if (a.isFinishing() || a.isDestroyed()) return;
        Object item = a.getWindow().getDecorView().getTag(TAG);
        PreviewImages images =
                item instanceof PreviewImages ? (PreviewImages) item : new PreviewImages(true);
        a.getWindow().getDecorView().setTag(TAG, images);
        images.bind(view, url);
    }

    public static void fromWorker(Activity a, String url, ImageView view) {
        new Handler(Looper.getMainLooper()).post(() -> bind(a, url, view));
    }

    public static void close(Activity a) {
        ProfileFeed.cancel(a);
        Object item = a.getWindow().getDecorView().getTag(TAG);
        if (item instanceof PreviewImages) ((PreviewImages) item).close();
        a.getWindow().getDecorView().setTag(TAG, null);
        try {
            ((Handler) InteractionController.field(a, "mainHandler"))
                    .removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }
    }

    static void cancelPending(Activity a) {
        Object item = a.getWindow().getDecorView().getTag(TAG);
        if (item instanceof PreviewImages) ((PreviewImages) item).cancelPending();
    }

    static void release(ImageView view) { PreviewImages.release(view); }
}
