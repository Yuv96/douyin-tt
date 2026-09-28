package com.dycomment.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Real list layout and remote navigation, with synthetic metadata and no image requests. */
final class FollowedLiveSelfTest {
    static void run(Activity activity) {
        java.util.List<SocialApi.Live> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SocialApi.Live live = new SocialApi.Live();
            live.id = "fixture-room-" + i;
            live.title = "直播 " + i;
            live.author = "作者 " + i;
            live.secUid = "fixture-author-" + i;
            live.stream = "";
            live.preview = "";
            entries.add(live);
        }
        PreviewImages images = new PreviewImages();
        GridView grid = FollowedLiveActivity.createGrid(activity, entries, images);
        FrameLayout host = new FrameLayout(activity);
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        int width = ModernMenuHelper.dp(activity, 640);
        int height = ModernMenuHelper.dp(activity, 320);
        host.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        decor.addView(host, new ViewGroup.LayoutParams(width, height));
        try {
            layout(host, width, height);
            require(grid.getNumColumns() == 2 && grid.getChildCount() == 6,
                    "followed live renders six fixtures in two columns");
            View first = grid.getChildAt(0), right = grid.getChildAt(1), below = grid.getChildAt(2);
            require(first.getTop() == right.getTop()
                            && right.getLeft() - first.getRight() == ModernMenuHelper.dp(activity, 12)
                            && below.getLeft() == first.getLeft()
                            && below.getTop() - first.getBottom() == ModernMenuHelper.dp(activity, 8),
                    "followed live column and row geometry");
            for (int i = 0; i < entries.size(); i++) {
                LinearLayout card = (LinearLayout) grid.getChildAt(i);
                require(grid.getItemAtPosition(i) == entries.get(i)
                                && ((TextView) card.getChildAt(1)).getText().toString()
                                        .equals(entries.get(i).author + "\n" + entries.get(i).title),
                        "followed live card keeps its room mapping " + i);
            }
            require(grid.requestFocusFromTouch(), "followed live grid receives remote focus");
            grid.setSelection(0);
            layout(host, width, height);
            key(grid, KeyEvent.KEYCODE_DPAD_RIGHT);
            require(grid.getSelectedItemPosition() == 1, "RIGHT selects second live column");
            key(grid, KeyEvent.KEYCODE_DPAD_DOWN);
            require(grid.getSelectedItemPosition() == 3, "DOWN stays in second live column");
            key(grid, KeyEvent.KEYCODE_DPAD_LEFT);
            require(grid.getSelectedItemPosition() == 2, "LEFT selects first live column");

            LinearLayout reused = (LinearLayout) grid.getAdapter().getView(0, null, grid);
            ImageView preview = (ImageView) reused.getChildAt(0);
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            try {
                preview.setImageBitmap(bitmap);
                View rebound = grid.getAdapter().getView(5, reused, grid);
                require(rebound == reused && preview.getDrawable() == null
                                && ((TextView) reused.getChildAt(1)).getText().toString()
                                        .equals(entries.get(5).author + "\n" + entries.get(5).title),
                        "recycled live card drops old preview and updates room");
                PreviewImages.release(preview);
                require(preview.getDrawable() == null && preview.getTag() == null,
                        "released live preview retains no drawable or URL");
            } finally {
                PreviewImages.release(preview);
                bitmap.recycle();
            }
            Log.i("Android5InteractionTest", "FOLLOWED_LIVE_TWO_COLUMNS_REMOTE_REUSE_OK");
        } finally {
            decor.removeView(host);
            images.close();
        }
    }

    private static void layout(View host, int width, int height) {
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);
    }

    private static void key(GridView grid, int code) {
        grid.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        grid.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private static void require(boolean ok, String detail) {
        if (!ok) throw new IllegalStateException(detail);
    }
}
