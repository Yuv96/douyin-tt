package com.dycomment.tv;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** Local fixtures only: no account, cookies, messages or network writes. */
public final class InteractionSelfTestActivity extends Activity {
    private TextView tvClock;
    private boolean showClock = true;

    void require(boolean ok, String detail) {
        if (!ok) throw new IllegalStateException(detail);
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        TextView result = new TextView(this);
        setContentView(result);
        try {
            LegacyThemeSelfTest.run(this);
            PlaybackFailureSelfTest.run();
            require(
                    CredentialStore.value("a=1; msToken=x=y; sessionid=s", "msToken").equals("x=y"),
                    "Cookie token preserves equals");
            require(
                    !CredentialStore.hasSession("msToken=not-a-login"),
                    "SDK token alone is not login");
            verifyAccountHealth();
            QuickShareSelfTest.run();
            require(!new VideoProxyServer().isReady(), "no obsolete local playback proxy");
            java.util.concurrent.CountDownLatch started =
                    new java.util.concurrent.CountDownLatch(2);
            java.util.concurrent.CountDownLatch release =
                    new java.util.concurrent.CountDownLatch(1);
            Runnable blocked =
                    () -> {
                        started.countDown();
                        try {
                            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };
            try {
                require(
                        SocialApi.submit(blocked) && SocialApi.submit(blocked),
                        "two social workers accepted");
                require(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "workers started");
                for (int i = 0; i < 8; i++)
                    require(SocialApi.submit(() -> {}), "bounded queue slot " + i);
                require(
                        !SocialApi.submit(() -> {}),
                        "overload is reported instead of blocking UI or dropping a send silently");
            } finally {
                release.countDown();
            }
            int unread =
                    SocialApi.parseUnread(
                            new JSONObject(
                                    "{\"notice_count\":[{\"group\":1,\"count\":2},{\"group\":2,\"count\":1},{\"group\":1,\"count\":2}]}"));
            require(unread == 3, "unread deduplication");
            require(
                    SocialApi.parseUnread(new JSONObject("{\"notice_count\":[]}")) == 0,
                    "zero unread");
            boolean rejected = false;
            try {
                SocialApi.parseLive(new JSONObject("{}"));
            } catch (Exception e) {
                rejected = true;
            }
            require(rejected, "invalid live response must not become an empty list");
            String room =
                    "{\"id_str\":\"123\",\"owner\":{\"nickname\":\"测试作者\",\"follow_info\":{\"follow_status\":1}},\"stream_url\":{\"hls_pull_url_map\":{\"SD1\":\"https://example.com/sd.m3u8\",\"FULL_HD1\":\"https://example.com/hd.m3u8\"}}}";
            List<SocialApi.Live> lives =
                    SocialApi.parseLive(
                            new JSONObject(
                                    "{\"data\":{\"data\":[{\"room\":"
                                            + room
                                            + ",\"is_recommend\":1},{\"room\":"
                                            + room
                                            + ",\"is_recommend\":0},{\"room\":"
                                            + room
                                            + ",\"is_recommend\":0}]}}"));
            require(
                    lives.size() == 1 && lives.get(0).stream.endsWith("sd.m3u8"),
                    "follow-only live and older-TV resolution");
            JSONObject compact =
                    SocialApi.readLiveResponse(
                            new java.io.ByteArrayInputStream(
                                    ("{\"status_code\":0,\"discarded\":{\"large_metadata\":[]},\"data\":{\"data\":[{\"room\":"
                                                    + room
                                                    + "}]}}")
                                            .getBytes("UTF-8")));
            require(
                    !compact.has("discarded")
                            && compact.optInt("status_code", -1) == 0
                            && SocialApi.parseLive(compact).size() == 1,
                    "streamed live metadata filtering");
            JSONObject coverRoom =
                    new JSONObject(room)
                            .put(
                                    "cover",
                                    new JSONObject(
                                            "{\"url_list\":[\"https://example.com/preview.jpg\"]}"));
            JSONObject covered =
                    SocialApi.readLiveResponse(
                            new java.io.ByteArrayInputStream(
                                    ("{\"status_code\":0,\"data\":{\"data\":[{\"room\":"
                                                    + coverRoom
                                                    + "}]}}")
                                            .getBytes("UTF-8")));
            require(
                    SocialApi.parseLive(covered).get(0).preview.endsWith("preview.jpg"),
                    "live preview preserved through streaming parser");
            QuickShareApi.Page friends =
                    QuickShareApi.parseFriends(
                            new JSONObject(
                                    "{\"user_list\":[{\"uid\":\"12\",\"nickname\":\"测试好友\"},{\"uid\":\"12\"}],\"cursor\":30,\"has_more\":true}"));
            require(
                    friends.friends.size() == 1 && friends.more && friends.cursor.equals("30"),
                    "friends paging and dedup");
            verifyAdaptiveCard();
            require(
                    VideoSocialState.label(0, 0).equals("未点赞")
                            && VideoSocialState.label(0, 1).equals("已点赞")
                            && VideoSocialState.label(2, 0).equals("未收藏")
                            && VideoSocialState.label(2, 1).equals("已收藏"),
                    "like and collection states");
            require(
                    VideoSocialState.label(1, 0).equals("未关注")
                            && VideoSocialState.label(1, 1).equals("已关注")
                            && VideoSocialState.label(1, 2).equals("已关注")
                            && VideoSocialState.label(1, 4).equals("关注待确认")
                            && VideoSocialState.label(1, -1).contains("未知"),
                    "follow states never guess unknown");
            verifyInfoCardLifecycle();
            verifyClockCapsule();
            Wire parsed = new Wire(new WireFixture.Out().number(1, Long.MAX_VALUE).text(2, "你好").done());
            require(
                    parsed.number(1, 0) == Long.MAX_VALUE && parsed.text(2).equals("你好"),
                    "protobuf preserves IDs and UTF8");
            rejected = false;
            try {
                new Wire(new byte[] {18, 9, 1});
            } catch (Exception e) {
                rejected = true;
            }
            require(rejected, "truncated wire response rejected");
            byte[] chat =
                    new WireFixture.Out()
                            .bytes(2, new WireFixture.Out().text(3, "测试观众").done())
                            .text(3, "测试弹幕")
                            .done();
            byte[] event =
                    new WireFixture.Out()
                            .text(1, "WebcastChatMessage")
                            .bytes(2, chat)
                            .number(3, 123)
                            .done();
            require(
                    LiveChatController.chats(new Wire(new WireFixture.Out().bytes(1, event).done()))
                            .get(0)
                            .text
                            .equals("测试观众：测试弹幕"),
                    "live chat wire decoding");
            final int[] selected = {-1}, cancelled = {0}, menu = {0};
            ModernMenuHelper.Panel panel =
                    ModernMenuHelper.show(
                            this,
                            "互动测试",
                            new String[] {"喜欢", "关注", "收藏", "主页", "分享"},
                            true,
                            true,
                            i -> selected[0] = i,
                            () -> cancelled[0]++,
                            () -> menu[0]++);
            require(panel.rows.length == 5 && ModernMenuHelper.isMenuShowing(), "five large rows");
            require(
                    panel.rows[0].isFocused(),
                    "menu takes focus from the video even in touch mode");
            panel.rows[2].performClick();
            require(selected[0] == 2 && !panel.closed, "actions keep panel open");
            panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU));
            panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU));
            require(menu[0] == 1, "MENU callback exactly once");
            panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            require(
                    cancelled[0] == 1 && !ModernMenuHelper.isMenuShowing(),
                    "BACK closes and clears showing state");
            ModernMenuHelper.showMenu(
                    this,
                    "纯文字菜单",
                    new String[] {"画质设置"},
                    new String[] {"BAD_ICON"},
                    i -> {},
                    () -> {});
            require(ModernMenuHelper.isMenuShowing(), "legacy menu ABI");
            ModernMenuHelper.dismissCurrentMenu(this);
            require(!ModernMenuHelper.isMenuShowing(), "legacy dismiss");
            result.setText("PASS Android 5.0 menu, key routing and social data parsing");
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(
                            () -> {
                                try {
                                    android.view.ViewGroup decor =
                                            (android.view.ViewGroup) getWindow().getDecorView();
                                    CommentsPanel comments = new CommentsPanel(this, "fixture");
                                    decor.addView(
                                            comments,
                                            new android.view.ViewGroup.LayoutParams(-1, -1));
                                    decor.removeView(comments);
                                    require(
                                            comments.getParent() == null,
                                            "external comment removal completed");
                                    CommentsPanel second = new CommentsPanel(this, "fixture");
                                    decor.addView(
                                            second,
                                            new android.view.ViewGroup.LayoutParams(-1, -1));
                                    second.close(false);
                                    require(
                                            second.getParent() == null,
                                            "explicit comment removal completed");
                                    Log.i(
                                            "Android5InteractionTest",
                                            "PASS API21_MENU_KEYS_SOCIAL_PARSERS_COMMENT_DETACH");
                                } catch (Exception e) {
                                    Log.e(
                                            "Android5InteractionTest",
                                            "FAIL comment lifecycle " + e.getMessage());
                                }
                            },
                            200);
        } catch (Exception e) {
            result.setText("FAIL " + e.getMessage());
            Log.e("Android5InteractionTest", "FAIL " + e.getMessage());
        }
    }

    private void fixtureCookie(String cookie) throws Exception {
        Class.forName("com.dycomment.tv.DouyinApi")
                .getMethod("setCookie", String.class, android.content.Context.class)
                .invoke(null, cookie, this);
    }

    private void verifyAccountHealth() throws Exception {
        String self = OfficialLoginPolicy.SELF_PATH, comments = "/aweme/v1/web/comment/list/", notices = "/aweme/v1/web/notice/count/";
        JSONObject valid = new JSONObject().put("status_code", 0).put("user", new JSONObject().put("uid", "123"));
        JSONObject rejected = new JSONObject().put("status_code", 8);
        require(CredentialHealth.evidence(comments, 200, null) == CredentialHealth.UNKNOWN,
                "nonJSON comments cannot invalidate account");
        require(CredentialHealth.evidence(notices, 200, new JSONObject().put("status_code", 4)) == CredentialHealth.UNKNOWN,
                "generic notice failure cannot invalidate account");
        require(CredentialHealth.evidence(comments, 200, rejected) == CredentialHealth.CHECK_SELF
                        && CredentialHealth.evidence(notices, 401, null) == CredentialHealth.CHECK_SELF
                        && CredentialHealth.evidence(comments, 403, null) == CredentialHealth.CHECK_SELF,
                "feature auth errors require independent self confirmation");
        require(CredentialHealth.evidence(self, 403, null) == CredentialHealth.UNKNOWN
                        && CredentialHealth.evidence(self, 200, null) == CredentialHealth.UNKNOWN
                        && CredentialHealth.evidence(self, 200, new JSONObject().put("status_code", 0)) == CredentialHealth.UNKNOWN,
                "WAF and missing self data are inconclusive");
        require(CredentialHealth.evidence(self, 200, new JSONObject().put("status_code", "0")
                        .put("user", new JSONObject().put("uid", "123"))) == CredentialHealth.UNKNOWN
                        && CredentialHealth.evidence(self, 200, new JSONObject().put("status_code", 0)
                        .put("user", new JSONObject().put("uid", "0"))) == CredentialHealth.UNKNOWN,
                "self verification requires strict numeric success and positive UID");
        require(CredentialHealth.evidence(self, 200, rejected) == CredentialHealth.UNAUTHENTICATED
                        && CredentialHealth.evidence(self, 401, null) == CredentialHealth.UNAUTHENTICATED
                        && CredentialHealth.evidence(self, 200, valid) == CredentialHealth.AUTHENTICATED,
                "only strict self evidence establishes account health");
        CredentialHealth.State state = new CredentialHealth.State();
        state.select("account-a");
        CredentialHealth.Ticket first = state.begin("account-a", state.epoch, 1000);
        require(first != null && !state.invalid, "feature failure starts a probe without logging out");
        require(state.begin("account-a", state.epoch, 40000) == null, "at most one self probe is in flight");
        state.finish(first, CredentialHealth.evidence(self, 200, valid));
        require(!state.invalid, "valid self disproves comment or notification auth failure");
        require(state.begin("account-a", state.epoch, 2000) == null, "repeated feature failures are throttled");
        CredentialHealth.Ticket unknown = state.begin("account-a", state.epoch, 31000);
        require(unknown != null, "probe can retry after throttle");
        state.finish(unknown, CredentialHealth.UNKNOWN);
        require(!state.invalid, "network failure cannot log out a healthy account");
        CredentialHealth.Ticket invalid = state.begin("account-a", state.epoch, 61000);
        state.finish(invalid, CredentialHealth.UNAUTHENTICATED);
        require(state.invalid, "confirmed self rejection marks unauthenticated");
        CredentialHealth.Ticket inconclusive = state.begin("account-a", state.epoch, 91000);
        state.finish(inconclusive, CredentialHealth.UNKNOWN);
        require(state.invalid, "inconclusive probe cannot invent a valid account");
        CredentialHealth.Ticket late = state.begin("account-a", state.epoch, 121000);
        state.apply("account-a", state.epoch, CredentialHealth.AUTHENTICATED);
        state.finish(late, CredentialHealth.UNAUTHENTICATED);
        require(!state.invalid, "late rejection cannot overwrite newer verified self or sync");
        CredentialHealth.Ticket oldAccount = state.begin("account-a", state.epoch, 151000);
        state.select("account-b");
        state.finish(oldAccount, CredentialHealth.UNAUTHENTICATED);
        require(!state.invalid && state.session.equals("account-b"), "old account probe cannot invalidate replacement");
        CredentialHealth.Ticket roundTrip = state.begin("account-b", state.epoch, 181000);
        state.select("account-a"); state.select("account-b");
        state.finish(roundTrip, CredentialHealth.UNAUTHENTICATED);
        require(!state.invalid, "switching away and back invalidates old observations");
        CredentialHealth.Ticket staleValid = state.begin("account-b", state.epoch, 211000);
        state.apply("account-b", state.epoch, CredentialHealth.UNAUTHENTICATED);
        state.finish(staleValid, CredentialHealth.AUTHENTICATED);
        require(state.invalid, "late success cannot overwrite newer definitive account rejection");
        require(!state.apply("candidate-account", state.epoch, CredentialHealth.AUTHENTICATED) && state.invalid,
                "uncommitted candidate validation cannot alter the current account");
    }

    private void requireCapsule(String status) {
        String label = tvClock.getText().toString();
        require(tvClock.getVisibility() == android.view.View.VISIBLE,
                "account capsule remains visible");
        require(label.endsWith("\u2002\u2002|\u2002\u2002" + status),
                "capsule status with balanced spacing: " + status);
        require(label.substring(0, 5).matches("[0-9]{2}:[0-9]{2}"), "capsule includes time");
        require(tvClock.getText() instanceof android.text.Spanned, "capsule styles separator");
        android.text.Spanned text = (android.text.Spanned) tvClock.getText();
        android.text.style.ForegroundColorSpan[] spans = text.getSpans(
                0, text.length(), android.text.style.ForegroundColorSpan.class);
        require(spans.length == 1 && spans[0].getForegroundColor() == 0x66ffffff,
                "separator alone uses softer white");
        int separator = label.indexOf('|');
        require(text.getSpanStart(spans[0]) == separator
                        && text.getSpanEnd(spans[0]) == separator + 1,
                "time and account status retain normal text color");
    }

    private void verifyClockCapsule() throws Exception {
        android.view.ViewGroup decor = (android.view.ViewGroup) getWindow().getDecorView();
        tvClock = new TextView(this);
        decor.addView(tvClock);
        TextView obsoleteBanner = new TextView(this);
        obsoleteBanner.setId(0x7f0f7a58);
        decor.addView(obsoleteBanner);
        InteractionController controller = InteractionController.get(this);
        // Keep the real request worker inactive: every response below is a synthetic fixture.
        controller.foreground = false;
        String first = "sessionid=fixture-capsule-account-a";
        String second = "sessionid_ss=fixture-capsule-account-b";
        String endpoint = "/aweme/v1/web/notice/count/";
        try {
            fixtureCookie("");
            CredentialHealth.reset();
            CredentialHealth.attach(this);
            requireCapsule("未登录");
            require(decor.findViewById(0x7f0f7a58) == null,
                    "legacy center login banner is removed");

            fixtureCookie("msToken=fixture-sdk-token-only");
            InteractionController.updateClock(this);
            requireCapsule("未登录");

            fixtureCookie(first);
            InteractionController.updateClock(this);
            requireCapsule("--");
            int firstGeneration = controller.unreadGeneration;
            controller.finishUnread(first, firstGeneration, 0);
            requireCapsule("+0");
            controller.finishUnread(first, firstGeneration, 7);
            requireCapsule("+7");
            controller.finishUnread(first, firstGeneration, -1);
            requireCapsule("+7");
            require(!CredentialHealth.needsRefresh(), "network failure is not logout");

            for (int i = 0; i < 4; i++) {
                CredentialHealth.observe(endpoint, first, CredentialHealth.observation(first), 200, null);
                CredentialHealth.observe("/aweme/v1/web/comment/list/", first, CredentialHealth.observation(first),
                        200, new JSONObject().put("status_code", 4));
            }
            InteractionController.updateClock(this);
            requireCapsule("+7");
            require(!CredentialHealth.needsRefresh(), "malformed notifications and generic comment failures are not logout");
            CredentialHealth.observe(OfficialLoginPolicy.SELF_PATH, first, CredentialHealth.observation(first), 401, null);
            InteractionController.updateClock(this);
            requireCapsule("未登录");
            controller.finishUnread(first, firstGeneration, 99);
            requireCapsule("未登录");
            CredentialHealth.observe(OfficialLoginPolicy.SELF_PATH, first, CredentialHealth.observation(first), 200,
                    new JSONObject().put("status_code", 0).put("user", new JSONObject().put("uid", "123")));
            InteractionController.updateClock(this);
            requireCapsule("--");
            controller.finishUnread(first, firstGeneration, 2);
            requireCapsule("+2");

            CredentialHealth.observe(OfficialLoginPolicy.SELF_PATH, first, CredentialHealth.observation(first), 401, null);
            fixtureCookie(second);
            InteractionController.updateClock(this);
            require(!CredentialHealth.needsRefresh(), "old account suspicion is not inherited");
            requireCapsule("--");
            int secondGeneration = controller.unreadGeneration;
            controller.unreadLoading = true;
            controller.finishUnread(first, firstGeneration, 88);
            require(controller.unreadLoading, "stale response cannot clear new account request");
            requireCapsule("--");
            controller.finishUnread(second, secondGeneration, -1);
            requireCapsule("--");
            require(!CredentialHealth.needsRefresh(), "unknown count is not logout or fake zero");
            controller.finishUnread(second, secondGeneration, 3);
            requireCapsule("+3");
            fixtureCookie(first);
            InteractionController.updateClock(this);
            controller.finishUnread(first, firstGeneration, 77);
            requireCapsule("--");
            require(controller.unreadGeneration != firstGeneration,
                    "switching away and back invalidates the original response");

            showClock = false;
            InteractionController.updateClock(this);
            require(tvClock.getVisibility() == android.view.View.GONE,
                    "healthy account respects hidden clock preference");
            fixtureCookie("");
            InteractionController.updateClock(this);
            require(tvClock.getVisibility() == android.view.View.VISIBLE
                            && tvClock.getText().toString().equals("未登录"),
                    "hidden clock still exposes the sole login hint without a separator");
            Log.i("Android5InteractionTest", "CAPSULE_AUTH_COUNTS_NETWORK_ACCOUNT_SWITCH_STYLE_OK");
        } finally {
            CredentialHealth.detach(this);
            fixtureCookie("");
            CredentialHealth.reset();
            controller.unreadLoading = false;
            showClock = true;
            decor.removeView(tvClock);
            decor.removeView(obsoleteBanner);
            tvClock = null;
        }
    }

    private void verifyAdaptiveCard() {
        android.view.View root =
                getLayoutInflater()
                        .inflate(
                                getResources()
                                        .getIdentifier("activity_main", "layout", getPackageName()),
                                null);
        TextView author =
                root.findViewById(getResources().getIdentifier("tvAuthor", "id", getPackageName()));
        TextView title =
                root.findViewById(getResources().getIdentifier("tvTitle", "id", getPackageName()));
        TextView stats =
                root.findViewById(getResources().getIdentifier("tvStats", "id", getPackageName()));
        android.view.View overlay =
                root.findViewById(
                        getResources().getIdentifier("infoOverlay", "id", getPackageName()));
        overlay.setVisibility(android.view.View.VISIBLE);
        require(
                title.getMaxLines() == 2
                        && title.getEllipsize() == android.text.TextUtils.TruncateAt.END,
                "title ends after two lines");
        android.view.View badge =
                root.findViewById(
                        getResources()
                                .getIdentifier("android5_follow_badge", "id", getPackageName()));
        android.widget.FrameLayout.LayoutParams badgeParams =
                (android.widget.FrameLayout.LayoutParams) badge.getLayoutParams();
        require(
                badgeParams.gravity
                        == (android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL),
                "follow tag at avatar bottom center");
        author.setText("作者");
        stats.setText("赞 1 · 评 2");
        title.setText("短标题");
        measureCard(root, 1280);
        int compact = overlay.getMeasuredWidth();
        require(compact < ModernMenuHelper.dp(this, 600), "short card wraps content");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) longText.append("长标题和标签");
        title.setText(longText);
        author.setText(longText);
        measureCard(root, 1280);
        require(
                overlay.getMeasuredWidth() > compact
                        && overlay.getMeasuredWidth() <= ModernMenuHelper.dp(this, 720),
                "long card grows only to the TV ceiling");
        measureCard(root, 360);
        require(
                overlay.getMeasuredWidth() <= ModernMenuHelper.dp(this, 324),
                "narrow card stays inside side margins");
        title.setText("短标题");
        author.setText("作者");
        measureCard(root, 1280);
        require(overlay.getMeasuredWidth() == compact, "next short video shrinks the card again");
    }

    private void verifyInfoCardLifecycle() {
        InfoCardState info = new InfoCardState();
        require(!info.visible(100), "no metadata before playback");
        info.ready(100);
        require(info.visible(3099) && !info.visible(3100), "exact three-second window");
        info.ready(5000);
        require(!info.visible(5000), "loop or surface return never reopens metadata");
        info.menu(true);
        require(info.visible(20000), "menu owns temporary visibility without a timeout");
        info.menu(false);
        require(!info.visible(20000), "closing menu hides immediately");
        info.selected();
        info.ready(30000);
        require(info.visible(30000), "switch away and back gives a new window");
        info.menu(true);
        info.menu(false);
        require(!info.visible(30001), "closing menu consumes remaining initial time");
        info.ready(30002);
        require(!info.visible(30002), "late ready event cannot resurrect a closed card");
        info.selected();
        require(!info.visible(30003), "switch cancels previous menu appearance");
    }

    private void measureCard(android.view.View root, int widthDp) {
        root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                        ModernMenuHelper.dp(this, widthDp), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                        ModernMenuHelper.dp(this, 720), android.view.View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        android.view.View overlay =
                root.findViewById(
                        getResources().getIdentifier("infoOverlay", "id", getPackageName()));
        require(
                overlay.getRight() <= root.getMeasuredWidth() / 2,
                "avatar and card together never cross the screen midpoint");
        android.view.View avatar =
                root.findViewById(
                        getResources().getIdentifier("ivAuthorAvatar", "id", getPackageName()));
        require(
                avatar.getClass().getSimpleName().equals("CircleImageView"),
                "avatar keeps circular rendering");
        android.view.View author =
                root.findViewById(getResources().getIdentifier("tvAuthor", "id", getPackageName()));
        android.view.View card = (android.view.View) author.getParent().getParent();
        require(
                avatar.getMeasuredWidth() == avatar.getMeasuredHeight()
                        && avatar.getMeasuredHeight() == card.getMeasuredHeight(),
                "circular avatar has square bounds matching the card at every width");
        int stableHeight =
                Math.round(
                        88
                                * getResources().getDisplayMetrics().density
                                * Math.max(1f, getResources().getConfiguration().fontScale));
        require(
                card.getMeasuredHeight() == stableHeight,
                "short, long and narrow cards retain the same bounded height");
    }
}
