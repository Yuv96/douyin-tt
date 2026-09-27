package com.dycomment.tv;

import android.app.Activity;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared, API-21-safe black / pink / white components. */
final class UiTheme {
    static final int BLACK = 0xff000000, WHITE = 0xffffffff, MUTED = 0xb3ffffff, PINK = 0xffff5b79;

    static TextView text(Activity a, String label, int size) {
        TextView v = new TextView(a);
        v.setText(label);
        v.setTextColor(WHITE);
        v.setTextSize(size);
        return v;
    }

    static TextView button(Activity a, String label, Runnable action) {
        TextView v = text(a, label, 20);
        v.setGravity(Gravity.CENTER);
        alignControl(v);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(ModernMenuHelper.background(false));
        v.setOnFocusChangeListener(
                (view, focus) -> view.setBackground(ModernMenuHelper.background(focus)));
        v.setOnClickListener(view -> action.run());
        return v;
    }

    static int controlHeight(TextView text) {
        float sp = text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity;
        android.graphics.Paint.FontMetrics metrics = text.getPaint().getFontMetrics();
        int line = (int) Math.ceil(metrics.descent - metrics.ascent);
        return Math.max(ModernMenuHelper.dp(text.getContext(), sp <= 14.1f ? 40 : 44),
                line + ModernMenuHelper.dp(text.getContext(), 12));
    }

    /** Only interactive single-line text, never descriptions or user-content paragraphs. */
    static void alignControl(TextView text) {
        text.setIncludeFontPadding(false);
        int horizontal = text instanceof EditText ? Gravity.START
                : text.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        text.setGravity(horizontal | Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        if (!(text instanceof EditText)) text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int x = ModernMenuHelper.dp(text.getContext(), 12);
        int y = ModernMenuHelper.dp(text.getContext(), 6);
        text.setPadding(x, y, x, y);
        text.setMinimumHeight(controlHeight(text));
    }

    static LinearLayout page(Activity a, String title) {
        a.getWindow().setStatusBarColor(BLACK);
        a.getWindow().setNavigationBarColor(BLACK);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(1);
        root.setBackgroundColor(BLACK);
        int p = ModernMenuHelper.dp(a, 24);
        root.setPadding(p, p, p, p);
        root.addView(text(a, title, 26));
        return root;
    }
}
