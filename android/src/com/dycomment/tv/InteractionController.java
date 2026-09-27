package com.dycomment.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public final class InteractionController {
    private static final int TAG = 0x7f0f7a51;
    final Activity activity;
    final Handler handler = new Handler(Looper.getMainLooper());
    ModernMenuHelper.Panel panel;
    SocialApi.State state;
    Object item;
    String id = "", secUid = "", session = "";
    int generation;
    boolean busy, stateLoading, resumePlayback;
    private PlayerView pausedForMenu;
    private int pausedSelection = -1;
    long lastUnread;
    int unread = -1;
    int unreadGeneration;
    boolean unreadHealthy;
    final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);
    final Date clockDate = new Date();
    long clockMinute = -1;
    String clockTime = "";
    boolean unreadLoading, foreground;
    final java.util.concurrent.ExecutorService unreadWork =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    final Runnable unreadTick =
            new Runnable() {
                public void run() {
                    if (!foreground || activity.isFinishing() || activity.isDestroyed()) return;
                    updateClock(activity);
                    handler.postDelayed(this, 30000);
                }
            };

    public static void resumed(Activity a) {
        LanSyncService.startIfEnabled(a);
        InteractionController c = get(a);
        c.foreground = true;
        c.lastUnread = 0;
        c.handler.removeCallbacks(c.unreadTick);
        c.handler.post(c.unreadTick);
        CredentialHealth.attach(a);
        VideoSocialState.ready(a);
    }

    public static void paused(Activity a) {
        InteractionController c = get(a);
        c.foreground = false;
        c.handler.removeCallbacks(c.unreadTick);
        c.forgetMenuPause();
        CredentialHealth.detach(a);
        try {
            Object comments = field(a, "commentOverlay");
            if (comments instanceof CommentsPanel) ((CommentsPanel) comments).close(false);
        } catch (Exception ignored) { }
        LiveChatController.stop(a);
    }

    public static void destroyed(Activity a) {
        InteractionController c = get(a);
        c.foreground = false;
        c.handler.removeCallbacksAndMessages(null);
        c.unreadWork.shutdownNow();
        c.generation++;
        c.close(false);
        c.session = "";
        c.unreadSession = "";
        c.item = null;
        c.state = null;
        ModernMenuHelper.dismissCurrentMenu(a);
        PlaybackCoordinator.destroy(a);
        VideoSocialState.destroy(a);
        LiveChatController.destroy(a);
        CredentialHealth.detach(a);
        a.getWindow().getDecorView().setTag(TAG, null);
    }

    String unreadSession = "";

    InteractionController(Activity a) {
        activity = a;
    }

    static InteractionController get(Activity a) {
        View decor = a.getWindow().getDecorView();
        Object o = decor.getTag(TAG);
        if (o instanceof InteractionController) return (InteractionController) o;
        InteractionController c = new InteractionController(a);
        decor.setTag(TAG, c);
        return c;
    }

    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static String text(Object o, String name) {
        try {
            Object value = field(o, name);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    void invoke(String method) {
        try {
            call(activity, method, new Class<?>[0]);
        } catch (Exception e) {
            toast("该功能暂不可用");
        }
    }

    void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }

    void showing(boolean value) {
        try {
            field(activity, "menuShowing", value);
        } catch (Exception ignored) {
        }
    }

    void pause() {
        try {
            PlayerView player = (PlayerView) field(activity, "videoView");
            if (!((Boolean) field(activity, "menuShowing")) || pausedForMenu != player
                    || !PlaybackCoordinator.valid(activity, pausedSelection)) forgetMenuPause();
            boolean wanted = player.wantsPlayback();
            invoke("pauseForMenu");
            if (wanted && !player.wantsPlayback()) {
                pausedForMenu = player;
                pausedSelection = PlaybackCoordinator.token(activity);
                resumePlayback = true;
            }
        } catch (Exception ignored) {
            forgetMenuPause();
        }
        showing(true);
    }

    private void forgetMenuPause() {
        resumePlayback = false;
        pausedForMenu = null;
        pausedSelection = -1;
    }

    private void resumeMenuPlayback() {
        PlayerView player = pausedForMenu;
        boolean restore = resumePlayback && player != null
                && PlaybackCoordinator.valid(activity, pausedSelection);
        forgetMenuPause();
        if (!restore) return;
        try {
            if (field(activity, "videoView") != player) return;
            invoke("resumeFromMenu");
            // The preference may have been switched off inside settings after this menu
            // paused the player; the original playback intent still belongs to this menu.
            if (!player.wantsPlayback()) player.start();
        } catch (Exception ignored) { }
    }

    void close(boolean resume) {
        generation++;
        busy = false;
        stateLoading = false;
        if (panel != null) panel.close(false);
        panel = null;
        showing(false);
        if (resume) resumeMenuPlayback();
        else forgetMenuPause();
    }

    static boolean live(Activity activity) {
        try {
            List<?> feed = (List<?>) field(activity, "feedList");
            int index = (Integer) field(activity, "currentIndex");
            return index >= 0 && index < feed.size() && Boolean.TRUE.equals(field(feed.get(index), "isLive"));
        } catch (Exception ignored) { return false; }
    }

    public static void showQuick(Activity a) {
        get(a).quick();
    }

    public static void showSettings(Activity a) {
        get(a).settings();
    }

    /** Runs before focused views so MENU repeat never opens several overlays. */
    public static boolean handleKey(Activity a, KeyEvent e) {
        if (PlaybackCoordinator.retryKey(a, e)) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && live(a)
                && !ModernMenuHelper.isMenuShowing()) {
            try {
                if (field(a, "commentOverlay") == null) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) get(a).comments();
                    return true;
                }
            } catch (Exception ignored) { }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK
                && e.getAction() == KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0) {
            try {
                Object comments = field(a, "commentOverlay");
                if (comments instanceof CommentsPanel) {
                    ((CommentsPanel) comments).close(true);
                    return true;
                }
                if (!ModernMenuHelper.isMenuShowing()
                        && !((Boolean) field(a, "menuShowing"))
                        && !((Boolean) field(a, "cameFromProfile"))) {
                    get(a).settings();
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        if (e.getKeyCode() != KeyEvent.KEYCODE_MENU) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;
        InteractionController c = get(a);
        if (c.panel != null && !c.panel.closed) c.comments();
        else {
            try {
                View comments = (View) field(a, "commentOverlay");
                if (comments instanceof CommentsPanel) {
                    ((CommentsPanel) comments).close(true);
                    return true;
                }
                if (comments != null && comments.getParent() != null) {
                    ((ViewGroup) comments.getParent()).removeView(comments);
                    field(a, "commentOverlay", null);
                    c.showing(false);
                    c.resumeMenuPlayback();
                    return true;
                }
            } catch (Exception ignored) {
            }
            ModernMenuHelper.dismissCurrentMenu(a);
            c.quick();
        }
        return true;
    }

    void quick() {
        try {
            List<?> feed = (List<?>) field(activity, "feedList");
            int index = (Integer) field(activity, "currentIndex");
            if (index < 0 || index >= feed.size()) {
                toast("请等待视频加载");
                return;
            }
            item = feed.get(index);
            id = text(item, "awemeId");
            secUid = text(item, "secUid");
        } catch (Exception e) {
            toast("当前视频暂不可用");
            return;
        }
        if (live(activity)) { comments(); return; }
        pause();
        generation++;
        state = null;
        busy = false;
        stateLoading = false;
        session = SocialApi.cookie();
        panel =
                ModernMenuHelper.show(
                        activity,
                        "与作者互动",
                        new String[] {"点赞 · 读取中", "关注 · 读取中", "收藏 · 读取中", "主页", "分享", "推荐给朋友", "不喜欢"},
                        true,
                        true,
                        this::select,
                        () -> close(true),
                        this::comments);
        VideoSocialState.menu(activity, false);
    }

    void comments() {
        close(true);
        CommentsPanel.show(activity);
    }

    boolean active(int token, String cookie) {
        return !activity.isFinishing()
                && !activity.isDestroyed()
                && token == generation
                && panel != null
                && !panel.closed
                && cookie.equals(SocialApi.cookie());
    }

    void loadState() {
        if (!stateLoading) VideoSocialState.menu(activity, true);
    }

    void labels() {
        if (panel == null || panel.closed) return;
        String[] verbs = {"点赞", "关注", "收藏"};
        for (int action = 0; action < 3; action++) {
            int value =
                    state == null
                            ? -1
                            : action == 0
                                    ? state.liked
                                    : action == 1 ? state.followed : state.collected;
            String label =
                    !SocialApi.personalCookie()
                            ? verbs[action] + " · 未登录"
                            : stateLoading
                                    ? verbs[action] + " · 读取中"
                                    : VideoSocialState.label(action, value);
            panel.rows[action].setText(label);
        }
        if (panel.rows.length > 5)
            panel.rows[5].setText(state != null && state.recommended == 1 ? "取消推荐" : "推荐给朋友");
    }

    void select(int action) {
        // With menu-pausing disabled, autoplay may change the video behind this panel.
        // Require a fresh choice instead of applying an action to the previous author/video.
        try {
            List<?> feed = (List<?>) field(activity, "feedList");
            int index = (Integer) field(activity, "currentIndex");
            if (index < 0 || index >= feed.size() || feed.get(index) != item) {
                close(false);
                quick();
                toast("视频已切换，请重新选择");
                return;
            }
        } catch (Exception e) {
            toast("无法确认当前视频，请重新打开菜单");
            return;
        }
        if (action == 3) {
            if (secUid.isEmpty()) {
                toast("无法获取作者主页");
                return;
            }
            close(false);
            try {
                call(activity, "openProfile", new Class<?>[] {String.class}, secUid);
            } catch (Exception e) {
                toast("无法打开作者主页");
            }
            return;
        }
        if (action == 4) {
            if (!SocialApi.personalCookie()) {
                toast("请先从电脑同步账号");
                return;
            }
            if (!id.matches("[0-9]+") || text(item, "isLive").equals("true")) {
                toast("仅支持分享当前短视频");
                return;
            }
            close(false);
            Intent share = new Intent(activity, QuickShareActivity.class);
            share.putExtra("video_id", id);
            activity.startActivity(share);
            return;
        }
        if (busy) return;
        if (!SocialApi.personalCookie()) { toast("请先从电脑同步账号"); return; }
        if (action != 0 && action != 1 && action != 2 && action != 5 && action != 6) return;
        if (!session.equals(SocialApi.cookie())) {
            session = SocialApi.cookie(); state = null; stateLoading = false;
        }
        if (!id.matches("[0-9]+") || live(activity)) { toast("仅支持当前短视频"); return; }
        if (action == 1 && (state == null || !state.uid.matches("[0-9]+") || secUid.isEmpty())) {
            toast("暂时无法确认作者"); loadState(); return;
        }
        if (action == 5 && state != null && state.recommendAllowed == 0) {
            toast("该视频暂不支持推荐"); return;
        }
        int current = state == null ? -1 : action == 0 ? state.liked
                : action == 1 ? state.followed : action == 2 ? state.collected : state.recommended;
        if (action == 1 && current == 4) { toast("关注申请待确认"); return; }
        // Unknown state means an explicit positive action; the browser reads current state first.
        final boolean enable = current != 1 && !(action == 1 && current == 2);
        final String kind = action == 0 ? "like" : action == 1 ? "follow"
                : action == 2 ? "collect" : action == 5 ? "recommend" : "dislike";
        final JSONObject args = new JSONObject();
        try {
            if (action == 1) args.put("user_id", state.uid).put("sec_uid", secUid);
            else args.put("video_id", id);
            if (action != 6) args.put("enabled", enable);
        } catch (Exception malformed) { toast("操作暂不可用"); return; }
        final String cookie = session;
        final int token = generation, selection = PlaybackCoordinator.token(activity);
        final SocialApi.State previous = state;
        final Object selectedItem = item;
        busy = true;
        panel.rows[action].setText("处理中…");
        if (!SocialApi.submit(() -> {
            SocialApi.State result = null;
            boolean confirmed = false;
            String notice;
            try {
                JSONObject data = BrowserActionBroker.perform(activity, kind, args, cookie);
                if (!Boolean.TRUE.equals(data.opt("confirmed"))
                        || (action != 6 && !Boolean.valueOf(enable).equals(data.opt("enabled"))))
                    throw new Exception("unconfirmed");
                confirmed = true;
                if (action != 6) {
                    result = copyState(previous);
                    if (action == 0) result.liked = enable ? 1 : 0;
                    else if (action == 1) result.followed = enable ? 1 : 0;
                    else if (action == 2) result.collected = enable ? 1 : 0;
                    else result.recommended = enable ? 1 : 0;
                }
                notice = action == 0 ? (enable ? "已点赞" : "已取消点赞")
                        : action == 1 ? (enable ? "已关注" : "已取消关注")
                        : action == 2 ? (enable ? "已收藏" : "已取消收藏")
                        : action == 5 ? (enable ? "已推荐给朋友" : "已取消推荐") : "已反馈不喜欢";
            } catch (BrowserActionBroker.Failure failure) {
                notice = failure.getMessage();
            } catch (Exception failure) { notice = "操作未确认，请先核对"; }
            final SocialApi.State updated = result;
            final boolean accepted = confirmed;
            final String message = notice;
            handler.post(() -> {
                VideoSocialState.confirmed(activity, selection, cookie, updated);
                if (!active(token, cookie)) return;
                busy = false; stateLoading = false;
                if (updated != null) state = updated;
                if (action == 6 && accepted && PlaybackCoordinator.valid(activity, selection)) {
                    close(false); resumePlayback = false;
                    removeDisliked(selectedItem);
                } else labels();
                toast(message);
            });
        })) {
            busy = false; labels(); toast("操作尚未提交");
        }
    }

    private static SocialApi.State copyState(SocialApi.State old) {
        SocialApi.State copy = new SocialApi.State();
        if (old != null) {
            copy.liked = old.liked; copy.followed = old.followed; copy.collected = old.collected;
            copy.recommended = old.recommended; copy.recommendAllowed = old.recommendAllowed; copy.uid = old.uid;
        }
        return copy;
    }

    private void removeDisliked(Object selectedItem) {
        try {
            List<?> feed = (List<?>) field(activity, "feedList");
            int current = (Integer) field(activity, "currentIndex");
            if (current < 0 || current >= feed.size() || feed.get(current) != selectedItem) return;
            feed.remove(current);
            if (feed.isEmpty()) {
                ((PlayerView) field(activity, "videoView")).stopPlayback();
                field(activity, "currentIndex", 0); invoke("loadFeed");
            } else call(activity, "playAt", new Class<?>[] {int.class}, Math.min(current, feed.size() - 1));
        } catch (Exception unavailable) { toast("反馈已提交，请切换下一条"); }
    }

    void settings() {
        pause();
        String[] labels = {
            "内容与主页",
            "播放与弹幕",
            "界面显示",
            "账号与登录" + (CredentialHealth.needsRefresh() ? " · 需要更新" : ""),
            "操作说明与关于",
            "退出"
        };
        ModernMenuHelper.showMenu(
                activity,
                "设置",
                labels,
                index -> {
                    if (index == 0)
                        submenu(
                                "内容与主页",
                                new String[] {"关注的直播", "个人主页", "搜索", "精选", "刷新推荐"},
                                new String[] {
                                    "live",
                                    "openSelfProfile",
                                    "openSearch",
                                    "openFeatured",
                                    "loadFeed"
                                });
                    else if (index == 1)
                        submenu(
                                "播放与弹幕",
                                new String[] {"播放倍速", "播放画质", "弹幕开关", "弹幕透明度", "弹幕大小", "弹幕速度"},
                                new String[] {
                                    "showSpeedPicker",
                                    "showQualityPicker",
                                    "toggleDanmaku",
                                    "showOpacityPicker",
                                    "showSizePicker",
                                    "showDanmakuSpeedPicker"
                                });
                    else if (index == 2) displaySettings();
                    else if (index == 3) {
                        showing(false);
                        CredentialHealth.open(activity);
                    }
                    else if (index == 4) {
                        showing(false);
                        activity.startActivity(new Intent(activity, AboutActivity.class));
                    } else activity.finish();
                },
                () -> close(true));
    }

    void displaySettings() {
        String[] labels = {"资料卡：播放后显示 3 秒", "时间显示", "菜单暂停", "自动连播", "过滤竖屏"};
        ModernMenuHelper.showMenu(
                activity,
                "界面与偏好",
                labels,
                index -> {
                    showing(false);
                    if (index == 0) {
                        toast("每次切入视频显示 3 秒；菜单键临时查看，关闭菜单即隐藏");
                        resumeMenuPlayback();
                        return;
                    }
                    String[] fields = {
                        "", "showClock", "pauseOnMenu", "autoPlayNext", "filterVertical"
                    };
                    try {
                        boolean enabled = !((Boolean) field(activity, fields[index]));
                        field(activity, fields[index], enabled);
                        invoke("saveDanmakuPrefs");
                        if (index == 1) updateClock(activity);
                        toast(labels[index] + (enabled ? "已开启" : "已关闭"));
                        resumeMenuPlayback();
                    } catch (Exception e) {
                        toast("设置暂不可用");
                    }
                },
                () -> settings());
    }

    void submenu(String title, String[] labels, String[] methods) {
        ModernMenuHelper.showMenu(
                activity,
                title,
                labels,
                index -> {
                    showing(false);
                    String method = methods[index];
                    if (method.equals("live"))
                        activity.startActivity(new Intent(activity, FollowedLiveActivity.class));
                    else invoke(method);
                },
                () -> settings());
    }

    public static void updateClock(Activity a) {
        InteractionController c = get(a);
        try {
            TextView clock = (TextView) field(a, "tvClock");
            if (clock == null) return;
            boolean showTime = (Boolean) field(a, "showClock");
            String cookie = SocialApi.cookie();
            boolean personal = SocialApi.personalCookie() && CredentialStore.hasSession(cookie);
            boolean healthy = personal && !CredentialHealth.needsRefresh();
            c.syncUnreadAccount(cookie, healthy);
            // The login hint remains available even when the user hides the time.
            clock.setVisibility(showTime || !healthy ? View.VISIBLE : View.GONE);
            long wall = System.currentTimeMillis();
            if (c.clockMinute != wall / 60000) {
                c.clockMinute = wall / 60000;
                c.clockDate.setTime(wall);
                c.clockTime = c.clockFormat.format(c.clockDate);
            }
            String status = !healthy ? "未登录" : c.unread >= 0 ? "+" + c.unread : "--";
            String label = (showTime ? c.clockTime + "\u2002\u2002|\u2002\u2002" : "") + status;
            if (!label.contentEquals(clock.getText())) {
                SpannableString styled = new SpannableString(label);
                if (showTime) {
                    int separator = label.indexOf('|');
                    styled.setSpan(new ForegroundColorSpan(0x66ffffff), separator, separator + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                clock.setText(styled);
            }
            String description = (showTime ? "时间，" : "")
                    + (!healthy ? "未登录" : c.unread >= 0
                            ? "抖音未读通知 " + c.unread : "抖音未读通知暂不可用");
            if (!description.equals(clock.getContentDescription()))
                clock.setContentDescription(description);
            long now = SystemClock.elapsedRealtime();
            if (!c.foreground
                    || !personal
                    || c.unreadLoading
                    || (c.lastUnread != 0 && now - c.lastUnread < 30000)) return;
            c.unreadLoading = true;
            c.lastUnread = now;
            final int requestGeneration = c.unreadGeneration;
            c.unreadWork.execute(
                    () -> {
                        int count;
                        try {
                            count = SocialApi.unread(cookie);
                        } catch (Exception e) {
                            count = -1;
                        }
                        final int result = count;
                        c.handler.post(() -> c.finishUnread(cookie, requestGeneration, result));
                    });
        } catch (Exception ignored) {
        }
    }

    private void syncUnreadAccount(String cookie, boolean healthy) {
        if (!cookie.equals(unreadSession)) {
            unreadSession = cookie;
            unreadGeneration++;
            unreadLoading = false;
            unread = -1;
            lastUnread = 0;
        }
        if (healthy != unreadHealthy) {
            unreadHealthy = healthy;
            unread = -1;
            lastUnread = 0;
        }
    }

    void finishUnread(String cookie, int requestGeneration, int result) {
        if (activity.isFinishing() || activity.isDestroyed()
                || requestGeneration != unreadGeneration
                || !cookie.equals(SocialApi.cookie())) return;
        unreadLoading = false;
        boolean healthy = SocialApi.personalCookie() && CredentialStore.hasSession(cookie)
                && !CredentialHealth.needsRefresh();
        syncUnreadAccount(cookie, healthy);
        // A transport failure retains a known count; it never changes account health.
        if (result >= 0 && healthy) unread = result;
        lastUnread = SystemClock.elapsedRealtime();
        updateClock(activity);
    }
}
