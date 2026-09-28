package com.dycomment.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/** Deterministic API21 UI coverage; executed only by GitHub Actions. */
public final class CommentsSelfTestActivity extends Activity {
    private static final int RAPID_SCROLL_STAGE = 100;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Fixture source = new Fixture(false);
    private CommentsPanel panel, detached;
    private Fixture delayed;
    private ViewGroup decor;
    private int stage, anchorPosition, anchorOffset, repeatsRemaining;
    private String anchorText;
    private long started, stageAt;
    private boolean done;
    private LiveFixture live;
    private RetryFixture retry;
    private long retryTime = 1000;
    // MainActivity bridge fixture: comment opening/closing must never change playback intent.
    final List<Object> feedList = new ArrayList<>();
    int currentIndex, pauses, resumes;
    boolean menuShowing, playing;
    boolean pauseOnMenu = true;
    PlayerView videoView;
    View commentOverlay;

    private void pauseForMenu() {
        if (!pauseOnMenu) return;
        pauses++; playing = false;
        if (videoView != null) videoView.pause();
    }
    private void resumeFromMenu() {
        if (!pauseOnMenu) return;
        resumes++; playing = true;
        if (videoView != null) videoView.start();
    }

    private static final class Item {
        boolean isLive;
        String awemeId = "fixture", roomId = "123";
    }

    private static final class LiveFixture implements LiveMessages.Source {
        LiveMessages.Listener listener;
        boolean current = true, closed;
        int starts;
        public boolean current() { return current; }
        public void start(LiveMessages.Listener value) { listener = value; starts++; }
        public void close() { closed = true; }
        void emit(int begin, int end) {
            List<LiveChatController.Chat> batch = new ArrayList<>();
            for (int i = begin; i < end; i++) {
                LiveChatController.Chat chat = new LiveChatController.Chat();
                chat.id = String.valueOf(i);
                chat.author = "测试观众";
                chat.content = "直播消息 " + i;
                chat.text = chat.author + "：" + chat.content;
                batch.add(chat);
            }
            listener.messages(batch);
        }
    }

    private static final class RetryFixture implements CommentsPanel.PageSource {
        volatile int requests;
        public boolean ready() { return true; }
        public boolean current() { return true; }
        public JSONObject load(int cursor) throws Exception {
            requests++;
            throw new Exception("fixture unavailable");
        }
    }

    private void playbackInvariant() throws Exception {
        java.lang.reflect.Field cookie = Class.forName("com.dycomment.tv.DouyinApi").getDeclaredField("cookie");
        cookie.setAccessible(true);
        Object previous = cookie.get(null);
        cookie.set(null, "");
        try {
        LiveChatController stream = LiveChatController.get(this);
        LiveChatController.start(this, "123456");
        int registered = (Integer) field(stream, "epoch");
        LiveChatController.start(this, "123456");
        require(stream.room.equals("123456") && (Integer) field(stream, "epoch") == registered
                        && field(stream, "transport") == null && !(Boolean) field(stream, "running"),
                "repeated playback hooks register the room without restarting or fetching messages");
        LiveMessages.Listener reading = new LiveMessages.Listener() {
            public void messages(List<LiveChatController.Chat> messages) { }
            public void unavailable() { }
        };
        stream.subscribe("123456", reading);
        stream.unsubscribe(reading);
        require(stream.room.isEmpty() && field(stream, "listener") == null
                        && field(stream, "transport") == null && !(Boolean) field(stream, "running")
                        && ((List<?>) field(stream, "recent")).isEmpty(),
                "closing the panel releases its whole live subscription");
        Item item = new Item();
        feedList.add(item);
        for (boolean isLive : new boolean[] {false, true}) {
            item.isLive = isLive;
            for (boolean initiallyPlaying : new boolean[] {false, true}) {
                playing = initiallyPlaying;
                // A stale menu resume flag must not resume a manually paused reader.
                InteractionController.get(this).resumePlayback = true;
                CommentsPanel.show(this);
                require(commentOverlay instanceof CommentsPanel, "reading panel opened");
                require(playing == initiallyPlaying && pauses == 0 && resumes == 0,
                        "opening short/live comments preserves playback intent");
                ((CommentsPanel) commentOverlay).close(true);
                require(playing == initiallyPlaying && pauses == 0 && resumes == 0,
                        "closing short/live comments preserves playback intent");
                require(commentOverlay == null && !menuShowing, "comment bridge released");
            }
        }
        controllerTransitions(item);
        feedList.clear();
        } finally {
            cookie.set(null, previous);
        }
    }

