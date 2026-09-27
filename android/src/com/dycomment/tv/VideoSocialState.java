package com.dycomment.tv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.List;

/** One bounded read per selected video; the avatar and menu share confirmed state. */
final class VideoSocialState {
    private static final int TAG = 0x7f0f7a53;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int epoch = -1;
    private volatile int request;
    private String id = "", author = "", cookie = "";
    private boolean loading, attempted, videoItem;
    private SocialApi.State state;

    private VideoSocialState(Activity a) {
        activity = a;
    }

    static VideoSocialState get(Activity a) {
        Object value = a.getWindow().getDecorView().getTag(TAG);
        if (value instanceof VideoSocialState) return (VideoSocialState) value;
        VideoSocialState valueNew = new VideoSocialState(a);
        a.getWindow().getDecorView().setTag(TAG, valueNew);
        return valueNew;
    }

    private void sync() {
        int selected = PlaybackCoordinator.token(activity);
        String currentCookie = SocialApi.cookie();
        if (epoch == selected && cookie.equals(currentCookie)) return;
        epoch = selected;
        cookie = currentCookie;
        request++;
        state = null;
        loading = attempted = false;
        id = author = "";
        videoItem = false;
        try {
            List<?> feed = (List<?>) InteractionController.field(activity, "feedList");
            int index = (Integer) InteractionController.field(activity, "currentIndex");
            Object item = feed.get(index);
            id = InteractionController.text(item, "awemeId");
            author = InteractionController.text(item, "secUid");
            videoItem =
                    id.matches("[0-9]+")
                            && !InteractionController.text(item, "isLive").equals("true");
        } catch (Exception ignored) {
        }
        render();
    }

    static void selected(Activity a) {
        get(a).sync();
    }

    static void ready(Activity a) {
        VideoSocialState s = get(a);
        s.sync();
        if (!s.attempted) s.load();
    }

    static void menu(Activity a, boolean retry) {
        VideoSocialState s = get(a);
        s.sync();
        if (s.state != null && !retry) s.publish();
        else s.load();
    }

    private boolean valid(int token, int selection, String account) {
        return token == request
                && PlaybackCoordinator.valid(activity, selection)
                && account.equals(SocialApi.cookie());
    }

    private void load() {
        if (loading) {
            publish();
            return;
        }
        if (!SocialApi.personalCookie() || (!videoItem && author.isEmpty())) {
            publish();
            return;
        }
        loading = attempted = true;
        final int token = ++request, selection = epoch;
        final String video = id, secUid = author, account = cookie;
        final boolean readVideo = videoItem;
        publish();
        if (!SocialApi.submit(
                () -> {
                    if (token != request
                            || activity.isFinishing()
                            || activity.isDestroyed()
                            || !account.equals(SocialApi.cookie())) return;
                    SocialApi.State result = null;
                    try {
                        result =
                                readVideo
                                        ? SocialApi.state(video, secUid, account)
                                        : SocialApi.authorState(secUid, account);
                    } catch (Exception ignored) {
                    }
                    final SocialApi.State response = result;
                    main.post(
                            () -> {
                                if (!valid(token, selection, account)) return;
                                loading = false;
                                state = response;
                                publish();
                            });
                })) {
            loading = false;
            publish();
        }
    }

    static void confirmed(Activity a, int selection, String account, SocialApi.State response) {
        if (a.isFinishing() || a.isDestroyed()) return;
        VideoSocialState s = get(a);
        s.sync();
        if (s.epoch != selection || !s.cookie.equals(account) || response == null) return;
        s.request++; // An older background read must not overwrite a confirmed action.
        s.loading = false;
        s.state = response;
        s.publish();
    }

    private void publish() {
        render();
        InteractionController c = InteractionController.get(activity);
        if (c.panel == null
                || c.panel.closed
                || c.busy
                || !c.id.equals(id)
                || !c.secUid.equals(author)
                || !c.session.equals(cookie)) return;
        c.state = state;
        c.stateLoading = loading;
        c.labels();
    }

    static String label(int action, int value) {
        if (action == 1) {
            if (value == 1 || value == 2) return "已关注";
            if (value == 0) return "未关注";
            if (value == 4) return "关注待确认";
            return "关注 · 状态未知";
        }
        String verb = action == 0 ? "点赞" : "收藏";
        return value == 1 ? "已" + verb : value == 0 ? "未" + verb : verb + " · 状态未知";
    }

    private void render() {
        int badgeId =
                activity.getResources()
                        .getIdentifier("android5_follow_badge", "id", activity.getPackageName());
        TextView badge = activity.findViewById(badgeId);
        if (badge == null) return;
        int followed = state == null ? -1 : state.followed;
        String text =
                !SocialApi.personalCookie()
                        ? "未登录"
                        : loading ? "读取中" : followed < 0 ? "状态未知" : label(1, followed);
        badge.setText(text);
        badge.setContentDescription("作者关注状态：" + text);
        GradientDrawable background = new GradientDrawable();
        background.setColor(followed == 0 ? UiTheme.PINK : 0xee262626);
        background.setCornerRadius(ModernMenuHelper.dp(activity, 10));
        background.setStroke(ModernMenuHelper.dp(activity, 1), 0x99ffffff);
        badge.setBackground(background);
    }

    static void destroy(Activity a) {
        Object value = a.getWindow().getDecorView().getTag(TAG);
        if (value instanceof VideoSocialState) {
            VideoSocialState s = (VideoSocialState) value;
            s.request++;
            s.main.removeCallbacksAndMessages(null);
            s.cookie = "";
            s.state = null;
        }
        a.getWindow().getDecorView().setTag(TAG, null);
    }
}
