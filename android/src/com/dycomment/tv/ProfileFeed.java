package com.dycomment.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One visible page at a time; likes must not wait for an entire account history. */
public final class ProfileFeed {
    private static final int TAG = 0x7f0f7a60;
    private static final String PATH = "/aweme/v1/web/aweme/favorite/";
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2));
    static { WORK.allowCoreThreadTimeOut(true); }

    private static final class State {
        final WeakReference<Activity> owner;
        final String session, secUid;
        final Handler main = new Handler(Looper.getMainLooper());
        final Set<String> ids = new HashSet<>();
        final Set<String> cursors = new HashSet<>();
        String cursor = "0";
        boolean loading, more = true, failed, closed;
        int generation;
        Future<?> pending;
        State(Activity activity, String uid) {
            owner = new WeakReference<>(activity);
            session = SocialApi.cookie();
            secUid = uid;
            cursors.add(cursor);
        }
    }

    public static void cancel(Activity activity) {
        ProfileGrid.close(activity);
        ProfileImages.cancelPending(activity);
        Object value = activity.getWindow().getDecorView().getTag(TAG);
        if (!(value instanceof State)) return;
        State state = (State) value;
        state.closed = true;
        state.generation++;
        state.main.removeCallbacksAndMessages(null);
        if (state.pending != null) state.pending.cancel(true);
        WORK.purge();
        try {
            TextView status = (TextView) field(activity, "tvStatus");
            status.setOnClickListener(null);
            status.setClickable(false);
            status.setFocusable(false);
        } catch (Exception ignored) { }
        activity.getWindow().getDecorView().setTag(TAG, null);
    }

    public static void likes(Activity activity) {
        cancel(activity);
        try {
            String uid = (String) field(activity, "secUid");
            State state = new State(activity, uid == null ? "" : uid);
            activity.getWindow().getDecorView().setTag(TAG, state);
            load(state);
        } catch (Exception error) {
            android.util.Log.e("ProfileFeed", "Unable to open likes", error);
        }
    }

    private static boolean active(State state) {
        Activity activity = state.owner.get();
        return !state.closed && activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow().getDecorView().getTag(TAG) == state
                && state.session.equals(SocialApi.cookie());
    }

    static void more(Activity activity) {
        Object value = activity.getWindow().getDecorView().getTag(TAG);
        if (value instanceof State && !((State) value).failed) load((State) value);
    }

    private static void status(State state, String message, boolean retry) {
        Activity activity = state.owner.get();
        if (!active(state)) return;
        try {
            TextView label = (TextView) field(activity, "tvStatus");
            label.setText(message);
            label.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
            label.setFocusable(retry);
            label.setOnClickListener(retry ? view -> load(state) : null);
            label.setClickable(retry);
            if (retry) {
                // A later-page retry belongs where scrolling stopped, below the retained cards.
                if (!state.ids.isEmpty() && label.getParent() instanceof android.widget.LinearLayout) {
                    android.widget.LinearLayout container = (android.widget.LinearLayout) label.getParent();
                    container.removeView(label);
                    container.addView(label);
                }
                UiTheme.alignControl(label);
                LegacyTheme.setupFocus(label);
            }
        } catch (Exception ignored) { }
    }

    private static void load(State state) {
        if (!active(state) || state.loading || !state.more) return;
        if (state.session.isEmpty() || state.secUid.isEmpty()) {
            status(state, "账号信息暂不可用，请重新打开主页", false);
            return;
        }
        state.loading = true;
        state.failed = false;
        final int generation = ++state.generation;
        final String cursor = state.cursor;
        status(state, state.ids.isEmpty() ? "加载喜欢…" : "", false);
        Runnable timeout = () -> {
            if (!active(state) || state.generation != generation || !state.loading) return;
            state.generation++;
            if (state.pending != null) state.pending.cancel(true);
            fail(state);
        };
        state.main.postDelayed(timeout, 25000);
        try {
            state.pending = WORK.submit(() -> {
                Page page = null;
                try {
                    JSONObject response = SocialApi.request(PATH, SocialApi.params(
                            "sec_user_id", state.secUid, "max_cursor", cursor, "min_cursor", "0",
                            "count", "18", "publish_video_strategy_type", "2"), false, state.session);
                    page = parsePage(response, cursor);
                } catch (Exception ignored) { }
                final Page result = page;
                state.main.post(() -> {
                    if (!active(state) || state.generation != generation) return;
                    state.main.removeCallbacks(timeout);
                    state.loading = false;
                    if (result == null || (result.more && state.cursors.contains(result.cursor))) {
                        fail(state);
                        return;
                    }
                    try {
                        Activity activity = state.owner.get();
                        @SuppressWarnings("unchecked") List<Object> videos = (List<Object>) field(activity, "videoList");
                        int added = 0;
                        for (Object item : result.items) {
                            String id = (String) field(item, "awemeId");
                            if (state.ids.add(id)) { videos.add(item); added++; }
                        }
                        state.cursor = result.cursor;
                        state.cursors.add(result.cursor);
                        state.more = result.more;
                        // Stop a duplicate-only page from triggering an automatic request loop.
                        if (result.more && added == 0) { fail(state); return; }
                        LegacyTheme.profileGrid(activity);
                        status(state, videos.isEmpty() ? "暂无喜欢的视频" : "", false);
                    } catch (Exception error) { fail(state); }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            state.main.removeCallbacks(timeout);
            fail(state);
        }
    }

    private static void fail(State state) {
        state.loading = false;
        state.failed = true;
        status(state, "喜欢加载失败，重试", true);
    }

    static final class Page {
        final List<Object> items = new ArrayList<>();
        String cursor;
        boolean more;
    }

    static Page parsePage(JSONObject response, String cursor) throws Exception {
        if (!response.has("status_code") || response.optInt("status_code", -1) != 0)
            throw new Exception("likes rejected");
        JSONArray list = response.optJSONArray("aweme_list");
        if (list == null || list.length() > 100) throw new Exception("likes list missing or oversized");
        Page page = new Page();
        page.more = response.optInt("has_more", 0) == 1 || response.optBoolean("has_more", false);
        page.cursor = response.optString("max_cursor", "");
        if (page.more && (page.cursor.isEmpty() || page.cursor.equals(cursor)))
            throw new Exception("likes cursor did not advance");
        for (int i = 0; i < list.length(); i++) page.items.add(model(list.getJSONObject(i)));
        return page;
    }

    private static Object model(JSONObject item) throws Exception {
        String id = item.optString("aweme_id", "");
        if (id.isEmpty()) throw new Exception("video id missing");
        JSONObject author = item.optJSONObject("author");
        if (author == null) author = new JSONObject();
        Class<?> type = Class.forName("com.dycomment.tv.DouyinApi$FeedItem");
        Object value = type.getConstructor(String.class, String.class, String.class, String.class)
                .newInstance(id, item.optString("desc", ""), author.optString("nickname", ""), author.optString("sec_uid", ""));
        for (Field field : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && field.get(value) == null) field.set(value, "");
        }
        JSONObject video = item.optJSONObject("video");
        if (video == null) video = new JSONObject();
        String cover = url(video.optJSONObject("origin_cover"));
        if (cover.isEmpty()) cover = url(video.optJSONObject("cover"));
        if (cover.isEmpty()) cover = url(video.optJSONObject("dynamic_cover"));
        set(value, "coverUrl", cover);
        set(value, "avatarUrl", url(author.optJSONObject("avatar_thumb")));
        set(value, "videoUrl", url(video.optJSONObject("play_addr")));
        set(value, "videoWidth", video.optInt("width"));
        set(value, "videoHeight", video.optInt("height"));
        set(value, "awemeType", item.optInt("aweme_type"));
        set(value, "createTime", item.optLong("create_time"));
        set(value, "userDigged", item.optInt("user_digged", -1));
        JSONObject stats = item.optJSONObject("statistics");
        if (stats == null) stats = new JSONObject();
        set(value, "likeCount", stats.optInt("digg_count", -1));
        set(value, "commentCount", stats.optInt("comment_count", -1));
        set(value, "collectCount", stats.optInt("collect_count", -1));
        set(value, "shareCount", stats.optInt("share_count", -1));
        JSONArray images = item.optJSONArray("images");
        List<String> imageUrls = new ArrayList<>();
        if (images != null) for (int i = 0; i < images.length(); i++) {
            String image = url(images.optJSONObject(i));
            if (!image.isEmpty()) imageUrls.add(image);
        }
        set(value, "imageUrls", imageUrls);
        if (cover.isEmpty() && !imageUrls.isEmpty()) set(value, "coverUrl", imageUrls.get(0));
        JSONObject music = item.optJSONObject("music");
        if (music != null) set(value, "musicUrl", url(music.optJSONObject("play_url")));
        return value;
    }

    private static String url(JSONObject object) {
        JSONArray urls = object == null ? null : object.optJSONArray("url_list");
        if (urls != null) for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i, "");
            if (url.startsWith("https://")) return url;
        }
        return "";
    }

    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
