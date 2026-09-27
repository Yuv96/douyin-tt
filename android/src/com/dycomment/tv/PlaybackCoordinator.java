package com.dycomment.tv;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import java.lang.reflect.Proxy;
import java.util.List;

/** Selection epochs cover network callbacks as well as native player events. */
public final class PlaybackCoordinator {
    private static final int TAG = 0x7f0f7a52;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int epoch;
    private boolean waiting, failed;
    private Runnable deadline;
    private final InfoCardState info = new InfoCardState();
    private final Runnable hideInfo = () -> renderMetadata();

    private void cancelLegacyInfoTimer() {
        try {
            Handler handler = (Handler) InteractionController.field(activity, "handler");
            handler.removeCallbacks(
                    (Runnable) InteractionController.field(activity, "hideOverlayRunnable"));
        } catch (Exception ignored) {
        }
    }

    private void renderMetadata() {
        cancelLegacyInfoTimer();
        main.removeCallbacks(hideInfo);
        long now = android.os.SystemClock.elapsedRealtime();
        try {
            View overlay = (View) InteractionController.field(activity, "infoOverlay");
            boolean visible = !waiting && !failed && info.visible(now);
            overlay.clearAnimation();
            overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
            InteractionController.field(activity, "isInfoVisible", visible);
        } catch (Exception ignored) {
        }
        if (info.remaining(now) > 0) main.postDelayed(hideInfo, info.remaining(now));
    }

    public static void reconcileMetadata(Activity a) {
        get(a).renderMetadata();
    }

    public static void metadataMenu(Activity a, boolean open) {
        if (host(a) == null) return;
        PlaybackCoordinator c = get(a);
        c.info.menu(open);
        c.renderMetadata();
    }

    private PlaybackCoordinator(Activity a) {
        activity = a;
    }

    static PlaybackCoordinator get(Activity a) {
        View decor = a.getWindow().getDecorView();
        Object value = decor.getTag(TAG);
        if (value instanceof PlaybackCoordinator) return (PlaybackCoordinator) value;
        PlaybackCoordinator c = new PlaybackCoordinator(a);
        decor.setTag(TAG, c);
        return c;
    }

    static Activity host(Context c) {
        // Synthetic tests can also exercise this bridge with matching fields.
        if (!(c instanceof Activity)) return null;
        try {
            InteractionController.field(c, "videoView");
            return (Activity) c;
        } catch (Exception e) {
            return null;
        }
    }

    private Object current() throws Exception {
        List<?> feed = (List<?>) InteractionController.field(activity, "feedList");
        int index = (Integer) InteractionController.field(activity, "currentIndex");
        return index < 0 || index >= feed.size() ? null : feed.get(index);
    }

    private PlayerView player() throws Exception {
        return (PlayerView) InteractionController.field(activity, "videoView");
    }

    private void call(String name) throws Exception {
        InteractionController.call(activity, name, new Class<?>[0]);
    }

    private void message(String text) {
        try {
            InteractionController.call(
                    activity, "showLoading", new Class<?>[] {String.class}, text);
        } catch (Exception ignored) {
        }
    }

    private void hideMetadata() {
        try {
            View overlay = (View) InteractionController.field(activity, "infoOverlay");
            overlay.clearAnimation();
            overlay.setVisibility(View.INVISIBLE);
            InteractionController.field(activity, "isInfoVisible", false);
        } catch (Exception ignored) {
        }
    }

    public static void trimFeed(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        try {
            List<?> feed = (List<?>) InteractionController.field(a, "feedList");
            int index = (Integer) InteractionController.field(a, "currentIndex");
            int remove = Math.min(Math.max(0, index - 50), Math.max(0, feed.size() - 400));
            if (remove > 0) {
                feed.subList(0, remove).clear();
                InteractionController.field(a, "currentIndex", index - remove);
            }
        } catch (Exception ignored) {
        }
    }

    public static void destroy(Activity a) {
        Object value = a.getWindow().getDecorView().getTag(TAG);
        if (value instanceof PlaybackCoordinator) {
            PlaybackCoordinator c = (PlaybackCoordinator) value;
            c.epoch++;
            c.waiting = false;
            c.main.removeCallbacksAndMessages(null);
            c.deadline = null;
            a.getWindow().getDecorView().setTag(TAG, null);
        }
    }

    public static int token(Activity a) {
        return get(a).epoch;
    }

    public static boolean valid(Activity a, int token) {
        return !a.isFinishing() && !a.isDestroyed() && get(a).epoch == token;
    }

