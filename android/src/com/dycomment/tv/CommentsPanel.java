package com.dycomment.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.util.*;
import java.util.concurrent.*;

/** Read-only, recycled comments. The retained window does not limit server pagination. */
final class CommentsPanel extends FrameLayout {
    private static final int MAX_COMMENTS = 200;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final PreviewImages images = new PreviewImages();
    private final List<Comment> rows = new ArrayList<>();
    private final PageSource source;
    private final LiveMessages.Source live;
    private final TimeSource clock;
    private final TextView status;
    private final ListView list;
    private final BaseAdapter adapter;
    private int cursor;
    private long retryAfter;
    private boolean busy, closed, retryBlocked, restoring, hasMore = true;

    interface PageSource {
        JSONObject load(int cursor) throws Exception;

        boolean ready();

        boolean current();
    }

    interface TimeSource { long now(); }

    private static PageSource remote(Activity a, String video) {
        final CredentialStore.Snapshot credentials = CredentialStore.snapshot(a);
        final int selection = PlaybackCoordinator.token(a);
        return new PageSource() {
            public boolean ready() {
                return current() && SocialApi.personalCookie() && CredentialStore.hasSession(credentials.cookie);
            }

            public boolean current() {
                return sameCredentials(credentials, CredentialStore.snapshot(a))
                        && PlaybackCoordinator.valid(a, selection);
            }

            public JSONObject load(int cursor) throws Exception {
                if (!current()) throw new Exception("评论会话已更新");
                return BrowserActionBroker.perform(a, "comments", arguments(video, cursor), credentials.cookie);
            }
        };
    }

    static boolean sameCredentials(CredentialStore.Snapshot expected, CredentialStore.Snapshot current) {
        return expected.generation == current.generation && expected.cookie.equals(current.cookie);
    }

    static JSONObject arguments(String video, int cursor) throws JSONException {
        // The paired official browser owns request signing and its current token context.
        return new JSONObject().put("video_id", video).put("cursor", cursor).put("count", 20);
    }

    CommentsPanel(Activity a, String id) {
        this(a, remote(a, id));
    }

    CommentsPanel(Activity a, PageSource pages) {
        this(a, pages, (LiveMessages.Source) null);
    }

    CommentsPanel(Activity a, LiveMessages.Source messages) {
        this(a, null, messages);
    }

    CommentsPanel(Activity a, PageSource pages, TimeSource clock) {
        this(a, pages, null, clock);
    }

    private CommentsPanel(Activity a, PageSource pages, LiveMessages.Source messages) {
        this(a, pages, messages, () -> android.os.SystemClock.elapsedRealtime());
    }

