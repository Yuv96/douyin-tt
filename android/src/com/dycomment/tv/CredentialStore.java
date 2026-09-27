package com.dycomment.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/** One auth transaction; readers needing cookie/token together take a Snapshot. */
final class CredentialStore {
    private static final String[] AUTH_KEYS = {"cookie", "ms_token", "ms_token_time", "a_bogus", "credential_generation"};

    static final class Snapshot {
        final String cookie, msToken, aBogus;
        final long generation;
        Snapshot(String cookie, String msToken, String aBogus, long generation) {
            this.cookie = cookie; this.msToken = msToken; this.aBogus = aBogus; this.generation = generation;
        }
    }

    static String value(String cookie, String name) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            int at = part.indexOf('=');
            if (at > 0 && name.equals(part.substring(0, at).trim())) return part.substring(at + 1).trim();
        }
        return "";
    }

    static boolean hasSession(String cookie) {
        return !value(cookie, "sessionid").isEmpty() || !value(cookie, "sessionid_ss").isEmpty();
    }

    static synchronized Snapshot snapshot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("dy_config", 0);
        String cookie = SocialApi.cookie();
        String token = value(cookie, "msToken");
        if (token.isEmpty()) token = prefs.getString("ms_token", "");
        return new Snapshot(cookie, token, prefs.getString("a_bogus", ""),
                prefs.getLong("credential_generation", 0));
    }

    private static void restore(SharedPreferences prefs, Map<String, Object> before) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : AUTH_KEYS) {
            Object old = before.get(key);
            if (old instanceof String) editor.putString(key, (String) old);
            else if (old instanceof Long) editor.putLong(key, (Long) old);
            else editor.remove(key);
        }
        // commit() updates the in-memory map even on an I/O failure. This restores both the
        // old map and, whenever storage is writable, its disk representation before unlocking.
        editor.commit();
    }

    static synchronized void save(Context context, String cookie) throws Exception {
        if (!hasSession(cookie)) throw new Exception("尚未取得登录凭证");
        Field field = Class.forName("com.dycomment.tv.DouyinApi").getDeclaredField("cookie");
        if (field.getType() != String.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                || java.lang.reflect.Modifier.isFinal(field.getModifiers()))
            throw new Exception("账号存储不兼容");
        field.setAccessible(true);
        Object nativeBefore = field.get(null);
        field.set(null, nativeBefore); // Resolve and prove writability before changing preferences.
        SharedPreferences prefs = context.getSharedPreferences("dy_config", 0);
        Map<String, Object> before = new HashMap<>();
        Map<String, ?> all = prefs.getAll();
        for (String key : AUTH_KEYS) if (all.containsKey(key)) before.put(key, all.get(key));
        String token = value(cookie, "msToken");
        SharedPreferences.Editor editor = prefs.edit().putString("cookie", cookie)
                .remove("a_bogus").remove("ms_token").remove("ms_token_time")
                .putLong("credential_generation", prefs.getLong("credential_generation", 0) + 1);
        if (!token.isEmpty()) editor.putString("ms_token", token)
                .putLong("ms_token_time", System.currentTimeMillis());
        if (!editor.commit()) {
            restore(prefs, before);
            throw new Exception("账号保存失败，原账号已保留");
        }
        try {
            // cleanup.py makes this upstream field volatile. Publish only the committed pair.
            field.set(null, cookie);
        } catch (Exception failed) {
            restore(prefs, before);
            field.set(null, nativeBefore);
            throw failed;
        }
        CredentialHealth.reset();
    }
}
