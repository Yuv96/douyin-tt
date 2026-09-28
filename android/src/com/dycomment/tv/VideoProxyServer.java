package com.dycomment.tv;

import android.content.Context;

/**
 * Legacy binary compatibility only. PlayerView owns the bounded range source;
 * the former proxy is never started by the original UI.
 */
public final class VideoProxyServer {
    public void start(Context context) {}

    public void stop() {}

    public boolean isReady() {
        return false;
    }

    public int getPort() {
        return 0;
    }

    public String toProxyUrl(String url) {
        return url;
    }
}
