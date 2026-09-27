package com.dycomment.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pixel-level API-21 checks for the legacy drawing adapter, without network data. */
final class LegacyThemeSelfTest {
    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException("legacy theme: " + detail);
    }

    static void run(Activity activity) {
        try {
            java.lang.reflect.Field field = Class.forName("com.dycomment.tv.DouyinApi")
                    .getDeclaredField("QUALITY_NAMES");
            field.setAccessible(true);
            String[] quality = (String[]) field.get(null);
            require(quality.length > 0, "quality picker has labels");
            for (String label : quality) {
                for (int i = 0; i < label.length(); i++) {
                    char c = label.charAt(i);
                    require(!Character.isSurrogate(c) && !(c >= 0x2600 && c <= 0x27ff),
                            "quality picker retains plain text");
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("legacy quality picker schema changed", e);
        }
        LinearLayout header = new LinearLayout(activity);
        GradientDrawable old = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xff2444ff, 0xffa622ff});
        LegacyTheme.background(header, old);
        require(pixel(header.getBackground(), 32, 32) == UiTheme.BLACK,
                "colored header gradient becomes black");

        TextView title = new TextView(activity);
        String userText = "作者😀 · 喜欢❤ 原标题";
        title.setText(userText);
        LegacyTheme.textColor(title, 0xffffaa00);
        require(title.getText().toString().equals(userText), "dynamic emoji text is preserved");
        require(title.getCurrentTextColor() == UiTheme.PINK, "warm legacy accent uses pink");
        LegacyTheme.textColor(title, 0xff777777);
        require(title.getCurrentTextColor() == UiTheme.MUTED, "secondary text remains legible");

        View bubble = new View(activity);
        GradientDrawable circle = new GradientDrawable();
        LegacyTheme.drawableColor(circle, 0xff3399ff);
        LegacyTheme.drawableShape(circle, GradientDrawable.OVAL);
        LegacyTheme.background(bubble, circle);
        require(Color.alpha(pixel(bubble.getBackground(), 32, 32)) == 0,
                "decorative bubbles are removed");
        View divider = new View(activity);
        LegacyTheme.background(divider, new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xffff2255, 0xff2244ff}));
        int dividerPixel = pixel(divider.getBackground(), 32, 32);
        require(Color.red(dividerPixel) == Color.green(dividerPixel)
                        && Color.green(dividerPixel) == Color.blue(dividerPixel),
                "header accent stripe becomes a neutral divider");

        TextView tab = new TextView(activity);
        tab.setFocusable(true);
        GradientDrawable selected = new GradientDrawable();
        LegacyTheme.drawableColor(selected, 0xfffe2c55);
        LegacyTheme.background(tab, selected);
        require(tab.isSelected(), "selected tab survives flattening");
        require(tab.getBackground() instanceof GradientDrawable,
                "legacy getBackground casts remain valid");
        final boolean[] called = {false};
        LegacyTheme.focusListener(tab, (view, focused) -> {
            called[0] = true;
            view.setScaleX(1.05f);
            view.setElevation(10);
        });
        tab.getOnFocusChangeListener().onFocusChange(tab, true);
        require(called[0] && tab.getScaleX() == 1f && tab.getElevation() == 0,
                "original focus behavior runs without zoom or glow");
        require(pixel(tab.getBackground(), 32, 32) == UiTheme.BLACK,
                "selected control fill stays black");
        LegacyTheme.drawableColor((GradientDrawable) tab.getBackground(), 0xff262626);
        require(!tab.isSelected(), "in-place tab mutation clears the old selection");
        LegacyTheme.drawableColor((GradientDrawable) tab.getBackground(), 0xfffe2c55);
        require(tab.isSelected() && pixel(tab.getBackground(), 32, 32) == UiTheme.BLACK,
                "in-place selected accent preserves a black fill");
        TextView legacyButton = new TextView(activity);
        legacyButton.setText("返回");
        legacyButton.setFocusable(true);
        final int[] legacyCalls = {0};
        legacyButton.setOnFocusChangeListener((view, focused) -> {
            legacyCalls[0]++;
            view.setBackgroundColor(focused ? 0xff444455 : 0xff333344);
            view.setScaleX(focused ? 1.1f : 0.95f);
            view.setScaleY(focused ? 1.1f : 0.95f);
            view.setElevation(8f);
        });
        java.util.WeakHashMap<View, Boolean> styled = new java.util.WeakHashMap<>();
        LegacyTheme.styleNew(legacyButton, styled);
        require(legacyButton.getMinimumWidth() == ModernMenuHelper.dp(activity, 72)
                        && legacyButton.getGravity() == android.view.Gravity.CENTER,
                "plain-text back controls reserve width and center their label");
        View.OnFocusChangeListener installed = legacyButton.getOnFocusChangeListener();
        LegacyTheme.styleNew(legacyButton, styled);
        require(installed == legacyButton.getOnFocusChangeListener(),
                "later layouts do not repeatedly wrap the installed listener");
        installed.onFocusChange(legacyButton, true);
        installed.onFocusChange(legacyButton, false);
        require(legacyCalls[0] == 2, "external legacy listener runs exactly once per focus event");
        require(legacyButton.getScaleX() == 1f && legacyButton.getScaleY() == 1f
                        && legacyButton.getElevation() == 0f
                        && pixel(legacyButton.getBackground(), 32, 32) == UiTheme.BLACK,
                "external focus listener cannot restore its old gray fill or zoom after blur");
        TextView animatorButton = new TextView(activity);
        animatorButton.setFocusable(true);
        final int[] animatorCalls = {0}, clickCalls = {0};
        animatorButton.setOnFocusChangeListener((view, focused) -> animatorCalls[0]++);
        animatorButton.setOnClickListener(view -> clickCalls[0]++);
        LegacyTheme.setupFocus(animatorButton);
        animatorButton.getOnFocusChangeListener().onFocusChange(animatorButton, true);
        animatorButton.getOnFocusChangeListener().onFocusChange(animatorButton, false);
        animatorButton.performClick();
        require(animatorCalls[0] == 0 && clickCalls[0] == 1,
                "replacement skips the old animator while retaining its navigation click");
        require(pixel(animatorButton.getBackground(), 32, 32) == UiTheme.BLACK,
                "replacement returns to the theme's black fill on blur");
        TextView description = new TextView(activity);
        description.setText("用户简介😀\n第二行仍是用户内容");
        description.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        description.setPadding(1, 2, 3, 4);
        description.setMaxLines(3);
        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(description);
        LegacyTheme.styleNew(textColumn, new java.util.WeakHashMap<>());
        require((description.getGravity() & android.view.Gravity.VERTICAL_GRAVITY_MASK)
                        == android.view.Gravity.TOP && description.getMaxLines() == 3
                        && description.getPaddingTop() == 2 && description.getPaddingBottom() == 4
                        && description.getText().toString().contains("\n"),
                "control alignment does not center or rewrite user descriptions");
        android.util.Log.i("Android5InteractionTest", "LEGACY_THEME_GRADIENT_FOCUS_USER_TEXT_OK");
    }

    private static int pixel(android.graphics.drawable.Drawable drawable, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 64, 64);
        drawable.draw(new Canvas(bitmap));
        int color = bitmap.getPixel(x, y);
        bitmap.recycle();
        return color;
    }
}
