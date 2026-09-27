package com.dycomment.tv;

import android.content.Context;

/**
 * Binary compatibility only: LibVLC reads the origin directly, so no local server or thread pool is
 * needed.
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
