package com.dycomment.tv;

/** Opaque secondary Activity forces Android to destroy the underlying video surface. */
public final class SurfaceCoverTestActivity extends android.app.Activity {
    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        android.widget.TextView cover = new android.widget.TextView(this);
        cover.setBackgroundColor(0xff102030);
        cover.setText("播放页离开 / 返回回归测试");
        setContentView(cover);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> finish(), 1800);
    }
}