    private CommentsPanel(Activity a, PageSource pages, LiveMessages.Source messages, TimeSource clock) {
        super(a);
        activity = a;
        source = pages;
        live = messages;
        this.clock = clock;
        setId(a.getResources().getIdentifier("android5_comments_panel", "id", a.getPackageName()));
        setBackgroundColor(0x80000000);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout box = UiTheme.page(a, live == null ? "评论" : "直播弹幕");
        addView(box, new FrameLayout.LayoutParams(
                Math.min(ModernMenuHelper.dp(a, 420),
                        a.getResources().getDisplayMetrics().widthPixels * 3 / 4),
                -1, Gravity.RIGHT));
        status = UiTheme.text(a, "", 14);
        status.setTextColor(UiTheme.MUTED);
        status.setVisibility(GONE);
        box.addView(status);
        list = new ReadingList(a);
        list.setDivider(new ColorDrawable(0x18ffffff));
        list.setDividerHeight(ModernMenuHelper.dp(a, 1));
        list.setSelector(new ColorDrawable(0x00000000));
        list.setCacheColorHint(0x00000000);
        list.setFocusable(false);
        list.setFocusableInTouchMode(false);
        list.setItemsCanFocus(false);
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        adapter = new BaseAdapter() {
            public int getCount() { return rows.size(); }

            public Object getItem(int p) { return rows.get(p); }

            public long getItemId(int p) { return p; }

            public boolean areAllItemsEnabled() { return false; }

            public boolean isEnabled(int p) { return false; }

            public View getView(int p, View old, ViewGroup parent) {
                CommentRow row = old instanceof CommentRow ? (CommentRow) old : new CommentRow(a);
                Comment comment = rows.get(p);
                row.meta.setText(live == null && comment.likes >= 0
                        ? comment.nickname + "  ·  " + comment.likes + " 赞" : comment.nickname);
                row.body.setText(comment.text);
                row.avatar.setVisibility(live == null ? VISIBLE : GONE);
                if (live == null) images.bind(row.avatar, comment.avatar);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) retryBlocked = false;
            }

            public void onScroll(AbsListView view, int first, int visible, int total) {
                loadAtBottom();
            }
        });
        list.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) retryBlocked = false;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) main.post(() -> loadAtBottom());
            return false;
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setOnClickListener(v -> close(true));
        box.setClickable(true);
        requestFocus();
        if (live == null) load();
    }

    /** Disabled rows must still support viewport anchoring after an eviction on a remote-only TV. */
    private static final class ReadingList extends ListView {
        ReadingList(Activity a) { super(a); }

        @Override
        public boolean isInTouchMode() {
            // ListView otherwise refuses setSelectionFromTop when every row is disabled.
            // Touch-mode positioning changes the viewport without creating a selected item.
            return true;
        }
    }

    private static final class Comment {
        final String nickname, text, avatar;
        final long likes;

        Comment(JSONObject comment) {
            JSONObject user = comment.optJSONObject("user");
            nickname = clipped(user == null ? "抖音用户" : user.optString("nickname", "抖音用户"), 100);
            text = clipped(comment.optString("text"), 2000);
            likes = comment.optLong("digg_count", -1);
            avatar = user == null ? "" : SocialApi.imageUrl(user.optJSONObject("avatar_thumb"));
        }

        Comment(LiveChatController.Chat message) {
            nickname = clipped(message.author, 100);
            text = clipped(message.content, 2000);
            likes = 0;
            avatar = "";
        }
    }

    private static String clipped(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private static final class CommentRow extends LinearLayout {
        final TextView meta, body;
        final ImageView avatar;

        CommentRow(Activity a) {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, ModernMenuHelper.dp(a, 14), 0, ModernMenuHelper.dp(a, 14));
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setFocusable(false);
            setClickable(false);
            setLongClickable(false);
            LinearLayout words = new LinearLayout(a);
            words.setOrientation(VERTICAL);
            meta = UiTheme.text(a, "", 10);
            meta.setTextColor(UiTheme.MUTED);
            body = UiTheme.text(a, "", 20);
            body.setPadding(0, ModernMenuHelper.dp(a, 5), 0, 0);
            words.addView(meta);
            words.addView(body);
            addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            avatar = new CircularAvatar(a);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(
                    ModernMenuHelper.dp(a, 40), ModernMenuHelper.dp(a, 40));
            picture.leftMargin = ModernMenuHelper.dp(a, 14);
            addView(avatar, picture);
        }
    }

    /** Mask the whole view, including the placeholder background installed by PreviewImages. */
    private static final class CircularAvatar extends ImageView {
        private final Path corners = new Path();
        private final Paint erase = new Paint(Paint.ANTI_ALIAS_FLAG);

        CircularAvatar(Activity a) {
            super(a);
            erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            corners.reset();
            corners.setFillType(Path.FillType.EVEN_ODD);
            corners.addRect(0, 0, w, h, Path.Direction.CW);
            corners.addCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, Path.Direction.CW);
        }

        @Override
        public void draw(Canvas canvas) {
            // An isolated layer keeps the mask from erasing the comment row beneath it.
            // Unlike outline clipping, this also works when API21 screenshots use a software Canvas.
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null, Canvas.ALL_SAVE_FLAG);
            super.draw(canvas);
            canvas.drawPath(corners, erase);
            canvas.restoreToCount(layer);
        }
    }

    private void loadAtBottom() {
        if (live != null || closed || restoring || retryBlocked || list.getHeight() == 0) return;
        if (rows.isEmpty() || !list.canScrollVertically(1)) load();
    }

    private void status(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    private void load() {
        if (live != null || busy || !hasMore || !active() || clock.now() < retryAfter) return;
        if (!source.ready()) {
            // Account state lives in the time capsule, not in the reading area.
            status("");
            retryBlocked = true;
            return;
        }
        busy = true;
        if (rows.isEmpty()) status("读取评论中…");
        final int next = cursor;
        work.execute(() -> {
            try {
                JSONObject data = source.load(next);
                JSONArray comments = data.optJSONArray("comments");
                if (comments == null) comments = new JSONArray();
                List<Comment> page = new ArrayList<>();
                for (int i = 0; i < Math.min(comments.length(), 20); i++) {
                    JSONObject comment = comments.optJSONObject(i);
                    if (comment != null) page.add(new Comment(comment));
                }
                final int end = data.optInt("cursor", next + comments.length());
                final boolean morePages = (data.optBoolean("has_more", false)
                        || data.optInt("has_more", 0) == 1) && end > next;
                main.post(() -> {
                    if (!active()) return;
                    // Keep the current first visible row at exactly the same vertical offset.
                    int first = list.getFirstVisiblePosition();
                    View top = list.getChildAt(0);
                    int offset = top == null ? 0 : top.getTop() - list.getPaddingTop();
                    rows.addAll(page);
                    int removed = Math.max(0, rows.size() - MAX_COMMENTS);
                    if (removed > 0) rows.subList(0, removed).clear();
                    cursor = end;
                    hasMore = morePages;
                    restoring = true;
                    adapter.notifyDataSetChanged();
                    if (removed > 0) list.setSelectionFromTop(Math.max(0, first - removed), offset);
                    status(rows.isEmpty() ? (hasMore ? "读取评论中…" : "暂无评论") : "");
                    // Let ListView lay out the appended rows before deciding if it is still at bottom.
                    list.postOnAnimation(() -> {
                        if (!active()) return;
                        restoring = false;
                        busy = false;
                        loadAtBottom();
                    });
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!active()) return;
                    busy = false;
                    retryBlocked = true;
                    retryAfter = clock.now() + 30000;
                    status(CredentialHealth.needsRefresh() ? "" : "评论暂不可用");
                });
            }
        });
    }

    private boolean active() {
        if (closed) return false;
        if (activity.isFinishing() || activity.isDestroyed()
                || !(live == null ? source.current() : live.current())) {
            close(true);
            return false;
        }
        return true;
    }

    private final Runnable sessionWatch = new Runnable() {
        public void run() {
            if (active()) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
        main.post(sessionWatch);
        if (live != null) live.start(new LiveMessages.Listener() {
            public void messages(List<LiveChatController.Chat> messages) {
                if (!active()) return;
                boolean follow = rows.isEmpty() || !list.canScrollVertically(1);
                int first = list.getFirstVisiblePosition();
                View top = list.getChildAt(0);
                int offset = top == null ? 0 : top.getTop() - list.getPaddingTop();
                for (LiveChatController.Chat message : messages) rows.add(new Comment(message));
                int removed = Math.max(0, rows.size() - MAX_COMMENTS);
                if (removed > 0) rows.subList(0, removed).clear();
                adapter.notifyDataSetChanged();
                if (follow) list.setSelectionFromTop(Math.max(0, rows.size() - 1), 0);
                else if (removed > 0) list.setSelectionFromTop(Math.max(0, first - removed), offset);
                status("");
            }

            public void unavailable() {
                if (active() && rows.isEmpty()) status("直播弹幕暂不可用");
            }
        });
    }

    private void dispose() {
        if (closed) return;
        closed = true;
        if (live != null) live.close();
        work.shutdownNow();
        images.close();
        main.removeCallbacksAndMessages(null);
        rows.clear();
        adapter.notifyDataSetChanged();
        try {
            if (InteractionController.field(activity, "commentOverlay") == this) {
                InteractionController.field(activity, "commentOverlay", null);
                InteractionController.field(activity, "menuShowing", false);
            }
        } catch (Exception ignored) {
        }
    }

    void close(boolean resume) {
        if (closed) return;
        dispose();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        // Reading comments never owns a playback pause, including a manually paused video.
    }

    @Override
    protected void onDetachedFromWindow() {
        // Do not reenter removeView while its API21 traversal is already removing this child.
        dispose();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) close(true);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_PAGE_UP) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && active()) {
                retryBlocked = false;
                boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN;
                int distance = (key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_PAGE_UP)
                        ? list.getHeight() * 3 / 4 : ModernMenuHelper.dp(activity, 88);
                list.smoothScrollBy(down ? distance : -distance, 120);
                if (down) loadAtBottom();
            }
            return true;
        }
        // Center/enter and horizontal keys never select comments or reach the underlying video.
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        return super.dispatchKeyEvent(e);
    }

    static void show(Activity a) {
        try {
            List<?> feed = (List<?>) InteractionController.field(a, "feedList");
            int index = (Integer) InteractionController.field(a, "currentIndex");
            if (index < 0 || index >= feed.size()) return;
            Object old = InteractionController.field(a, "commentOverlay");
            if (old instanceof CommentsPanel) ((CommentsPanel) old).close(false);
            InteractionController controller = InteractionController.get(a);
            controller.showing(true);
            Object item = feed.get(index);
            boolean isLive = Boolean.TRUE.equals(InteractionController.field(item, "isLive"));
            CommentsPanel panel = isLive
                    ? new CommentsPanel(a, LiveMessages.remote(a, InteractionController.text(item, "roomId")))
                    : new CommentsPanel(a, InteractionController.text(item, "awemeId"));
            InteractionController.field(a, "commentOverlay", panel);
            ((ViewGroup) a.getWindow().getDecorView()).addView(panel, new ViewGroup.LayoutParams(-1, -1));
        } catch (Exception e) {
            Toast.makeText(a, "评论暂不可用", Toast.LENGTH_SHORT).show();
        }
    }
}
