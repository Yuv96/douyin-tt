package com.dycomment.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.List;

/** Native recycling keeps card/bitmap ownership proportional to the viewport, not account size. */
public final class ProfileGrid {
    static final int COLUMNS = 6, PAGE = 18;
    private static final int TAG = 0x7f0f7a64;
    private final Activity activity;
    private final ScrollView original;
    private final LinearLayout container, host;
    private final TextView status;
    private final GridView grid;
    private final ViewGroup parent;
    private final ViewGroup.LayoutParams originalParams;
    private final int parentIndex;
    private final List<?> items;
    private final Cards adapter = new Cards();
    private final Method play;
    private int exposed = PAGE, requested = PAGE, count, width;
    private boolean closed, paging;

    private ProfileGrid(Activity activity) throws Exception {
        this.activity = activity;
        original = (ScrollView) ProfileFeed.field(activity, "scrollView");
        container = (LinearLayout) ProfileFeed.field(activity, "videoContainer");
        status = (TextView) ProfileFeed.field(activity, "tvStatus");
        items = (List<?>) ProfileFeed.field(activity, "videoList");
        play = activity.getClass().getDeclaredMethod("lambda$buildVideoCard$4$com-dycomment-tv-ProfileActivity",
                Class.forName("com.dycomment.tv.DouyinApi$FeedItem"), int.class, View.class);
        play.setAccessible(true);
        ((List<?>) ProfileFeed.field(activity, "videoImageViews")).clear();
        parent = (ViewGroup) original.getParent();
        parentIndex = parent.indexOfChild(original);
        originalParams = original.getLayoutParams();
        host = new LinearLayout(activity);
        host.setOrientation(LinearLayout.VERTICAL);
        grid = new GridView(activity);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        grid.setHorizontalSpacing(dp(8)); grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        grid.setClipToPadding(false);
        grid.setDrawSelectorOnTop(true);
        GradientDrawable selector = new GradientDrawable();
        selector.setColor(Color.TRANSPARENT); selector.setCornerRadius(dp(6));
        selector.setStroke(dp(2), UiTheme.PINK);
        grid.setSelector(selector);
        grid.setRecyclerListener(view -> { if (view instanceof Card) ProfileImages.release(((Card) view).image); });
        grid.setOnItemClickListener((list, view, position, id) -> {
            if (position >= items.size()) return;
            try { play.invoke(activity, items.get(position), position, view); }
            catch (Exception failure) { android.util.Log.e("ProfileGrid", "Unable to open selected video"); }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> list) { }
            @Override public void onItemSelected(AdapterView<?> list, View view, int position, long id) {
                // Remote navigation into the last exposed row prepares the following three rows.
                if (grid.hasFocus() && position >= PAGE - COLUMNS && position >= count - COLUMNS) more();
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                // Layout of the first batch must never expand the whole list automatically.
                if (first >= COLUMNS && first + visible >= count - COLUMNS) more();
            }
        });
        grid.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int next = Math.max(1, Math.min(dp(176), (right - left - dp(16) - dp(8) * (COLUMNS - 1)) / COLUMNS));
            int side = Math.max(dp(8), (right - left - next * COLUMNS - dp(8) * (COLUMNS - 1)) / 2);
            if (grid.getPaddingLeft() != side) grid.setPadding(side, dp(8), side, dp(8));
            if (width != next) { width = next; grid.setColumnWidth(width); adapter.notifyDataSetChanged(); }
        });
        width = Math.max(1, Math.min(dp(176), (activity.getResources().getDisplayMetrics().widthPixels - dp(56)) / COLUMNS));
        grid.setColumnWidth(width);
        grid.setAdapter(adapter);
        host.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        if (status.getParent() instanceof ViewGroup) ((ViewGroup) status.getParent()).removeView(status);
        status.setPadding(dp(8), dp(8), dp(8), dp(8));
        host.addView(status, new LinearLayout.LayoutParams(-1, -2));
        parent.removeView(original);
        parent.addView(host, parentIndex, originalParams);
        refresh();
    }

    public static void show(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Object value = activity.getWindow().getDecorView().getTag(TAG);
            if (value instanceof ProfileGrid) ((ProfileGrid) value).refresh();
            else {
                ProfileGrid page = new ProfileGrid(activity);
                activity.getWindow().getDecorView().setTag(TAG, page);
            }
        } catch (Exception failure) { android.util.Log.e("ProfileGrid", "Unable to create profile grid", failure); }
    }

    public static void focus(Activity activity) {
        Object value = activity.getWindow().getDecorView().getTag(TAG);
        if (value instanceof ProfileGrid) {
            ProfileGrid page = (ProfileGrid) value;
            if (page.grid.getSelectedItemPosition() < 0 && page.count > 0) {
                page.grid.setSelection(0); page.grid.requestFocus();
            }
        }
    }

    static GridView view(Activity activity) {
        Object value = activity.getWindow().getDecorView().getTag(TAG);
        return value instanceof ProfileGrid ? ((ProfileGrid) value).grid : null;
    }

    static void more(Activity activity) {
        Object value = activity.getWindow().getDecorView().getTag(TAG);
        if (value instanceof ProfileGrid) ((ProfileGrid) value).more();
    }

    private void more() {
        if (closed || paging) return;
        paging = true;
        grid.post(() -> {
            paging = false;
            if (closed) return;
            requested = Math.max(requested, exposed + PAGE);
            if (items.size() > exposed) {
                exposed = Math.min(requested, items.size());
                refresh();
            } else ProfileFeed.more(activity);
        });
    }

    private void refresh() {
        if (closed) return;
        exposed = Math.min(Math.max(exposed, Math.min(requested, items.size())), items.size());
        int next = Math.min(exposed, items.size());
        status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (count != next) { count = next; adapter.notifyDataSetChanged(); }
    }

    public static void close(Activity activity) {
        Object value = activity.getWindow().getDecorView().getTag(TAG);
        if (!(value instanceof ProfileGrid)) return;
        ProfileGrid page = (ProfileGrid) value;
        page.closed = true;
        page.grid.setRecyclerListener(view -> { if (view instanceof Card) ProfileImages.release(((Card) view).image); });
        for (int i = 0; i < page.grid.getChildCount(); i++) {
            View child = page.grid.getChildAt(i);
            if (child instanceof Card) ProfileImages.release(((Card) child).image);
        }
        page.grid.setAdapter(null);
        page.host.removeView(page.status);
        page.container.addView(page.status, 0, new LinearLayout.LayoutParams(-1, -2));
        page.parent.removeView(page.host);
        page.parent.addView(page.original, Math.min(page.parentIndex, page.parent.getChildCount()), page.originalParams);
        activity.getWindow().getDecorView().setTag(TAG, null);
    }

    private int dp(int value) { return ModernMenuHelper.dp(activity, value); }

    private final class Cards extends BaseAdapter {
        @Override public int getCount() { return count; }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Card card = recycled instanceof Card ? (Card) recycled : new Card();
            card.setLayoutParams(new AbsListView.LayoutParams(width, Math.round(width * 4f / 3f)));
            Object item = getItem(position);
            try {
                card.title.setText((String) ProfileFeed.field(item, "desc"));
                int likes = (Integer) ProfileFeed.field(item, "likeCount");
                card.likes.setText(likes > 0 ? "赞 " + likes : "");
                card.likes.setVisibility(likes > 0 ? View.VISIBLE : View.GONE);
                ProfileImages.bind(activity, (String) ProfileFeed.field(item, "coverUrl"), card.image);
            } catch (Exception malformed) {
                card.title.setText(""); card.likes.setVisibility(View.GONE); ProfileImages.release(card.image);
            }
            return card;
        }
    }

    private final class Card extends FrameLayout {
        final ImageView image;
        final TextView title, likes;
        Card() {
            super(ProfileGrid.this.activity);
            image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout caption = new LinearLayout(activity);
            caption.setOrientation(LinearLayout.VERTICAL);
            caption.setPadding(dp(6), dp(4), dp(6), dp(4));
            caption.setBackgroundColor(0xb3000000);
            likes = UiTheme.text(activity, "", 11);
            likes.setSingleLine(true);
            title = UiTheme.text(activity, "", 10);
            title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            caption.addView(likes); caption.addView(title);
            FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            addView(caption, label);
            setFocusable(false); setClickable(false);
        }
    }
}
