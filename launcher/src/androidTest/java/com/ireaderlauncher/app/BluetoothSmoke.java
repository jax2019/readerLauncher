package com.ireaderlauncher.app;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;

/** 仅改动蓝牙及本功能偏好，finally 精确恢复测试前状态。 */
final class BluetoothSmoke {
    private final Instrumentation instrumentation;
    private final Context context;
    BluetoothSmoke(Instrumentation instrumentation) {
        this.instrumentation = instrumentation; context = instrumentation.getTargetContext();
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void waitFor(BooleanSupplier ready, String message) {
        long until = SystemClock.elapsedRealtime() + 20000;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        check(ready.getAsBoolean(), message);
    }
    private void progress(String text) { Bundle b = new Bundle(); b.putString("step", text); instrumentation.sendStatus(0, b); }
    private Notification notification() {
        for (StatusBarNotification n : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (n.getId() == BluetoothQuickActionService.NOTIFICATION_ID) return n.getNotification();
        return null;
    }
    private boolean notificationState(boolean on) {
        Notification n = notification();
        return n != null && ("蓝牙：" + (on ? "已开启" : "已关闭")).equals(n.extras.getString(Notification.EXTRA_TITLE));
    }
    private void preference(boolean enabled) {
        main(() -> check(BluetoothQuickActions.setEnabled(context, enabled), "快捷功能设置失败"));
        waitFor(() -> (notification() != null) == enabled, "通知显示状态不正确");
    }

    @SuppressWarnings("deprecation")
    void run() {
        check(DeviceProfile.current().bluetoothQuickActions && BluetoothQuickActions.available(context), "当前设备不支持多看蓝牙测试");
        check(BluetoothQuickActions.notificationsAllowed(context), "请先允许蓝牙快捷通知再运行测试");
        waitFor(() -> BluetoothQuickActions.state(context) == BluetoothAdapter.STATE_ON
                || BluetoothQuickActions.state(context) == BluetoothAdapter.STATE_OFF, "测试前蓝牙状态不稳定");
        boolean enabledBefore = BluetoothQuickActions.enabled(context);
        int stateBefore = BluetoothQuickActions.state(context);
        try {
            preference(false);
            main(() -> new BluetoothRestoreReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED)));
            SystemClock.sleep(500);
            check(notification() == null, "未启用时开机回调意外显示通知");
            check(BluetoothQuickActions.state(context) == stateBefore, "关闭快捷功能改变了蓝牙");
            preference(true);
            check(BluetoothQuickActions.state(context) == stateBefore, "开启快捷功能改变了蓝牙");
            progress("偏好开关与通知显示通过");

            // 与首页共用同一请求入口，连续请求同一目标不能反向切换。
            for (boolean on : new boolean[]{false, true}) {
                main(() -> { BluetoothQuickActions.requestState(context, on); BluetoothQuickActions.requestState(context, on); });
                waitFor(() -> BluetoothQuickActions.state(context) == (on ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF)
                        && !BluetoothQuickActions.busy(context) && notificationState(on), "首页请求与通知状态不同步");
            }
            progress("开启、关闭及重复请求通过");

            // 在其他页面也可操作真实通知的 PendingIntent，而不是绕过通知直接调用控制方法。
            main(() -> context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage("com.android.settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            main(() -> {
                Notification n = notification();
                check(n != null && n.contentView != null, "缺少通知布局");
                View content = n.contentView.apply(context, new FrameLayout(context));
                View toggle = content.findViewById(R.id.bluetooth_toggle);
                check(toggle != null && toggle.isEnabled(), "通知开关不可点击");
                toggle.performClick(); toggle.performClick();
            });
            waitFor(() -> BluetoothQuickActions.state(context) == BluetoothAdapter.STATE_OFF && notificationState(false)
                    && !BluetoothQuickActions.busy(context), "离开桌面后通知无法关闭蓝牙");
            progress("后台通知操作及重复点击通过");

            main(() -> check(BluetoothQuickActions.adapter(context).enable(), "外部开启蓝牙失败"));
            waitFor(() -> notificationState(true), "系统外部切换未同步到通知");
            progress("外部状态广播同步通过");

            main(() -> context.stopService(new Intent(context, BluetoothQuickActionService.class)));
            waitFor(() -> notification() == null, "测试前无法停止通知服务");
            main(() -> new BluetoothRestoreReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED)));
            waitFor(() -> notificationState(true), "开机回调未按偏好恢复通知");
            check(BluetoothQuickActions.state(context) == BluetoothAdapter.STATE_ON, "恢复通知改变了蓝牙状态");
            main(() -> context.stopService(new Intent(context, BluetoothQuickActionService.class)));
            waitFor(() -> notification() == null, "升级恢复测试前无法停止服务");
            main(() -> new BluetoothRestoreReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED)));
            waitFor(() -> notificationState(true), "升级回调未恢复通知");
            progress("开机与升级恢复入口通过");

            preference(false);
            main(() -> context.startService(new Intent(context, BluetoothQuickActionService.class).setAction(BluetoothQuickActionService.DISABLE)));
            SystemClock.sleep(500);
            check(notification() == null && BluetoothQuickActions.state(context) == BluetoothAdapter.STATE_ON,
                    "关闭功能后旧请求仍改变蓝牙或显示通知");
        } finally {
            main(() -> {
                BluetoothAdapter adapter = BluetoothQuickActions.adapter(context);
                if (stateBefore == BluetoothAdapter.STATE_ON) adapter.enable(); else adapter.disable();
            });
            waitFor(() -> BluetoothQuickActions.state(context) == stateBefore, "测试前蓝牙状态未恢复");
            preference(enabledBefore);
            main(() -> context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            check(BluetoothQuickActions.enabled(context) == enabledBefore, "测试前快捷功能偏好未恢复");
        }
    }
}
