package com.dycomment.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/** Shared drawing policy for the three pinned, programmatically built legacy pages. */
public final class LegacyTheme {
    private static final WeakHashMap<GradientDrawable, Boolean> ovals = new WeakHashMap<>();
    private static final WeakHashMap<GradientDrawable, Integer> colors = new WeakHashMap<>();
    private static final WeakHashMap<GradientDrawable, WeakReference<View>> owners = new WeakHashMap<>();
    private static final WeakHashMap<LinearLayout, Boolean> alignedRows = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<TextView>> indicators = new WeakHashMap<>();
    private LegacyTheme() {}

    static int profileColumns(int available, float density) { return ProfileGrid.COLUMNS; }

    /** All profile video tabs share the same recycled, incrementally exposed grid. */
    public static void profileGrid(Activity activity) { ProfileGrid.show(activity); }

    public static void appendProfileGrid(Activity activity, List<?> page) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            if (((Integer) ProfileFeed.field(activity, "currentTab")) != 0) return;
            @SuppressWarnings("unchecked") List<Object> videos = (List<Object>) ProfileFeed.field(activity, "videoList");
            videos.addAll(page);
            profileGrid(activity);
        } catch (Exception error) {
            android.util.Log.e("ProfileGrid", "Unable to append profile grid", error);
        }
    }

    public static void attach(Activity activity) {
        activity.getWindow().setStatusBarColor(UiTheme.BLACK);
        activity.getWindow().setNavigationBarColor(UiTheme.BLACK);
        final ViewGroup content = activity.findViewById(android.R.id.content);
        content.setBackgroundColor(UiTheme.BLACK);
        final WeakHashMap<View, Boolean> styled = new WeakHashMap<>();
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            styleNew(content, styled);
            for (java.util.Map.Entry<View, WeakReference<TextView>> entry : indicators.entrySet()) {
                TextView label = entry.getValue().get();
                if (label != null && label.getRootView() == content.getRootView())
                    positionIndicator(label, entry.getKey());
            }
        };
        content.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                if (content.getViewTreeObserver().isAlive())
                    content.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                styled.clear();
                content.removeOnAttachStateChangeListener(this);
            }
        });
        styleNew(content, styled);
    }

    /** Called by the pinned profile tab handler on initial selection and tab changes. */
    public static void alignIndicator(TextView label, View indicator) {
        if (indicator == null) return;
        indicators.put(indicator, new WeakReference<>(label));
        indicator.animate().cancel();
        positionIndicator(label, indicator);
    }

    private static void positionIndicator(TextView label, View indicator) {
        android.text.Layout layout = label.getLayout();
        if (layout == null || layout.getLineCount() == 0 || indicator.getWidth() == 0
                || !(indicator.getParent() instanceof View)) return;
        int[] textPosition = new int[2], parentPosition = new int[2];
        label.getLocationOnScreen(textPosition);
        ((View) indicator.getParent()).getLocationOnScreen(parentPosition);
        float left = label.getCompoundPaddingLeft();
        float right = label.getWidth() - label.getCompoundPaddingRight();
        int gravity = Gravity.getAbsoluteGravity(label.getGravity(), label.getLayoutDirection())
                & Gravity.HORIZONTAL_GRAVITY_MASK;
        // Single-line TextView can use a 1,048,576px internal Layout and shift
        // it while drawing. Use its visible content box and horizontal gravity.
        float textWidth = Math.min(right - left, layout.getLineWidth(0));
        float localCenter = gravity == Gravity.RIGHT ? right - textWidth / 2f
                : gravity == Gravity.CENTER_HORIZONTAL ? (left + right) / 2f
                : left + textWidth / 2f;
        float center = textPosition[0] - parentPosition[0] + localCenter;
        // X is the final parent-relative coordinate. translationX would add the
        // FrameLayout's existing 16dp padding a second time.
        float target = center - indicator.getWidth() / 2f;
        if (Math.abs(indicator.getX() - target) > .1f) indicator.setX(target);
    }

    static void styleNew(View view, WeakHashMap<View, Boolean> styled) {
        if (!styled.containsKey(view)) {
            styled.put(view, Boolean.TRUE);
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                if (view.isFocusable() && "ProfileActivity".equals(view.getContext().getClass().getSimpleName())
                        && "粉丝".contentEquals(text.getText())) {
                    text.setVisibility(View.GONE);
                    text.setFocusable(false);
                }
                textColor(text, text.getCurrentTextColor());
                if (text instanceof EditText) ((EditText) text).setHintTextColor(UiTheme.MUTED);
                if (view.isFocusable()) {
                    // The old icon-only back and clear buttons need room for real labels.
                    if ("返回".contentEquals(text.getText()) || "清空".contentEquals(text.getText())) {
                        ViewGroup.LayoutParams params = view.getLayoutParams();
                        int width = ModernMenuHelper.dp(view.getContext(), 72);
                        text.setMinimumWidth(width);
                        text.setGravity(Gravity.CENTER);
                        if (params != null && params.width > 0 && params.width < width) {
                            params.width = width;
                            view.setLayoutParams(params);
                        }
                        text.setTextSize(18);
                    }
                    UiTheme.alignControl(text);
                    ViewGroup.LayoutParams params = text.getLayoutParams();
                    if (params != null && params.height != 0) {
                        params.height = UiTheme.controlHeight(text);
                        if (params instanceof LinearLayout.LayoutParams
                                && text.getParent() instanceof LinearLayout
                                && ((LinearLayout) text.getParent()).getOrientation() == LinearLayout.VERTICAL) {
                            LinearLayout.LayoutParams margins = (LinearLayout.LayoutParams) params;
                            int gap = ModernMenuHelper.dp(text.getContext(), 8);
                            margins.topMargin = Math.max(margins.topMargin, gap);
                            margins.bottomMargin = Math.max(margins.bottomMargin, gap);
                        }
                        text.setLayoutParams(params);
                    }
                }
            }
            if (view.isFocusable()) {
                // Preserve navigation callbacks while applying our final focus treatment.
                // The per-view styled guard keeps this wrapper stable across layouts.
                focusListener(view, view.getOnFocusChangeListener());
                control(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleNew(group.getChildAt(i), styled);
        }
        if (view instanceof LinearLayout && isControlRow((LinearLayout) view))
            alignRow((LinearLayout) view);
    }

    private static boolean directInput(View view) {
        if (!(view instanceof LinearLayout)) return false;
        LinearLayout row = (LinearLayout) view;
        if (row.getOrientation() != LinearLayout.HORIZONTAL) return false;
        for (int i = 0; i < row.getChildCount(); i++)
            if (row.getChildAt(i) instanceof EditText) return true;
        return false;
    }

    static boolean isControlRow(LinearLayout row) {
        if (row.getOrientation() != LinearLayout.HORIZONTAL) return false;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView && child.isFocusable()) return true;
            if (directInput(child)) return true;
            if (child instanceof HorizontalScrollView) {
                HorizontalScrollView scroll = (HorizontalScrollView) child;
                if (scroll.getChildCount() == 1 && scroll.getChildAt(0) instanceof LinearLayout
                        && isControlRow((LinearLayout) scroll.getChildAt(0))) return true;
            }
        }
        return false;
    }

    private static void removeRedundantLabels(LinearLayout row) {
        String page = row.getContext().getClass().getSimpleName();
        boolean input = directInput(row);
        TextView title = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView && "精选".contentEquals(((TextView) child).getText()))
                title = (TextView) child;
        }
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView) || child.isFocusable()) continue;
            String text = ((TextView) child).getText().toString().trim();
            if (page.equals("FeaturedActivity") && title != null && text.equals("浏览发现好内容")) {
                row.removeView(child);
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) title.getLayoutParams();
                params.width = 0;
                params.weight = 1;
                title.setLayoutParams(params);
            } else if (page.equals("SearchActivity") && input && text.equals("搜索")) {
                // The input's necessary hint already identifies the action and supported content.
                row.removeView(child);
            }
        }
    }

    private static void alignRow(LinearLayout row) {
        removeRedundantLabels(row);
        boolean input = directInput(row);
        if (!alignedRows.containsKey(row)) {
            row.setBaselineAligned(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            alignedRows.put(row, Boolean.TRUE);
        }
        int y = input ? 0 : (row.getPaddingTop() != 0 || row.getPaddingBottom() != 0)
                ? ModernMenuHelper.dp(row.getContext(), 8) : 0;
        if (row.getPaddingTop() != y || row.getPaddingBottom() != y)
            row.setPadding(row.getPaddingLeft(), y, row.getPaddingRight(), y);
        int height = ModernMenuHelper.dp(row.getContext(), 40);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                android.graphics.Paint.FontMetrics metrics = text.getPaint().getFontMetrics();
                int required = text.isFocusable() ? UiTheme.controlHeight(text)
                        : (int) Math.ceil(metrics.descent - metrics.ascent);
                height = Math.max(height, required);
            }
            else if (directInput(child)) height = Math.max(height, child.getMinimumHeight());
            else if (child instanceof HorizontalScrollView && ((HorizontalScrollView) child).getChildCount() == 1)
                height = Math.max(height, ((HorizontalScrollView) child).getChildAt(0).getMinimumHeight());
        }
        int gap = ModernMenuHelper.dp(row.getContext(), input ? 8 : 12);
        int visible = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            int left = visible++ == 0 ? 0 : gap;
            boolean changed = params.gravity != Gravity.CENTER_VERTICAL || params.leftMargin != left
                    || params.rightMargin != 0 || params.topMargin != 0 || params.bottomMargin != 0;
            params.gravity = Gravity.CENTER_VERTICAL;
            params.leftMargin = left;
            params.rightMargin = params.topMargin = params.bottomMargin = 0;
            if (child instanceof TextView || directInput(child) || child instanceof HorizontalScrollView) {
                changed |= params.height != height;
                params.height = height;
            }
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                int textGravity = (text.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL;
                if (text.getGravity() != textGravity) text.setGravity(textGravity);
                if (text.getIncludeFontPadding()) text.setIncludeFontPadding(false);
                if (!text.isFocusable()) {
                    if (text.getPaddingTop() != 0 || text.getPaddingBottom() != 0)
                        text.setPadding(text.getPaddingLeft(), 0, text.getPaddingRight(), 0);
                    if (text.getMaxLines() != 1) text.setSingleLine(true);
                }
            }
            if (changed) child.setLayoutParams(params);
        }
        int totalHeight = height + y * 2;
        if (row.getMinimumHeight() != totalHeight) row.setMinimumHeight(totalHeight);
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params != null && params.height > 0 && params.height != totalHeight) {
            params.height = totalHeight;
            row.setLayoutParams(params);
        }
    }

    private static boolean accent(int color) {
        return Color.red(color) > 180 && Color.red(color) > Color.green(color) * 1.25f
                && Color.red(color) > Color.blue(color) * 1.1f;
    }

    public static void textColor(TextView view, int color) {
        int brightness = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
        view.setTextColor(accent(color) ? UiTheme.PINK
                : brightness >= 210 && Color.alpha(color) >= 220 ? UiTheme.WHITE : UiTheme.MUTED);
    }

    public static void backgroundColor(View view, int color) {
        if (view.isFocusable()) control(view);
        else if (view.getClass() == View.class)
            view.setBackgroundColor(accent(color) ? UiTheme.PINK : 0x26ffffff);
        else view.setBackgroundColor(Color.alpha(color) == 0 ? Color.TRANSPARENT : UiTheme.BLACK);
    }

    public static void drawableColor(GradientDrawable drawable, int color) {
        colors.put(drawable, color);
        WeakReference<View> reference = owners.get(drawable);
        View owner = reference == null ? null : reference.get();
        if (owner != null && owner.isFocusable()) {
            // Search applies its selected color AFTER attaching the same drawable.
            if (owner instanceof TextView) owner.setSelected(accent(color));
            control(owner);
            return;
        }
        drawable.setColor(accent(color) ? UiTheme.PINK
                : Color.alpha(color) == 0 ? Color.TRANSPARENT : UiTheme.BLACK);
    }

    public static void drawableShape(GradientDrawable drawable, int shape) {
        ovals.put(drawable, shape == GradientDrawable.OVAL);
        drawable.setShape(shape);
    }

    public static void background(View view, Drawable drawable) {
        // Keep GradientDrawable: the upstream tab handlers cast getBackground() to it.
        if (drawable instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) drawable;
            owners.put(gradient, new WeakReference<>(view));
            if (view.getClass() == View.class && Boolean.TRUE.equals(ovals.get(gradient))) {
                gradient.setColor(Color.TRANSPARENT); // Remove decorative colored header bubbles.
                gradient.setStroke(0, Color.TRANSPARENT);
            } else if (view.getClass() == View.class) {
                Integer color = colors.get(gradient);
                int flat = color != null && accent(color) ? UiTheme.PINK : 0x26ffffff;
                gradient.setColors(new int[] {flat, flat});
                gradient.setStroke(0, Color.TRANSPARENT);
            } else if (view instanceof ViewGroup || view instanceof TextView) {
                Integer color = colors.get(gradient);
                if (view instanceof TextView && color != null) view.setSelected(accent(color));
                gradient.setColors(new int[] {UiTheme.BLACK, UiTheme.BLACK});
                gradient.setStroke(0, Color.TRANSPARENT);
            } else if (view instanceof ImageView) {
                gradient.setColor(0xff181818);
                gradient.setStroke(0, Color.TRANSPARENT);
            }
        }
        view.setBackground(drawable);
        if (view.isFocusable()) control(view);
    }

    public static void focusListener(View view, View.OnFocusChangeListener original) {
        view.setOnFocusChangeListener((target, focused) -> {
            if (original != null) original.onFocusChange(target, focused);
            // Preserve tab selection/navigation while replacing the old zoom-and-glow treatment.
            target.animate().cancel();
            target.setScaleX(1f);
            target.setScaleY(1f);
            target.setElevation(0f);
            control(target);
        });
    }

    public static void setupFocus(View view) {
        // Replacement for AnimHelper setup: never start its independent color animator.
        // Only replace the focus listener; the existing click/navigation action stays intact.
        view.setFocusable(true);
        focusListener(view, null);
        control(view);
    }

    static void control(View view) {
        if (ProfileGrid.usesItemFocus(view)) {
            // GridView must own keyboard focus for DPAD, but its selector already outlines the item.
            // A second control border here would outline the entire viewport at the same time.
            view.setBackgroundColor(UiTheme.BLACK);
            return;
        }
        GradientDrawable background = view.getBackground() instanceof GradientDrawable
                ? (GradientDrawable) view.getBackground() : new GradientDrawable();
        owners.put(background, new WeakReference<>(view));
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColors(new int[] {view.isFocused() ? 0xff202020 : UiTheme.BLACK,
                view.isFocused() ? 0xff202020 : UiTheme.BLACK});
        background.setCornerRadius(ModernMenuHelper.dp(view.getContext(), 10));
        background.setStroke(ModernMenuHelper.dp(view.getContext(), view.isFocused() ? 2 : 1),
                view.isFocused() || view.isSelected() ? UiTheme.PINK : 0x26ffffff);
        view.setBackground(background);
    }
}
