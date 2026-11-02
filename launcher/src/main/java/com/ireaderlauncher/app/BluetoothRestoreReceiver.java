package com.ireaderlauncher.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 仅恢复用户启用的通知入口，不恢复或强制更改蓝牙电源状态。 */
public final class BluetoothRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
            BluetoothQuickActions.ensureRunning(context);
    }
}
