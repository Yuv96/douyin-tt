package com.dycomment.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.security.SecureRandom;
import java.util.Map;

/** Explicitly enabled foreground listener; independent of settings Activity/request lifetimes. */
public class LanSyncService extends Service {
    static final String PREFS = "lan_sync", RESET = "com.dycomment.tv.LAN_SYNC_RESET";
    private static final String CHANNEL = "lan-session-sync";
    private static final int NOTIFICATION = 18765;
    private static volatile String status = "等待启动";
    private static LanSyncService activeService;
    private static Object pairingWindowOwner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LanCredentialServer server;
    private int generation;

    static SharedPreferences settings(Context context) { return context.getSharedPreferences(PREFS, 0); }
    static boolean enabled(Context context) { return settings(context).getBoolean("enabled", false); }

    static String hex(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }
    static byte[] unhex(String value) {
        if (!value.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Pairing data invalid");
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    private static String randomHex() { byte[] value = new byte[16]; new SecureRandom().nextBytes(value); return hex(value); }
    private static void commitSettings(SharedPreferences prefs, SharedPreferences.Editor editor, String message) {
        Map<String, ?> previous = prefs.getAll();
        if (editor.commit()) return;
        // A failed disk write can still change SharedPreferences' in-memory values.
        SharedPreferences.Editor rollback = prefs.edit();
        for (String name : new String[] {"enabled", "pair_key", "receiver_id"}) {
            Object value = previous.get(name);
            if (value instanceof String) rollback.putString(name, (String) value);
            else if (value instanceof Boolean) rollback.putBoolean(name, (Boolean) value);
            else rollback.remove(name);
        }
        rollback.commit();
        throw new IllegalStateException(message);
    }
    static synchronized void ensurePairing(Context context) {
        SharedPreferences prefs = settings(context);
        String key = prefs.getString("pair_key", ""), id = prefs.getString("receiver_id", "");
        if (key.matches("[a-f0-9]{32}") && id.matches("[a-f0-9]{32}")) return;
        commitSettings(prefs, prefs.edit().putString("pair_key", randomHex()).putString("receiver_id", randomHex()),
                "无法保存配对设置");
    }
    static String pairingKey(Context context) { ensurePairing(context); return settings(context).getString("pair_key", ""); }
    static String receiverId(Context context) { ensurePairing(context); return settings(context).getString("receiver_id", ""); }
    static String status(Context context) { return enabled(context) ? status : "同步已关闭"; }

    // Only the resumed settings page holds this in-process capability. No HTTP route can approve.
    static synchronized void openPairingWindow(Object owner) {
        if (pairingWindowOwner == owner) return;
        pairingWindowOwner = owner;
        if (activeService != null && activeService.server != null) activeService.server.pairing.openWindow();
    }
    static synchronized void closePairingWindow(Object owner) {
        if (pairingWindowOwner != owner) return;
        pairingWindowOwner = null;
        if (activeService != null && activeService.server != null) activeService.server.pairing.closeWindow();
    }
    static synchronized LanPairingSession.View pairingView(Object owner) {
        return pairingWindowOwner == owner && activeService != null && activeService.server != null
                ? activeService.server.pairing.view() : null;
    }
    static synchronized void decidePairing(Object owner, String id, boolean allow) {
        if (pairingWindowOwner == owner && activeService != null && activeService.server != null)
            activeService.server.pairing.decide(id, allow);
    }

    public static void startIfEnabled(Context context) {
        if (!enabled(context)) return;
        Intent intent = new Intent(context, LanSyncService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException unavailable) { status = "同步服务未能启动，请打开同步设置重试"; }
    }
    static synchronized void setEnabled(Context context, boolean enabled) {
        if (enabled) ensurePairing(context);
        SharedPreferences prefs = settings(context);
        commitSettings(prefs, prefs.edit().putBoolean("enabled", enabled), "无法保存开关");
        if (enabled) startIfEnabled(context);
        else context.stopService(new Intent(context, LanSyncService.class));
    }
    static synchronized void resetPairing(Context context) {
        ensurePairing(context);
        SharedPreferences prefs = settings(context);
        commitSettings(prefs, prefs.edit().putString("pair_key", randomHex()), "无法重置配对");
        if (enabled(context)) {
            Intent intent = new Intent(context, LanSyncService.class).setAction(RESET);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
        }
    }

    LanCredentialServer.Validator validator() {
        return cookie -> SocialApi.validateAccountAt("https://www.douyin.com", cookie);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        notification();
        try { ensurePairing(this); }
        catch (RuntimeException unavailable) {
            status = "配对设置暂不可用，请重试";
            closeServer(); stopSelf(); return START_NOT_STICKY;
        }
        if (intent != null && RESET.equals(intent.getAction())) closeServer();
        if (server == null) {
            final int token = ++generation;
            server = new LanCredentialServer(this, receiverId(this), unhex(pairingKey(this)), validator(), value ->
                main.post(() -> { if (token == generation) { status = value; notification(); } }));
            synchronized (LanSyncService.class) {
                activeService = this;
                if (pairingWindowOwner != null) server.pairing.openWindow();
            }
            try { server.start(); status = "已开启，等待电脑同步"; notification(); }
            catch (java.io.IOException failure) {
                status = failure instanceof java.net.BindException
                        ? "端口 18765 已被占用，同步未启动" : "同步监听暂不可用";
                closeServer(); stopSelf();
            }
        }
        return START_STICKY;
    }

    private void notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "电脑账号同步", NotificationManager.IMPORTANCE_LOW));
        Intent page = new Intent(this, LanSyncActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("电脑账号同步已开启").setContentText(status)
                .setContentIntent(PendingIntent.getActivity(this, 0, page, flags))
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(CHANNEL);
        startForeground(NOTIFICATION, builder.build());
    }

    private void closeServer() {
        generation++;
        synchronized (LanSyncService.class) {
            if (server != null) server.close();
            server = null;
            if (activeService == this) activeService = null;
        }
    }
    @Override public void onDestroy() {
        closeServer(); main.removeCallbacksAndMessages(null); stopForeground(true); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
