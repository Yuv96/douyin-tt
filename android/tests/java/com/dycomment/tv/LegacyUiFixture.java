package com.dycomment.tv;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Test APK only: populate the real legacy builders without any account or network read. */
public final class LegacyUiFixture {
    public static boolean show(Activity activity) {
        if (!activity.getIntent().getBooleanExtra("legacy_ui_fixture", false)) return false;
        try {
            String name = activity.getClass().getSimpleName();
            ArrayList<Object> videos = new ArrayList<>();
            for (int i = 0; i < (name.equals("ProfileActivity") ? 180 : 8); i++) {
                Object video = model("FeedItem");
                put(video, "desc", i == 0 ? "保留用户标题😀 · 一起看见精彩" : "精彩生活 · 第 " + (i + 1) + " 集");
                put(video, "author", "创作者😀");
                put(video, "awemeId", "fixture-" + i);
                put(video, "likeCount", 1280 + i);
                videos.add(video);
            }
            if (name.equals("ProfileActivity")) {
                boolean self = activity.getIntent().getBooleanExtra("is_self", false);
                put(activity, "isSelf", self);
                ((TextView) get(activity, "tvNickname")).setText(self ? "我的主页😀" : "创作者😀");
                ((TextView) get(activity, "tvSignature")).setText("记录日常，抬头看见更大的世界");
                ((TextView) get(activity, "tvStats")).setText("关注 28   粉丝 1.2万   获赞 8.6万");
                ((View) get(activity, "tvStatus")).setVisibility(View.GONE);
                call(activity, "buildTabBar", new Class<?>[] {String[].class},
                        (Object) (self ? new String[] {"作品", "喜欢", "收藏", "稍后再看", "粉丝", "关注", "历史"}
                                : new String[] {"作品"}));
                put(activity, "videoList", videos);
                call(activity, "buildVideoGrid", new Class<?>[0]);
            } else if (name.equals("FeaturedActivity")) {
                put(activity, "videoList", videos);
                call(activity, "buildGrid", new Class<?>[0]);
                ArrayList<Object> hot = new ArrayList<>();
                for (String word : new String[] {"周末去哪儿", "城市漫步", "今日音乐"}) {
                    Object item = model("HotSearchItem");
                    put(item, "word", word);
                    hot.add(item);
                }
                call(activity, "buildHotTags", new Class<?>[] {List.class}, hot);
            } else if (name.equals("SearchActivity")) {
                put(activity, "resultList", videos);
                put(activity, "searchTab", 0);
                call(activity, "showSearchTabs", new Class<?>[0]);
            } else throw new IllegalStateException("unsupported fixture page");
            activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            activity.getWindow().getDecorView().postDelayed(() -> {
                try {
                    // A fresh emulator can still be in touch mode. Enumerate all
                    // remote targets before making the first target touch-focusable.
                    ArrayList<View> focusables = new ArrayList<>();
                    activity.getWindow().getDecorView().addFocusables(focusables,
                            View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
                    if (focusables.isEmpty()) throw new IllegalStateException("no remote focus targets");
                    for (View target : focusables) {
                        if (target instanceof android.widget.EditText) continue;
                        target.setFocusableInTouchMode(true);
                        target.requestFocus();
                        break;
                    }
                    android.util.Log.i("Android5LegacyUiTest", "FONT_CONFIGURATION actual="
                            + activity.getResources().getConfiguration().fontScale + " expected="
                            + activity.getIntent().getFloatExtra("expected_font_scale", 1f)
                            + " scaledDensity=" + activity.getResources().getDisplayMetrics().scaledDensity);
                    LegacyGeometrySelfTest.verify(activity);
                    if (name.equals("ProfileActivity")) {
                        ProfileFeedSelfTest.run();
                        ProfileFeedSelfTest.geometry(activity);
                        ProfileFeedSelfTest.exerciseGrid(activity, () -> android.util.Log.i("Android5LegacyUiTest",
                                "PASS " + name + " populated fixture; focus targets=" + focusables.size()));
                        return;
                    }
                    android.util.Log.i("Android5LegacyUiTest", "PASS " + name + " populated fixture; focus targets=" + focusables.size());
                } catch (Exception e) {
                    android.util.Log.e("Android5LegacyUiTest", "FAIL " + name, e);
                }
            }, 500);
        } catch (Exception e) {
            android.util.Log.e("Android5LegacyUiTest", "FAIL legacy fixture", e);
        }
        return true;
    }

    private static Object model(String type) throws Exception {
        Class<?> modelClass = Class.forName("com.dycomment.tv.DouyinApi$" + type);
        Object value;
        if (type.equals("FeedItem")) {
            // Same pinned constructor exercised by SwitchingSelfTestActivity.
            value = modelClass.getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance("fixture", "fixture video", "fixture author", "fixture-author");
        } else if (type.equals("HotSearchItem")) {
            // Verified against the pinned APK's sanitized model source in CI.
            value = modelClass.getConstructor(String.class, long.class, int.class)
                    .newInstance("fixture hot search", 12800L, 1);
        } else if (type.equals("UserItem")) {
            value = modelClass.getConstructor().newInstance();
        } else throw new IllegalArgumentException("unsupported fixture model: " + type);
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType() == String.class && field.get(value) == null) field.set(value, "");
            if (field.getType() == List.class && field.get(value) == null) field.set(value, new ArrayList<>());
        }
        return value;
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void put(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }
}