    private void controllerKey(int key) {
        require(InteractionController.handleKey(this, new KeyEvent(KeyEvent.ACTION_DOWN, key)),
                "controller consumes the menu/live reading key");
        InteractionController.handleKey(this, new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private void closeReading() {
        require(commentOverlay instanceof CommentsPanel, "controller opened comments");
        ((CommentsPanel) commentOverlay).close(true);
    }

    private void controllerTransitions(Item item) throws Exception {
        InteractionController controller = InteractionController.get(this);
        controller.close(false);
        videoView = new PlayerView(this);
        decor.addView(videoView, new ViewGroup.LayoutParams(1, 1));
        try {
            item.isLive = false;
            pauseOnMenu = true;
            videoView.start();
            require(videoView.wantsPlayback() && !videoView.isPlaying(),
                    "fixture has playback intent before native output is available");
            controller.quick();
            require(controller.panel != null && !videoView.wantsPlayback(),
                    "interaction menu owns the pause during preparation");
            int before = resumes;
            controllerKey(KeyEvent.KEYCODE_MENU);
            require(videoView.wantsPlayback() && resumes == before + 1,
                    "interaction-to-comments restores preparation playback intent");
            closeReading();
            require(videoView.wantsPlayback() && resumes == before + 1,
                    "closing comments does not restore twice");

            videoView.pause();
            before = resumes;
            controller.quick();
            controllerKey(KeyEvent.KEYCODE_MENU);
            closeReading();
            require(!videoView.wantsPlayback() && resumes == before,
                    "manually paused short video stays paused across actual interaction/comments transition");

            item.isLive = true;
            for (int key : new int[] {KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU}) {
                controller.resumePlayback = true; // A flag alone cannot grant pause ownership.
                controllerKey(key);
                require(controller.panel == null && !ModernMenuHelper.isMenuShowing(),
                        "live reading keys never open the short-video interaction menu");
                View opened = commentOverlay;
                controllerKey(KeyEvent.KEYCODE_MENU);
                require(commentOverlay == opened && opened.getParent() == decor,
                        "MENU keeps the same live column and subscription open");
                controllerKey(KeyEvent.KEYCODE_BACK);
                require(commentOverlay == null && !menuShowing,
                        "BACK explicitly closes the persistent live column");
                require(!videoView.wantsPlayback() && resumes == before,
                        "live reading ignores stale menu resume flags on manually paused video");
            }

            videoView.start();
            int pausesBefore = pauses;
            controllerKey(KeyEvent.KEYCODE_MENU);
            require(videoView.wantsPlayback() && pauses == pausesBefore && resumes == before,
                    "MENU opens live chat without pausing an active stream");
            controllerKey(KeyEvent.KEYCODE_BACK);
            require(videoView.wantsPlayback() && pauses == pausesBefore && resumes == before,
                    "BACK closes live chat without changing active playback");
            controller.settings();
            require(controller.panel == null && ModernMenuHelper.isMenuShowing()
                            && !videoView.wantsPlayback(), "settings owns live playback pause independently of quick panel");
            before = resumes;
            controllerKey(KeyEvent.KEYCODE_MENU);
            require(videoView.wantsPlayback() && resumes == before + 1
                            && !ModernMenuHelper.isMenuShowing(),
                    "settings-to-live-comments consumes and restores its own pause");
            closeReading();

            videoView.pause();
            before = resumes;
            controller.settings();
            controllerKey(KeyEvent.KEYCODE_MENU);
            closeReading();
            require(!videoView.wantsPlayback() && resumes == before,
                    "settings-to-live-comments preserves an existing manual pause");

            item.isLive = false;
            pauseOnMenu = false;
            videoView.start();
            before = resumes;
            controller.quick();
            controllerKey(KeyEvent.KEYCODE_MENU);
            closeReading();
            require(videoView.wantsPlayback() && resumes == before,
                    "menu pause disabled grants no pause ownership");

            pauseOnMenu = true;
            controller.quick();
            pauseOnMenu = false;
            controller.comments();
            closeReading();
            require(videoView.wantsPlayback(),
                    "disabling the menu-pause preference still restores the pause already owned by that menu");

            pauseOnMenu = true;
            controller.quick();
            PlaybackCoordinator.selected(this);
            before = resumes;
            controller.comments();
            closeReading();
            require(!videoView.wantsPlayback() && resumes == before,
                    "old menu ownership cannot restart a newly selected video");
        } finally {
            if (commentOverlay instanceof CommentsPanel) ((CommentsPanel) commentOverlay).close(false);
            controller.close(false);
            ModernMenuHelper.dismissCurrentMenu(this);
            decor.removeView(videoView);
            videoView = null;
            pauseOnMenu = true;
        }
    }

    private void liveProtocol() throws Exception {
        require(LiveMessageTransport.identity("{\"user_unique_id\":\"123456789\"}").equals("123456789"),
                "live identity comes from official bootstrap");
        require(LiveMessageTransport.identity("{\\\"user_unique_id\\\":\\\"987654321\\\"}").equals("987654321"),
                "live identity accepts escaped server hydration JSON");
        boolean rejected = false;
        try { LiveMessageTransport.identity("{\"uid\":\"123456789\"}"); }
        catch (Exception expected) { rejected = true; }
        require(rejected, "account UID never substitutes for live device identity");
        LiveMessageTransport transport = new LiveMessageTransport("");
        byte[] chat = new WireFixture.Out().bytes(2, new WireFixture.Out().text(3, "观众").done())
                .text(3, "消息内容").done();
        byte[] event = new WireFixture.Out().text(1, "WebcastChatMessage").bytes(2, chat).number(3, 123).done();
        LiveMessageTransport.Batch batch = transport.decode(new Wire(new WireFixture.Out()
                .bytes(1, event).text(2, "next-cursor").text(5, "server-context").number(3, 1).done()));
        require(batch.messages.size() == 1 && batch.messages.get(0).content.equals("消息内容"),
                "live protocol decodes only actual chat payloads");
        require(batch.interval == 1000 && field(transport, "cursor").equals("next-cursor")
                        && field(transport, "internal").equals("server-context"),
                "server cursor/context advance and polling has a one-second floor");
        require(transport.decode(new Wire(new WireFixture.Out().text(2, "next").number(3, 60000).done())).interval == 30000,
                "server interval remains bounded");
        rejected = false;
        try { transport.decode(new Wire(new byte[0])); } catch (Exception expected) { rejected = true; }
        require(rejected, "empty or malformed live responses are not successful empty lists");
        transport.close();
    }

    private void commentCredentials() throws Exception {
        CredentialStore.Snapshot current = new CredentialStore.Snapshot(
                "sessionid=fixture; msToken=current-token", "current-token", "old-request-signature", 4);
        JSONObject first = CommentsPanel.arguments("video-one", 0);
        JSONObject next = CommentsPanel.arguments("video-one", 20);
        require(first.length() == 3 && first.getString("video_id").equals("video-one")
                        && first.getInt("cursor") == 0 && first.getInt("count") == 20
                        && next.length() == 3 && next.getInt("cursor") == 20
                        && !first.has("msToken") && !first.has("a_bogus") && !first.has("cookie"),
                "readonly comment jobs contain only video, cursor and count, never credentials or signatures");
        require(!CommentsPanel.sameCredentials(current,
                        new CredentialStore.Snapshot(current.cookie, current.msToken, "", 5))
                        && !CommentsPanel.sameCredentials(current,
                                new CredentialStore.Snapshot("sessionid=another", "", "", 4)),
                "cookie replacement and same-cookie generation changes invalidate pending comments");
    }

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        decor = (ViewGroup) getWindow().getDecorView();
        panel = new CommentsPanel(this, source);
        decor.addView(panel, new ViewGroup.LayoutParams(-1, -1));
        started = stageAt = SystemClock.elapsedRealtime();
        handler.postDelayed(check, 200);
    }

    private static final class Fixture implements CommentsPanel.PageSource {
        private static final String[] TEXTS = {
            "这个镜头拍得真好，晚霞的颜色也很舒服。",
            "原来还可以这样，学到了。",
            "慢慢看完才发现，最打动人的是那些不起眼的小细节。",
            "配乐和画面刚刚好。",
            "周末也想出去走走，吹吹风。",
            "这段看了好几遍，还是觉得很有意思。",
            "隔着屏幕都能感受到当时的开心。",
            "谢谢分享，认真生活的样子真的很美。"
        };
        private static final String[] NAMES = {
            "山间清风", "晚风", "小满", "知夏", "白日梦", "向晴", "听海", "慢慢来"
        };
        volatile boolean current = true;
        volatile int requested = -1, requests;
        final boolean delayed;
        final CountDownLatch release = new CountDownLatch(1);

        Fixture(boolean wait) { delayed = wait; }

        public boolean ready() { return true; }

        public boolean current() { return current; }

        public JSONObject load(int cursor) throws Exception {
            requested = cursor;
            requests++;
            if (delayed || cursor == 220) {
                // Deliberately outlive interruption to model an already-running HTTP read.
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            JSONArray comments = new JSONArray();
            // An empty but advancing page must not stop subsequent pages.
            if (cursor != 20) for (int i = cursor; i < cursor + 20; i++) {
                comments.put(new JSONObject()
                        .put("text", TEXTS[i % TEXTS.length])
                        .put("digg_count", i == 0 ? -1 : i)
                        .put("user", new JSONObject().put("nickname", NAMES[i % NAMES.length])));
            }
            return new JSONObject().put("comments", comments)
                    .put("cursor", cursor + 20).put("has_more", cursor < 320 ? 1 : 0);
        }
    }

    private Object field(Object target, String name) throws Exception {
        return InteractionController.field(target, name);
    }

    private ListView list() throws Exception { return (ListView) field(panel, "list"); }

    private boolean busy() throws Exception { return (Boolean) field(panel, "busy"); }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void key(int code) {
        panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void passive(View view) {
        require(!view.isFocusable() && !view.isClickable() && !view.isLongClickable(),
                "comment content exposes focus or action");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) passive(group.getChildAt(i));
        }
    }

    private String rowText(View row) throws Exception {
        // Likes are unique fixture data, so repeated natural text still identifies the same row.
        return ((TextView) field(row, "meta")).getText().toString()
                + "\n" + ((TextView) field(row, "body")).getText().toString();
    }

    private void savePopulatedScreenshot() throws Exception {
        Bitmap picture = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            decor.draw(new Canvas(picture));
            File directory = getExternalFilesDir(null);
            if (directory == null) directory = getFilesDir();
            File screenshot = new File(directory, "comments-populated.png");
            try (FileOutputStream out = new FileOutputStream(screenshot)) {
                require(picture.compress(Bitmap.CompressFormat.PNG, 100, out), "save populated screenshot");
            }
            require(screenshot.isFile() && screenshot.length() > 0, "populated screenshot exists");
            Log.i("Android5CommentsTest", "SCREENSHOT_PATH=" + screenshot.getCanonicalPath());
        } finally {
            picture.recycle();
        }
    }

