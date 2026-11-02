package com.ireaderlauncher.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;

/** 快捷入口偏好与真实蓝牙状态分开保存：恢复入口不会替用户打开蓝牙。 */
final class BluetoothQuickActions {
    static final String PREF = "bluetooth_quick_action_enabled";
    static final String CHANGED = "com.ireaderlauncher.app.BLUETOOTH_QUICK_ACTION_CHANGED";
    static final int UNKNOWN = -1;
    private static volatile int requestedState = UNKNOWN;
    private static volatile String error = "";

    private BluetoothQuickActions() {}

    static BluetoothAdapter adapter(Context c) {
        if (!DeviceProfile.current().bluetoothQuickActions) return null;
        BluetoothManager manager = c.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    static boolean available(Context c) {
        try { return adapter(c) != null; }
        catch (RuntimeException e) { return false; }
    }

    static boolean enabled(Context c) {
        return DeviceProfile.current().bluetoothQuickActions
                && c.getSharedPreferences("desktop", Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static boolean setEnabled(Context c, boolean enabled) {
        if (enabled && !available(c)) { error = "此设备的蓝牙暂不可用"; return false; }
        if (!c.getSharedPreferences("desktop", Context.MODE_PRIVATE).edit().putBoolean(PREF, enabled).commit()) {
            error = "无法保存蓝牙快捷功能设置，请重试"; return false;
        }
        error = "";
        if (enabled && !ensureRunning(c)) {
            c.getSharedPreferences("desktop", Context.MODE_PRIVATE).edit().putBoolean(PREF, false).commit();
            return false;
        }
        if (!enabled) {
            c.stopService(new Intent(c, BluetoothQuickActionService.class));
            c.getSystemService(NotificationManager.class).cancel(BluetoothQuickActionService.NOTIFICATION_ID);
            requestedState = UNKNOWN;
        }
        changed(c);
        return true;
    }

    static boolean ensureRunning(Context c) {
        if (!enabled(c)) return true;
        return start(c, BluetoothQuickActionService.REFRESH);
    }

    static void requestState(Context c, boolean turnOn) {
        if (!enabled(c) || busy(c)) return;
        if (!start(c, turnOn ? BluetoothQuickActionService.ENABLE : BluetoothQuickActionService.DISABLE)) changed(c);
    }

    private static boolean start(Context c, String action) {
        try {
            c.startForegroundService(new Intent(c, BluetoothQuickActionService.class).setAction(action));
            return true;
        } catch (RuntimeException e) {
            error = "无法启动蓝牙快捷通知，请重新启用此功能"; return false;
        }
    }

    static int state(Context c) {
        try { BluetoothAdapter adapter = adapter(c); return adapter == null ? UNKNOWN : adapter.getState(); }
        catch (RuntimeException e) { return UNKNOWN; }
    }

    static boolean busy(Context c) {
        int state = state(c);
        return requestedState != UNKNOWN || state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF;
    }

    static String status(Context c) {
        if (requestedState == BluetoothAdapter.STATE_ON) return "正在开启…";
        if (requestedState == BluetoothAdapter.STATE_OFF) return "正在关闭…";
        return stateText(state(c));
    }

    static String stateText(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON: return "已开启";
            case BluetoothAdapter.STATE_OFF: return "已关闭";
            case BluetoothAdapter.STATE_TURNING_ON: return "正在开启…";
            case BluetoothAdapter.STATE_TURNING_OFF: return "正在关闭…";
            default: return "状态不可用";
        }
    }

    static String error() { return error; }
    static int requestedState() { return requestedState; }
    static void pending(int target) { requestedState = target; error = ""; }
    static void finished(String message) { requestedState = UNKNOWN; error = message; }
    static void changed(Context c) { c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName())); }

    static boolean notificationsAllowed(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(BluetoothQuickActionService.CHANNEL_ID);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
}
