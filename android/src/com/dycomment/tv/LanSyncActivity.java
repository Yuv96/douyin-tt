package com.dycomment.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Closing this page does not stop an enabled sync service. */
public final class LanSyncActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView address, code, state, toggle, allow, reject;
    private final Object pairingWindow = new Object();
    private String shownPairing = "";
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = UiTheme.page(this, "电脑账号同步");
        address = UiTheme.text(this, "", 18); page.addView(address);
        code = UiTheme.text(this, "等待电脑配对", 28); code.setTypeface(Typeface.MONOSPACE); page.addView(code);
        allow = UiTheme.button(this, "数字相同，允许", () -> {
            LanSyncService.decidePairing(pairingWindow, shownPairing, true); refresh();
        });
        reject = UiTheme.button(this, "拒绝", () -> {
            LanSyncService.decidePairing(pairingWindow, shownPairing, false); refresh();
        });
        addButton(page, allow); addButton(page, reject);
        allow.setVisibility(android.view.View.GONE); reject.setVisibility(android.view.View.GONE);
        state = UiTheme.text(this, "", 17); page.addView(state);
        toggle = UiTheme.button(this, "", () -> {
            try { LanSyncService.setEnabled(this, !LanSyncService.enabled(this)); refresh(); }
            catch (RuntimeException failure) { state.setText("同步设置未保存，请重试"); }
        });
        addButton(page, toggle);
        addButton(page, UiTheme.button(this, "撤销已配对电脑", () -> {
            new android.app.AlertDialog.Builder(this).setMessage("撤销后，电脑需要重新配对。电视现有账号会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("重置", (dialog, which) -> {
                        try { LanSyncService.resetPairing(this); refresh(); }
                        catch (RuntimeException failure) { state.setText("配对未能撤销，请重试"); }
                    }).show();
        }));
        addButton(page, UiTheme.button(this, "返回", () -> finish()));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        setContentView(scroll); toggle.requestFocus();
        LanSyncService.startIfEnabled(this);
    }
    private void addButton(LinearLayout page, TextView button) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = ModernMenuHelper.dp(this, 8);
        page.addView(button, layout);
    }
    private void refresh() {
        List<String> addresses = LanCredentialServer.addresses();
        setTextIfChanged(address, addresses.isEmpty() ? "未发现局域网 IPv4 地址" : "电视地址：http://" + addresses.get(0) + ":18765");
        LanPairingSession.View pairing = LanSyncService.pairingView(pairingWindow);
        boolean pending = pairing != null && "pending".equals(pairing.status);
        boolean newlyShown = pending && !pairing.id.equals(shownPairing);
        shownPairing = pending ? pairing.id : "";
        setTextIfChanged(code, pending ? "核对数字：" + pairing.sas + "\n剩余 " + pairing.seconds + " 秒"
                : pairing == null ? "等待电脑配对"
                : "approved".equals(pairing.status) ? "已配对"
                : "rejected".equals(pairing.status) ? "已拒绝配对"
                : "expired".equals(pairing.status) ? "配对已过期" : "等待电脑配对");
        allow.setVisibility(pending ? android.view.View.VISIBLE : android.view.View.GONE);
        reject.setVisibility(pending ? android.view.View.VISIBLE : android.view.View.GONE);
        // A newly arrived request never leaves focus on the approval action.
        if (newlyShown) reject.requestFocus();
        setTextIfChanged(state, LanSyncService.status(this));
        setTextIfChanged(toggle, LanSyncService.enabled(this) ? "关闭电脑同步" : "开启电脑同步");
    }
    private static void setTextIfChanged(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    private final Runnable update = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (RuntimeException failure) { setTextIfChanged(state, "配对设置暂不可用"); }
            main.postDelayed(this, 500);
        }
    };
    @Override protected void onResume() {
        super.onResume(); LanSyncService.openPairingWindow(pairingWindow); main.post(update);
    }
    @Override protected void onPause() {
        LanSyncService.closePairingWindow(pairingWindow); main.removeCallbacksAndMessages(null); super.onPause();
    }
}
