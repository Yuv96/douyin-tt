package com.dycomment.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Content-sized metadata with a ceiling, never a fixed-width strip over the video. */
public final class VideoInfoLayout extends LinearLayout {
    public VideoInfoLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        int available = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            // Equal side margins make half of the available span end at the screen midpoint.
            // This includes both the avatar and the text card, even in narrow windows.
            int ceiling = available / 2;
            ceiling = Math.min(ceiling, Math.round(720 * density));
            widthSpec = MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST);
        }
        // A stable two-line card height also fixes the circular avatar's square bounds.
        // System font scaling changes both together, never the current video's text length.
        int height =
                Math.round(
                        88 * density * Math.max(1f, getResources().getConfiguration().fontScale));
        if (getChildCount() > 0 && getChildAt(0) instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) getChildAt(0);
            if (row.getChildCount() >= 2) {
                View avatar = row.getChildAt(0), card = row.getChildAt(1);
                ViewGroup.LayoutParams avatarParams = avatar.getLayoutParams();
                ViewGroup.LayoutParams cardParams = card.getLayoutParams();
                if (avatarParams.width != height
                        || avatarParams.height != height
                        || cardParams.height != height) {
                    avatarParams.width = avatarParams.height = cardParams.height = height;
                    avatar.forceLayout();
                    card.forceLayout();
                    row.forceLayout();
                }
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
