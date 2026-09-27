package com.dycomment.tv;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.GridView;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import org.json.JSONObject;

/** Test APK only: feed boundaries and actual profile geometry, without account requests. */
final class ProfileFeedSelfTest {
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("profile: " + message);
    }

    static void run() throws Exception {
        require(LegacyTheme.profileColumns(1280, 1f) == 6, "television grid has six columns");
        require(LegacyTheme.profileColumns(360, 1f) == 6, "column count remains six when cells shrink");
        int portrait = PreviewImages.sampleSize(1080, 1920, 176, 235);
        require(1080 / portrait >= 132 && 1920 / portrait >= 235,
                "portrait decoder keeps enough pixels for the card instead of a 180px ceiling");
        int landscape = PreviewImages.sampleSize(1920, 1080, 176, 235);
        require(1920 / landscape >= 176, "landscape decode respects fitted width");
        require(PreviewImages.sampleSize(80, 120, 176, 235) == 1, "small originals are not downsampled");
        ProfileFeed.Page empty = ProfileFeed.parsePage(new JSONObject(
                "{\"status_code\":0,\"aweme_list\":[],\"has_more\":0}"), "0");
        require(empty.items.isEmpty() && !empty.more, "explicit empty list is valid");
        for (String invalid : new String[] {
                "{\"status_code\":0}",
                "{\"status_code\":8,\"aweme_list\":[]}",
                "{\"status_code\":0,\"aweme_list\":[],\"has_more\":1}",
                "{\"status_code\":0,\"aweme_list\":[],\"has_more\":1,\"max_cursor\":\"0\"}"}) {
            boolean rejected = false;
            try { ProfileFeed.parsePage(new JSONObject(invalid), "0"); }
            catch (Exception expected) { rejected = true; }
            require(rejected, "reject missing lists, unsuccessful business status and stuck cursors");
        }
        ProfileFeed.Page page = ProfileFeed.parsePage(new JSONObject(
                "{\"status_code\":0,\"has_more\":1,\"max_cursor\":\"20\",\"aweme_list\":["
                + "{\"aweme_id\":\"123\",\"desc\":\"original title\",\"author\":{\"nickname\":\"author\"},"
                + "\"video\":{\"origin_cover\":{\"url_list\":[\"https://example.test/original.jpg\"]},"
                + "\"cover\":{\"url_list\":[\"https://example.test/small.jpg\"]}}}]}"), "0");
        require(page.more && page.items.size() == 1 && "20".equals(page.cursor), "first page can render before later pages");
        require("https://example.test/original.jpg".equals(ProfileFeed.field(page.items.get(0), "coverUrl")),
                "prefer the original cover over a smaller thumbnail");
        require(((Integer) ProfileFeed.field(page.items.get(0), "likeCount")) == -1,
                "missing statistics remain unknown instead of inventing zero likes");
    }

    static void geometry(Activity activity) throws Exception {
        GridView grid = ProfileGrid.view(activity);
        require(grid != null && grid.getNumColumns() == 6, "six-column recycled grid is installed");
        require(grid.getCount() == 18, "only three rows are exposed initially despite a long source list");
        require(grid.getChildCount() > 0 && grid.getChildCount() <= 18, "only viewport cells are attached");
        require(grid.getChildCount() >= 6, "the first row contains six real cards");
        View first = grid.getChildAt(0), last = grid.getChildAt(5);
        require(Math.abs((first.getLeft() + last.getRight()) / 2f - grid.getWidth() / 2f) <= 1,
                "the six-card group is horizontally centered");
        for (int column = 1; column < 6; column++) {
            View previous = grid.getChildAt(column - 1), card = grid.getChildAt(column);
            require(card.getTop() == first.getTop()
                            && Math.abs(card.getLeft() - previous.getRight() - ModernMenuHelper.dp(activity, 8)) <= 1,
                    "six cards share a row with consistent spacing");
        }
        for (int i = 0; i < grid.getChildCount(); i++) {
            View card = grid.getChildAt(i);
            require(card.getWidth() <= ModernMenuHelper.dp(activity, 176), "bounded card width");
            require(Math.abs(card.getHeight() - card.getWidth() * 4f / 3f) <= 1f, "portrait card aspect");
            ImageView image = (ImageView) ((ViewGroup) card).getChildAt(0);
            require(image.getScaleType() == ImageView.ScaleType.FIT_CENTER, "centered cover without stretching");
        }
        require(((java.util.List<?>) ProfileFeed.field(activity, "videoImageViews")).isEmpty(),
                "legacy image list never retains every card bitmap");
        android.util.Log.i("Android5LegacyUiTest", "PROFILE_ADAPTIVE_GRID_OK columns=6 initial=18");
    }

    static void exerciseGrid(Activity activity, Runnable passed) {
        final View previousFocus = activity.getCurrentFocus();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(new Runnable() {
            int stage;
            int beforePaging;
            ImageView retired;
            Bitmap marker;
            @Override public void run() {
                try {
                    GridView grid = ProfileGrid.view(activity);
                    if (stage == 0) {
                        geometry(activity);
                        grid.requestFocusFromTouch(); grid.setSelection(0);
                    } else if (stage == 1) key(grid, KeyEvent.KEYCODE_DPAD_RIGHT);
                    else if (stage == 2) {
                        require(grid.getSelectedItemPosition() == 1, "remote right advances one item");
                        key(grid, KeyEvent.KEYCODE_DPAD_DOWN);
                    } else if (stage == 3) {
                        require(grid.getSelectedItemPosition() == 7, "remote down advances six items");
                        beforePaging = grid.getCount();
                    } else if (stage >= 4 && stage <= 9) {
                        ProfileGrid.more(activity);
                    } else if (stage == 10) {
                        require(grid.getCount() == beforePaging + 108 && grid.getCount() < 180,
                                "each user demand exposes one additional batch of eighteen");
                        retired = (ImageView) ((ViewGroup) grid.getChildAt(0)).getChildAt(0);
                        marker = Bitmap.createBitmap(320, 432, Bitmap.Config.ARGB_8888);
                        retired.setImageBitmap(marker);
                        grid.setSelection(60);
                    } else if (stage == 11) {
                        require(!(retired.getDrawable() instanceof BitmapDrawable)
                                        || ((BitmapDrawable) retired.getDrawable()).getBitmap() != marker,
                                "leaving the viewport releases the previous bitmap reference");
                        marker.recycle(); marker = null;
                        int rows = (grid.getHeight() + grid.getChildAt(0).getHeight() - 1)
                                / grid.getChildAt(0).getHeight() + 2;
                        require(grid.getChildCount() <= rows * 6 && grid.getChildCount() < grid.getCount(),
                                "hundreds of metadata entries never become hundreds of attached cards");
                        require(grid.getSelectedItemPosition() == 60, "deep scroll retains the selected item");
                        key(grid, KeyEvent.KEYCODE_DPAD_UP);
                    } else if (stage == 12) {
                        require(grid.getSelectedItemPosition() == 54, "remote up returns one six-item row");
                        ProfileFeed.cancel(activity);
                        require(ProfileGrid.view(activity) == null, "tab close restores original non-video page host");
                        LegacyTheme.profileGrid(activity);
                    } else {
                        geometry(activity);
                        if (previousFocus != null) previousFocus.requestFocus();
                        android.util.Log.i("Android5LegacyUiTest", "PROFILE_WINDOW_BITMAP_DPAD_OK");
                        passed.run();
                        return;
                    }
                    stage++;
                    main.postDelayed(this, 80);
                } catch (Exception failure) {
                    if (marker != null && !marker.isRecycled()) { PreviewImages.release(retired); marker.recycle(); }
                    android.util.Log.e("Android5LegacyUiTest", "FAIL profile recycled window", failure);
                }
            }
        });
    }

    private static void key(GridView grid, int code) {
        grid.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        grid.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
}
