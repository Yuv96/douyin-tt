package com.dycomment.tv;

import android.content.Context;

/** Legacy danmaku ABI; credentials belong to this television's account only. */
public final class MsTokenHelper {
    public interface TokenCallback {
        void onToken(TokenPair token);

        void onFailed(String reason);
    }

    public interface MsTokenCallback {
        void onToken(String token);

        void onFailed(String reason);
    }

    public static final class TokenPair {
        public final String msToken, aBogus;

        public TokenPair(String token, String signature) {
            msToken = token;
            aBogus = signature;
        }

        public boolean isValid() {
            return !msToken.isEmpty();
        }
    }

    public static void getTokens(Context c, TokenCallback callback) {
        CredentialStore.Snapshot snapshot = CredentialStore.snapshot(c);
        callback.onToken(new TokenPair(snapshot.msToken, snapshot.aBogus));
    }

    public static void getToken(Context c, MsTokenCallback callback) {
        getTokens(
                c,
                new TokenCallback() {
                    public void onToken(TokenPair pair) {
                        callback.onToken(pair.msToken);
                    }

                    public void onFailed(String reason) {
                        callback.onFailed(reason);
                    }
                });
    }

    public static void setTokens(Context c, String token, String signature) {
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("dy_config", 0).edit();
        if (token != null) e.putString("ms_token", token);
        if (signature != null) e.putString("a_bogus", signature);
        e.putLong("ms_token_time", System.currentTimeMillis()).apply();
    }

    public static void setToken(Context c, String token) {
        setTokens(c, token, null);
    }

    public static void resetTokens(Context c) {
        c.getSharedPreferences("dy_config", 0)
                .edit()
                .remove("ms_token")
                .remove("a_bogus")
                .remove("ms_token_time")
                .apply();
    }

    public static void resetToken(Context c) {
        resetTokens(c);
    }
}
