package com.dycomment.tv;

import android.app.Activity;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Measure the actual legacy page after layout, including the larger-font CI matrix. */
final class LegacyGeometrySelfTest {
    private final Activity activity;
    private int controls, rows;

    private LegacyGeometrySelfTest(Activity activity) { this.activity = activity; }

    static void verify(Activity activity) {
        LegacyGeometrySelfTest test = new LegacyGeometrySelfTest(activity);
        float expected = activity.getIntent().getFloatExtra("expected_font_scale", 1f);
        test.require(Math.abs(activity.getResources().getConfiguration().fontScale - expected) < 0.02f,
                "font scale matches the requested CI scenario");
        test.walk(activity.findViewById(android.R.id.content));
        test.tabIndicators();
        test.require(test.controls > 0 && test.rows > 0, "real controls and toolbar rows were measured");
        android.util.Log.i("Android5LegacyUiTest", "GEOMETRY_OK " + activity.getClass().getSimpleName()
                + " controls=" + test.controls + " rows=" + test.rows + " fontScale=" + expected);
    }

    private int dp(int value) { return ModernMenuHelper.dp(activity, value); }

    private void tabIndicators() {
        if (!activity.getClass().getSimpleName().equals("ProfileActivity")) return;
        try {
            java.lang.reflect.Field barField = activity.getClass().getDeclaredField("tabBar");
            java.lang.reflect.Field indicatorField = activity.getClass().getDeclaredField("tabIndicator");
            barField.setAccessible(true); indicatorField.setAccessible(true);
            LinearLayout bar = (LinearLayout) barField.get(activity);
            View indicator = (View) indicatorField.get(activity);
            java.lang.reflect.Method move = activity.getClass().getDeclaredMethod("animateIndicator", TextView.class);
            move.setAccessible(true);
            require(bar.getChildCount() > 0 && indicator.getWidth() > 0, "tab indicator is laid out");
            indicatorCenter((TextView) bar.getChildAt(0), indicator);
            for (int index = 1; index < bar.getChildCount(); index++) {
                TextView label = (TextView) bar.getChildAt(index);
                if (label.getVisibility() == View.GONE) continue;
                move.invoke(activity, label);
                indicatorCenter(label, indicator);
            }
            move.invoke(activity, (TextView) bar.getChildAt(0));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("tab fixture", failure); }
    }

    private void indicatorCenter(TextView label, View indicator) {
        int[] textPosition = new int[2], barPosition = new int[2];
        label.getLocationOnScreen(textPosition); indicator.getLocationOnScreen(barPosition);
        require((label.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL,
                "tab label is horizontally centered in its visible control");
        float textCenter = textPosition[0] + (label.getCompoundPaddingLeft() + label.getWidth()
                - label.getCompoundPaddingRight()) / 2f;
        float barCenter = barPosition[0] + indicator.getWidth() / 2f;
        require(Math.abs(textCenter - barCenter) <= dp(1),
                "tab underline aligns with rendered text: difference=" + Math.abs(textCenter - barCenter));
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(activity.getClass().getSimpleName() + ": " + message);
    }

    private void textCenter(TextView text) {
        Layout layout = text.getLayout();
        require(layout != null && layout.getLineCount() == 1, "control or row label stays on one line");
        require((text.getGravity() & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL,
                "control or row label has vertical-center gravity");
        float center = text.getBaseline() + (layout.getLineAscent(0) + layout.getLineDescent(0)) / 2f;
        require(Math.abs(center - text.getHeight() / 2f) <= dp(2),
                "rendered text center matches its control box: difference="
                        + Math.abs(center - text.getHeight() / 2f));
        require(text.getPaddingTop() == text.getPaddingBottom(), "vertical padding is symmetric");
        require(layout.getHeight() <= text.getHeight() - text.getPaddingTop() - text.getPaddingBottom(),
                "larger text is not clipped vertically");
    }

    private void walk(View view) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView && view.isFocusable()) {
            TextView text = (TextView) view;
            controls++;
            textCenter(text);
            require(text.getHeight() >= dp(40), "control is at least 40dp high");
            int ceiling = Math.max(dp(44), UiTheme.controlHeight(text));
            require(text.getHeight() <= ceiling + dp(2), "control has no unnecessary vertical space");
            if (!(text instanceof EditText))
                require(text.getLayout().getEllipsisCount(0) == 0, "fixture action label is not truncated");
        }
        if (view instanceof LinearLayout && LegacyTheme.isControlRow((LinearLayout) view)) {
            LinearLayout row = (LinearLayout) view;
            rows++;
            require(!row.isBaselineAligned(), "toolbar does not align unequal font sizes by baseline");
            float center = (row.getPaddingTop() + row.getHeight() - row.getPaddingBottom()) / 2f;
            View previous = null;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child.getVisibility() == View.GONE) continue;
                require(Math.abs(child.getTop() + child.getHeight() / 2f - center) <= dp(1),
                        "buttons, fields and adjacent labels share a row center");
                if (previous != null) {
                    int gap = child.getLeft() - previous.getRight();
                    require(gap >= dp(8) - 1 && gap <= dp(12) + 1,
                            "adjacent controls have an 8–12dp gap: actual=" + gap);
                }
                if (child instanceof TextView) {
                    textCenter((TextView) child);
                    require(!((TextView) child).getText().toString().trim().equals("浏览发现好内容"),
                            "decorative Featured subtitle is removed without an empty placeholder");
                }
                previous = child;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i));
        }
    }
}