    private void liveTransparency() throws Exception {
        for (int i = 0; i < list().getChildCount(); i++) {
            View row = list().getChildAt(i);
            require(Math.abs(((TextView) field(row, "body")).getTextSize()
                            - 2 * ((TextView) field(row, "meta")).getTextSize()) < 0.1f,
                    "live nicknames remain half the message text size");
        }
        require(panel.isLive() && panel.getWidth() <= decor.getWidth() * 2 / 5
                        && panel.getRight() == decor.getWidth(),
                "live chat occupies only a narrow right column");
        require(!panel.isClickable() && panel.getElevation() == 0,
                "live chat has no outside-click dismissal or panel shadow");
        Bitmap picture = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            int videoColor = 0xff407c98;
            picture.eraseColor(videoColor);
            Canvas canvas = new Canvas(picture);
            canvas.translate(panel.getLeft(), panel.getTop());
            panel.draw(canvas);
            int[] columns = {1, panel.getLeft() - 1, panel.getLeft() + 1, panel.getRight() - 2};
            for (int x : columns) for (int y = 1; y < picture.getHeight(); y += 17) {
                require(picture.getPixel(x, y) == videoColor,
                        "live video pixels remain undimmed outside text, including column padding");
            }
            File directory = getExternalFilesDir(null);
            if (directory == null) directory = getFilesDir();
            File screenshot = new File(directory, "live-comments-transparent.png");
            try (FileOutputStream out = new FileOutputStream(screenshot)) {
                require(picture.compress(Bitmap.CompressFormat.PNG, 100, out), "save live column screenshot");
            }
            Log.i("Android5CommentsTest", "LIVE_SCREENSHOT_PATH=" + screenshot.getCanonicalPath());
        } finally {
            picture.recycle();
        }
    }

    private void requireCircularPixels(ImageView avatar, int centerColor, boolean placeholder) {
        int size = avatar.getWidth();
        Bitmap rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            avatar.draw(new Canvas(rendered));
            int[] outside = {0, size / 10, size - size / 10 - 1, size - 1};
            for (int x : outside) for (int y : outside) {
                require(Color.alpha(rendered.getPixel(x, y)) == 0,
                        "avatar background and image corners must be transparent");
            }
            int[] inside = {size / 4, size / 2, size * 3 / 4};
            for (int x : inside) {
                int pixel = rendered.getPixel(x, size / 2);
                require(placeholder ? pixel == 0x26ffffff : pixel == centerColor,
                        placeholder ? "round placeholder is visible without previous image pixels"
                                : "loaded avatar remains visible and center cropped");
            }
            rendered.eraseColor(Color.BLUE);
            avatar.draw(new Canvas(rendered));
            for (int x : outside) for (int y : outside) {
                require(rendered.getPixel(x, y) == Color.BLUE,
                        "avatar mask must preserve the comment row underneath");
            }
        } finally {
            rendered.recycle();
        }
    }

    private void circularAvatar(ImageView avatar) {
        PreviewImages loader = new PreviewImages();
        Bitmap loaded = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        try {
            loader.bind(avatar, "");
            requireCircularPixels(avatar, 0, true);
            loaded.eraseColor(Color.RED);
            Paint paint = new Paint();
            paint.setColor(Color.GREEN);
            new Canvas(loaded).drawRect(20, 0, 60, 40, paint);
            // Models the loader's completion, including its still-present square background.
            avatar.setImageBitmap(loaded);
            requireCircularPixels(avatar, Color.GREEN, false);
            loader.bind(avatar, "");
            // API21 may retain an empty BitmapDrawable after setImageBitmap(null).
            // The rendered placeholder must return with no pixels from the old bitmap.
            requireCircularPixels(avatar, 0, true);
        } finally {
            avatar.setImageBitmap(null);
            loader.close();
            loaded.recycle();
        }
    }

    private final Runnable rapidDown = new Runnable() {
        public void run() {
            if (done) return;
            long now = SystemClock.uptimeMillis();
            panel.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_DOWN, 16 - repeatsRemaining));
            if (--repeatsRemaining > 0) handler.postDelayed(this, 25);
            else panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        }
    };

    private final Runnable check = new Runnable() {
        public void run() {
            if (done) return;
            long now = SystemClock.elapsedRealtime();
            try {
                require(now - started < 75000, "timeout stage=" + stage);
                ListView list = list();
                if (stage == 0 && list.getChildCount() > 1 && !busy()) {
                    require(panel.isFocused() && !list.isFocusable(), "panel owns remote focus");
                    require(!list.getAdapter().areAllItemsEnabled(), "comments cannot be selected");
                    for (int i = 0; i < list.getChildCount(); i++) passive(list.getChildAt(i));
                    View row = list.getChildAt(0);
                    TextView body = (TextView) field(row, "body");
                    TextView meta = (TextView) field(row, "meta");
                    require(meta.getText().toString().equals("山间清风"),
                            "unknown comment likes show nickname only, never a fabricated or negative count");
                    ImageView avatar = (ImageView) field(row, "avatar");
                    require(Math.abs(body.getTextSize() - 2 * meta.getTextSize()) < 0.1f,
                            "nickname and likes must be half the body size");
                    require(avatar.getWidth() == ModernMenuHelper.dp(CommentsSelfTestActivity.this, 40)
                            && avatar.getHeight() == avatar.getWidth(), "fixed square avatar");
                    require(avatar.getLeft() > row.getWidth() / 2
                            && Math.abs(avatar.getTop() + avatar.getHeight() / 2
                                    - row.getHeight() / 2) <= 1, "avatar on right and vertically centered");
                    circularAvatar(avatar);
                    require(((ViewGroup) list.getParent()).getChildCount() == 3,
                            "comment panel contains only title, status, list");
                    savePopulatedScreenshot();
                    anchorPosition = list.getFirstVisiblePosition();
                    anchorOffset = list.getChildAt(0).getTop();
                    key(KeyEvent.KEYCODE_DPAD_CENTER);
                    require(panel.getParent() == decor && panel.isFocused(), "center has no action");
                    key(KeyEvent.KEYCODE_DPAD_DOWN);
                    stage = 1;
                    stageAt = now;
                } else if (stage == 1 && now - stageAt > 200) {
                    require(list.getFirstVisiblePosition() > anchorPosition
                            || list.getChildAt(0).getTop() < anchorOffset, "remote down scrolls pixels");
                    key(KeyEvent.KEYCODE_DPAD_UP);
                    stage = 2;
                    stageAt = now;
                } else if (stage == 2 && now - stageAt > 200) {
                    require(list.getFirstVisiblePosition() == 0, "remote up returns to first row");
                    require(panel.isFocused(), "scrolling never moves focus to comments");
                    repeatsRemaining = 16;
                    handler.post(rapidDown);
                    stage = RAPID_SCROLL_STAGE;
                    stageAt = now;
                } else if (stage == RAPID_SCROLL_STAGE && repeatsRemaining == 0 && now - stageAt > 900) {
                    require(list.getFirstVisiblePosition() > 0 || list.getChildAt(0).getTop() < 0,
                            "rapid remote repeat continues scrolling");
                    require(panel.isFocused() && list.getSelectedItemPosition() == -1,
                            "rapid remote repeat never selects a comment");
                    stage = 3;
                } else if (stage == 3) {
                    require(list.getCount() <= 200, "retained comments exceeded memory bound");
                    if (source.requested == 220) {
                        // Let the last smooth scroll finish before recording its viewport anchor.
                        stage = 4;
                        stageAt = now;
                    } else if (!busy()) key(KeyEvent.KEYCODE_PAGE_DOWN);
                } else if (stage == 4 && now - stageAt > 200) {
                    anchorText = rowText(list.getChildAt(0));
                    anchorOffset = list.getChildAt(0).getTop();
                    source.release.countDown();
                    stage = 5;
                } else if (stage == 5 && !busy() && (Integer) field(panel, "cursor") >= 240) {
                    require(anchorText.equals(rowText(list.getChildAt(0)))
                            && Math.abs(anchorOffset - list.getChildAt(0).getTop()) <= 1,
                            "eviction must preserve visible comment and pixel offset");
                    stage = 6;
                } else if (stage == 6) {
                    require(list.getCount() <= 200, "pagination retained too many rows");
                    if (!(Boolean) field(panel, "hasMore") && !busy()) {
                        require((Integer) field(panel, "cursor") == 340 && source.requests == 17,
                                "pagination must pass 200 and an empty advancing page");
                        require(list.getCount() == 200, "bounded rolling window retains 200 comments");
                        for (int i = 0; i < list.getChildCount(); i++) passive(list.getChildAt(i));
                        require(panel.isFocused() && list.getSelectedItemPosition() == -1,
                                "pagination keeps all rows unfocused and unselected");
                        source.current = false;
                        stage = 7;
                        stageAt = now;
                    } else if (!busy()) key(KeyEvent.KEYCODE_PAGE_DOWN);
                } else if (stage == 7 && panel.getParent() == null) {
                    require(((List<?>) field(panel, "rows")).isEmpty(), "account switch clears rows");
                    require((Boolean) field(panel, "closed"), "account switch disposes panel");
                    delayed = new Fixture(true);
                    detached = new CommentsPanel(CommentsSelfTestActivity.this, delayed);
                    decor.addView(detached, new ViewGroup.LayoutParams(-1, -1));
                    stage = 8;
                } else if (stage == 8 && delayed.requested == 0) {
                    decor.removeView(detached);
                    delayed.release.countDown();
                    stage = 9;
                    stageAt = now;
                } else if (stage == 9 && now - stageAt > 500) {
                    require(detached.getParent() == null && (Boolean) field(detached, "closed"),
                            "external removal disposes safely");
                    require(((List<?>) field(detached, "rows")).isEmpty(),
                            "late network callback must not repopulate removed panel");
                    CommentsPanel explicit = new CommentsPanel(CommentsSelfTestActivity.this,
                            new Fixture(false));
                    decor.addView(explicit, new ViewGroup.LayoutParams(-1, -1));
                    explicit.close(false);
                    require(explicit.getParent() == null && (Boolean) field(explicit, "closed"),
                            "explicit close disposes safely");
                    playbackInvariant();
                    liveProtocol();
                    commentCredentials();
                    live = new LiveFixture();
                    panel = new CommentsPanel(CommentsSelfTestActivity.this, live);
                    decor.addView(panel, panel.windowLayout());
                    require(live.starts == 1, "live panel subscribes exactly once");
                    live.emit(0, 240);
                    stage = 10;
                    stageAt = now;
                } else if (stage == 10 && now - stageAt > 300) {
                    liveTransparency();
                    require(list().getCount() == 200, "live reading window retains at most 200 rows");
                    require(panel.isFocused() && list().getSelectedItemPosition() == -1,
                            "live messages remain unfocused and unselected");
                    for (int i = 0; i < list().getChildCount(); i++) {
                        View row = list().getChildAt(i);
                        passive(row);
                        require(((ImageView) field(row, "avatar")).getVisibility() == View.GONE,
                                "live messages do not add avatars");
                    }
                    key(KeyEvent.KEYCODE_MENU);
                    require(panel.getParent() == decor && !live.closed && live.starts == 1,
                            "focused live column consumes MENU without dismissing or resubscribing");
                    stage = 16;
                    stageAt = now;
                } else if (stage == 16 && now - stageAt > 5500) {
                    require(panel.getParent() == decor && !live.closed && live.starts == 1,
                            "live chat remains visible during idle time without auto-collapse");
                    live.emit(240, 241);
                    require(list().getCount() == 200,
                            "persistent live chat continues receiving bounded updates");
                    live.current = false;
                    stage = 11;
                } else if (stage == 11 && panel.getParent() == null) {
                    require(live.closed, "room/account change releases live subscription");
                    live.emit(240, 250);
                    require(((List<?>) field(panel, "rows")).isEmpty(),
                            "late live batches cannot repopulate a closed panel");
                    retry = new RetryFixture();
                    panel = new CommentsPanel(CommentsSelfTestActivity.this, retry, () -> retryTime);
                    decor.addView(panel, new ViewGroup.LayoutParams(-1, -1));
                    stage = 12;
                } else if (stage == 12 && retry.requests == 1 && !busy()) {
                    for (int i = 0; i < 40; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
                    retryTime += 29999;
                    key(KeyEvent.KEYCODE_DPAD_DOWN);
                    stage = 13;
                    stageAt = now;
                } else if (stage == 13 && now - stageAt > 300) {
                    require(retry.requests == 1 && !busy(),
                            "held down keys cannot bypass the 30-second comment retry cooldown");
                    retryTime++;
                    stage = 14;
                    stageAt = now;
                } else if (stage == 14 && now - stageAt > 300) {
                    require(retry.requests == 1, "cooldown expiry does not automatically retry");
                    key(KeyEvent.KEYCODE_DPAD_DOWN);
                    stage = 15;
                } else if (stage == 15 && retry.requests == 2 && !busy()) {
                    require((Long) field(panel, "retryAfter") == retryTime + 30000,
                            "an explicit scroll after expiry retries once and renews failure cooldown");
                    panel.close(false);
                    done = true;
                    Log.i("Android5CommentsTest", "PASS API21_COMMENTS_READ_ONLY_SCROLL_PAGING_LIFECYCLE");
                    return;
                }
            } catch (Exception e) {
                done = true;
                Log.e("Android5CommentsTest", "FAIL " + e.getMessage());
                cleanup();
                return;
            }
            handler.postDelayed(this, 180);
        }
    };

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        source.release.countDown();
        if (delayed != null) delayed.release.countDown();
        if (panel != null) panel.close(false);
        if (detached != null) detached.close(false);
    }

    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }
}
