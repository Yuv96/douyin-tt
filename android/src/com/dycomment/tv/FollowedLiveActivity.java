package com.dycomment.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Followed accounts only; errors and a genuinely empty list are separate states. */
public final class FollowedLiveActivity extends Activity {
    android.widget.GridView list;
    final List<SocialApi.Live> entries = new ArrayList<>();
    android.widget.BaseAdapter adapter;
    final PreviewImages images = new PreviewImages();
    TextView status, refresh;
    boolean loading;
    int generation;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.BLACK);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = button("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("  关注的直播");
        title.setTextSize(26);
        title.setTextColor(-1);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = button("刷新");
        refresh.setOnClickListener(v -> load());
        bar.addView(refresh);
        root.addView(bar);
        status = new TextView(this);
        status.setTextSize(18);
        status.setTextColor(UiTheme.MUTED);
        status.setPadding(dp(8), dp(14), dp(8), dp(14));
        root.addView(status);
        list = createGrid(this, entries, images);
        adapter = (android.widget.BaseAdapter) list.getAdapter();
        list.setOnItemClickListener((parent, view, position, id) -> open(entries.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refresh.requestFocus();
        load();
    }

    static android.widget.GridView createGrid(Activity activity, List<SocialApi.Live> entries, PreviewImages images) {
        android.widget.GridView grid = new android.widget.GridView(activity);
        grid.setNumColumns(2);
        grid.setColumnWidth(ModernMenuHelper.dp(activity, 280));
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(ModernMenuHelper.dp(activity, 12));
        grid.setVerticalSpacing(ModernMenuHelper.dp(activity, 8));
        grid.setBackgroundColor(UiTheme.BLACK);
        android.graphics.drawable.GradientDrawable selector = new android.graphics.drawable.GradientDrawable();
        selector.setColor(android.graphics.Color.TRANSPARENT);
        selector.setCornerRadius(ModernMenuHelper.dp(activity, 10));
        selector.setStroke(ModernMenuHelper.dp(activity, 2), UiTheme.PINK);
        grid.setSelector(selector);
        grid.setDrawSelectorOnTop(true);
        grid.setRecyclerListener(view -> {
            if (view instanceof LinearLayout) {
                android.view.View preview = ((LinearLayout) view).getChildAt(0);
                if (preview instanceof android.widget.ImageView) PreviewImages.release((android.widget.ImageView) preview);
            }
        });
        android.widget.BaseAdapter adapter =
                new android.widget.BaseAdapter() {
                    public int getCount() {
                        return entries.size();
                    }

                    public Object getItem(int p) {
                        return entries.get(p);
                    }

                    public long getItemId(int p) {
                        return p;
                    }

                    public android.view.View getView(
                            int p, android.view.View old, android.view.ViewGroup parent) {
                        LinearLayout row;
                        if (old instanceof LinearLayout) row = (LinearLayout) old;
                        else {
                            row = new LinearLayout(activity);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(ModernMenuHelper.dp(activity, 10), ModernMenuHelper.dp(activity, 8), ModernMenuHelper.dp(activity, 14), ModernMenuHelper.dp(activity, 8));
                            row.setBackground(ModernMenuHelper.background(false));
                            android.widget.ImageView preview =
                                    new android.widget.ImageView(activity);
                            preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                            row.addView(preview, new LinearLayout.LayoutParams(ModernMenuHelper.dp(activity, 144), ModernMenuHelper.dp(activity, 81)));
                            TextView label = new TextView(activity);
                            label.setTextSize(22);
                            label.setTextColor(-1);
                            label.setMaxLines(2);
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                            lp.leftMargin = ModernMenuHelper.dp(activity, 16);
                            row.addView(label, lp);
                        }
                        SocialApi.Live live = entries.get(p);
                        ((TextView) row.getChildAt(1)).setText(live.author + "\n" + live.title);
                        images.bind((android.widget.ImageView) row.getChildAt(0), live.preview);
                        return row;
                    }
                };
        grid.setAdapter(adapter);
        return grid;
    }

    int dp(int n) {
        return ModernMenuHelper.dp(this, n);
    }

    TextView button(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setTextColor(-1);
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(ModernMenuHelper.background(false));
        view.setOnFocusChangeListener(
                (v, focus) -> v.setBackground(ModernMenuHelper.background(focus)));
        return view;
    }

    void load() {
        if (loading) return;
        if (!SocialApi.personalCookie()) {
            status.setText("请先从电脑同步账号");
            return;
        }
        final String cookie = SocialApi.cookie();
        final int token = ++generation;
        loading = true;
        refresh.setText("加载中");
        status.setText("正在读取关注的直播...");
        entries.clear();
        adapter.notifyDataSetChanged();
        images.clear();
        if (!SocialApi.submit(
                () -> {
                    try {
                        List<SocialApi.Live> lives = SocialApi.followedLive(cookie);
                        runOnUiThread(
                                () -> {
                                    if (!valid(token, cookie)) return;
                                    loading = false;
                                    refresh.setText("刷新");
                                    status.setText(
                                            lives.isEmpty()
                                                    ? "你关注的人暂时没有开播。"
                                                    : "当前有 " + lives.size() + " 个关注的直播");
                                    entries.addAll(lives);
                                    adapter.notifyDataSetChanged();
                                    if (!entries.isEmpty()) {
                                        list.requestFocus();
                                        list.setSelection(0);
                                    }
                                });
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    if (!valid(token, cookie)) return;
                                    loading = false;
                                    refresh.setText("重试");
                                    status.setText("读取失败：" + e.getMessage());
                                });
                    }
                })) {
            loading = false;
            refresh.setText("重试");
            status.setText("请求较多，请稍后重试");
        }
    }

    boolean valid(int token, String cookie) {
        return !isFinishing()
                && !isDestroyed()
                && generation == token
                && cookie.equals(SocialApi.cookie());
    }

    void open(SocialApi.Live live) {
        if (live.stream.isEmpty()) {
            Toast.makeText(this, "直播暂不可播放，请刷新列表", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Class<?> itemClass = Class.forName("com.dycomment.tv.DouyinApi$FeedItem");
            Object item =
                    itemClass
                            .getConstructor(String.class, String.class, String.class, String.class)
                            .newInstance(live.id, live.title, live.author, live.secUid);
            InteractionController.field(item, "roomId", live.id);
            InteractionController.field(item, "isLive", true);
            InteractionController.field(item, "videoUrl", live.stream);
            InteractionController.field(item, "liveStreamUrl", live.stream);
            InteractionController.field(item, "awemeType", 101);
            Class<?> main = Class.forName("com.dycomment.tv.MainActivity");
            ArrayList<Object> feed = new ArrayList<>();
            feed.add(item);
            main.getField("profileVideoList").set(null, feed);
            main.getField("profilePlayIndex").setInt(null, 0);
            main.getField("profileIsList").setBoolean(null, true);
            Intent intent = new Intent(this, main);
            intent.putExtra("from_profile", true);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "直播暂时无法打开", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        generation++;
        images.close();
        super.onDestroy();
    }
}
