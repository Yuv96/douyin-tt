package com.dycomment.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit adb confirmation bridge; class and manifest entry exist only in SELF_TEST builds. */
public final class LanPairingConfirmationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        setResultCode("com.dycomment.tv.TEST_BROWSER_ACTION".equals(intent.getAction())
                ? LanSyncSelfTestActivity.triggerExternalBrowserAction(intent)
                : LanSyncSelfTestActivity.confirmExternalPairing(intent));
    }
}
