package com.dycomment.tv;

/** Browser capability gate fixture; invoked only by the GitHub Actions interaction activity. */
final class QuickShareSelfTest {
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static void run() throws Exception {
        boolean unavailable = false;
        try {
            // A null Context proves the unavailable capability is rejected before enqueueing a share.
            QuickShareApi.share(null, "123", "456", "");
        } catch (QuickShareApi.UnavailableException expected) {
            unavailable = true;
        }
        require(unavailable, "an unavailable browser share capability must not attempt a write");
    }
}
