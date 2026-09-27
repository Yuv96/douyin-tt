package com.dycomment.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import java.util.*;

/** The only send control is selecting a friend; there is no text composer. */
public final class QuickShareActivity extends Activity {
    final List<QuickShareApi.Friend> friends = new ArrayList<>();
    final Set<String> sent = new HashSet<>();
    final PreviewImages images = new PreviewImages();
    TextView status, more;
    ListView list;
    BaseAdapter adapter;
    String session, id, cursor = "0";
    boolean busy;
    int generation;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        session = SocialApi.cookie();
        id = getIntent().getStringExtra("video_id");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(dp(24), dp(18), dp(24), dp(18));
        root.setBackgroundColor(UiTheme.BLACK);
        TextView title = text("分享当前短视频", 26);
        root.addView(title);
        status = text(QuickShareApi.UNAVAILABLE, 18);
        root.addView(status);
        list = new ListView(this);
        list.setDividerHeight(dp(5));
        list.setSelector(ModernMenuHelper.background(true));
        adapter =
                new BaseAdapter() {
                    public int getCount() {
                        return friends.size();
                    }

                    public Object getItem(int p) {
                        return friends.get(p);
                    }

                    public long getItemId(int p) {
                        return p;
                    }

                    public View getView(int p, View old, ViewGroup parent) {
                        LinearLayout row;
                        if (old instanceof LinearLayout) row = (LinearLayout) old;
                        else {
                            row = new LinearLayout(QuickShareActivity.this);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(dp(12), dp(8), dp(12), dp(8));
                            row.setBackground(ModernMenuHelper.background(false));
                            ImageView avatar = new ImageView(QuickShareActivity.this);
                            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
                            TextView name = text("", 23);
                            name.setPadding(dp(14), 0, 0, 0);
                            row.addView(name);
                        }
                        QuickShareApi.Friend f = friends.get(p);
                        images.bind((ImageView) row.getChildAt(0), f.avatar);
                        ((TextView) row.getChildAt(1))
                                .setText(f.name + (sent.contains(f.uid) ? "  ·  已提交" : ""));
                        return row;
                    }
                };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, index, rowId) -> share(friends.get(index)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        more = text("加载更多好友", 20);
        more.setFocusable(true);
        more.setPadding(dp(16), dp(12), dp(16), dp(12));
        more.setBackground(ModernMenuHelper.background(false));
        more.setOnFocusChangeListener((v, f) -> v.setBackground(ModernMenuHelper.background(f)));
        more.setOnClickListener(v -> load());
        root.addView(more);
        setContentView(root);
        load();
    }

    int dp(int n) {
        return ModernMenuHelper.dp(this, n);
    }

    TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(-1);
        v.setTextSize(size);
        return v;
    }

    boolean valid(int token) {
        return token == generation
                && !isFinishing()
                && !isDestroyed()
                && session.equals(SocialApi.cookie());
    }

    void load() {
        if (busy) return;
        if (!SocialApi.personalCookie() || id == null || !id.matches("[0-9]+")) {
            status.setText("请先从电脑同步账号，再选择短视频分享");
            return;
        }
        busy = true;
        final int token = generation;
        more.setText("正在读取好友...");
        if (!SocialApi.submit(
                () -> {
                    try {
                        QuickShareApi.Page page = QuickShareApi.friends(session, cursor);
                        runOnUiThread(
                                () -> {
                                    if (!valid(token)) return;
                                    busy = false;
                                    Set<String> known = new HashSet<>();
                                    for (QuickShareApi.Friend f : friends) known.add(f.uid);
                                    for (QuickShareApi.Friend f : page.friends)
                                        if (known.add(f.uid)) friends.add(f);
                                    boolean next = page.more && !cursor.equals(page.cursor);
                                    cursor = page.cursor;
                                    adapter.notifyDataSetChanged();
                                    more.setText(next ? "加载更多好友" : "已显示全部好友");
                                    more.setEnabled(next);
                                    if (friends.isEmpty()) status.setText("暂无可分享的好友");
                                    else if (!list.hasFocus()) {
                                        list.requestFocus();
                                        list.setSelection(0);
                                    }
                                });
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    if (valid(token)) {
                                        busy = false;
                                        more.setText("读取失败，点击重试");
                                    }
                                });
                    }
                })) {
            busy = false;
            more.setText("请求较多，请稍后重试");
        }
    }

    void share(QuickShareApi.Friend friend) {
        if (busy || sent.contains(friend.uid)) return;
        if (!session.equals(SocialApi.cookie())) {
            status.setText("账号已切换，请返回后重新打开分享");
            return;
        }
        try {
            QuickShareApi.requireShareCapability();
        } catch (QuickShareApi.UnavailableException e) {
            status.setText(e.getMessage());
            return;
        }
        busy = true;
        final int token = generation;
        status.setText("正在分享给 " + friend.name + "...");
        if (!SocialApi.submit(
                () -> {
                    String message;
                    boolean ok = false;
                    try {
                        message =
                                QuickShareApi.share(
                                        QuickShareActivity.this, id, friend.uid, session);
                        ok = true;
                    } catch (QuickShareApi.UnavailableException e) {
                        message = e.getMessage();
                    } catch (Exception e) {
                        message = "分享未确认，请先在抖音检查后再重试";
                    }
                    final String result = message;
                    final boolean accepted = ok;
                    runOnUiThread(
                            () -> {
                                if (!valid(token)) return;
                                busy = false;
                                if (accepted) sent.add(friend.uid);
                                adapter.notifyDataSetChanged();
                                status.setText(result);
                            });
                })) {
            busy = false;
            status.setText("请求较多，视频尚未发送，请稍后重试");
        }
    }

    @Override
    protected void onDestroy() {
        generation++;
        images.close();
        super.onDestroy();
    }
}