    public static void selected(Activity a) {
        LiveChatController.stop(a);
        try {
            Object comments = InteractionController.field(a, "commentOverlay");
            if (comments instanceof CommentsPanel) ((CommentsPanel) comments).close(false);
        } catch (Exception ignored) { }
        PlaybackCoordinator c = get(a);
        c.epoch++;
        c.failed = false;
        c.info.selected();
        if (ModernMenuHelper.recallsMetadata(a)) c.info.menu(true);
        VideoSocialState.selected(a);
        c.main.removeCallbacks(c.hideInfo);
        c.cancelLegacyInfoTimer();
        c.hideMetadata();
        if (c.deadline != null) c.main.removeCallbacks(c.deadline);
        try {
            c.player().stopPlayback();
            Object item = c.current();
            c.waiting =
                    item != null
                            && ((List<?>) InteractionController.field(item, "imageUrls")).isEmpty();
            if (c.waiting) {
                c.hideMetadata();
                c.message("正在加载，可按上/下键切换");
                final int token = c.epoch;
                c.deadline =
                        () -> {
                            if (valid(a, token) && c.waiting) error(a);
                        };
                c.main.postDelayed(c.deadline, 22000);
            }
        } catch (Exception ignored) {
        }
    }

    /** Called after legacy metadata binding, before any asynchronous video request. */
    public static void bound(Activity a) {
        PlaybackCoordinator c = get(a);
        if (!c.waiting) c.info.ready(android.os.SystemClock.elapsedRealtime());
        c.renderMetadata();
        if (!c.waiting) VideoSocialState.ready(a);
    }

    public static boolean canHideLoading(Activity a) {
        PlaybackCoordinator c = get(a);
        return !c.waiting && !c.failed;
    }

    public static void loading(Context context) {
        Activity a = host(context);
        if (a == null) return;
        PlaybackCoordinator c = get(a);
        c.waiting = true;
        c.failed = false;
        c.hideMetadata();
        c.message("正在加载，可按上/下键切换");
    }

    public static void ready(Context context) {
        Activity a = host(context);
        if (a == null) return;
        PlaybackCoordinator c = get(a);
        c.waiting = false;
        c.failed = false;
        if (c.deadline != null) c.main.removeCallbacks(c.deadline);
        try {
            c.call("hideLoading");
        } catch (Exception ignored) {
        }
        c.info.ready(android.os.SystemClock.elapsedRealtime());
        c.renderMetadata();
        VideoSocialState.ready(a);
    }

    public static boolean error(Activity a) {
        PlaybackCoordinator c = get(a);
        c.epoch++;
        c.waiting = false;
        c.failed = true;
        if (c.deadline != null) c.main.removeCallbacks(c.deadline);
        try {
            c.player().stopPlayback();
            c.call("stopDanmakuScheduler");
            c.call("stopLiveDanmaku");
        } catch (Exception ignored) {
        }
        c.hideMetadata();
        c.message("加载失败或超时：上/下键切换，确定键重试");
        return true;
    }

    public static boolean retryKey(Activity a, KeyEvent e) {
        if (!get(a).failed || ModernMenuHelper.isMenuShowing()) return false;
        try {
            if ((Boolean) InteractionController.field(a, "menuShowing")) return false;
        } catch (Exception ignored) {
        }
        if (e.getKeyCode() != KeyEvent.KEYCODE_DPAD_CENTER
                && e.getKeyCode() != KeyEvent.KEYCODE_ENTER) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            try {
                int index = (Integer) InteractionController.field(a, "currentIndex");
                Object item = get(a).current();
                if (item != null && !((Boolean) InteractionController.field(item, "isLive")))
                    InteractionController.field(
                            item, "videoUrl", ""); // refresh expired CDN URL on explicit retry
                InteractionController.call(a, "playAt", new Class<?>[] {int.class}, index);
            } catch (Exception ex) {
                error(a);
            }
        }
        return true;
    }

    public static void detail(Activity a, Object item) {
        final PlaybackCoordinator c = get(a);
        final int token = c.epoch;
        try {
            if (c.current() != item) return;
            Class<?> callback = Class.forName("com.dycomment.tv.DouyinApi$DetailCallback");
            Object listener =
                    Proxy.newProxyInstance(
                            callback.getClassLoader(),
                            new Class<?>[] {callback},
                            (proxy, method, args) -> {
                                if (method.getName().equals("onResult")
                                        || method.getName().equals("onError"))
                                    c.main.post(
                                            () -> {
                                                if (!valid(a, token)) return;
                                                try {
                                                    if (c.current() != item) return;
                                                    if (!method.getName().equals("onResult")
                                                            || args == null
                                                            || args[0] == null) {
                                                        error(a);
                                                        return;
                                                    }
                                                    Object result = args[0];
                                                    String url =
                                                            InteractionController.text(
                                                                    result, "videoUrl");
                                                    if (url.isEmpty()) {
                                                        error(a);
                                                        return;
                                                    }
                                                    InteractionController.field(
                                                            item, "videoUrl", url);
                                                    InteractionController.call(
                                                            a,
                                                            "showDanmaku",
                                                            new Class<?>[] {item.getClass()},
                                                            item);
                                                    InteractionController.call(
                                                            a,
                                                            "playVideo",
                                                            new Class<?>[] {String.class},
                                                            url);
                                                } catch (Exception e) {
                                                    error(a);
                                                }
                                            });
                                return null;
                            });
            Class.forName("com.dycomment.tv.DouyinApi")
                    .getMethod("getVideoDetail", String.class, callback)
                    .invoke(null, InteractionController.text(item, "awemeId"), listener);
        } catch (Exception e) {
            error(a);
        }
    }
}
