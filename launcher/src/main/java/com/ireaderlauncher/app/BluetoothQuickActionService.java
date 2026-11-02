package com.ireaderlauncher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.widget.RemoteViews;
import android.widget.Toast;

/** 用户主动启用后才运行；广播驱动通知刷新，不轮询、不持有唤醒锁。 */
public final class BluetoothQuickActionService extends Service {
    static final String CHANNEL_ID = "bluetooth_quick_actions";
    static final int NOTIFICATION_ID = 1002;
    static final String REFRESH = "com.ireaderlauncher.app.bluetooth.REFRESH";
    static final String ENABLE = "com.ireaderlauncher.app.bluetooth.ENABLE";
    static final String DISABLE = "com.ireaderlauncher.app.bluetooth.DISABLE";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listening;
    private final Runnable timeout = () -> {
        int target = BluetoothQuickActions.requestedState();
        if (target == BluetoothQuickActions.UNKNOWN) return;
        finishRequest(BluetoothQuickActions.state(this) == target ? "" : "蓝牙切换超时，请重试或打开蓝牙设置");
    };
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int state = BluetoothQuickActions.state(context);
            int target = BluetoothQuickActions.requestedState();
            if (target != BluetoothQuickActions.UNKNOWN
                    && (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF)) {
                finishRequest(state == target ? "" : "蓝牙未能切换，请重试或打开蓝牙设置");
            } else {
                if (target == BluetoothQuickActions.UNKNOWN) BluetoothQuickActions.finished("");
                publish();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (!DeviceProfile.current().bluetoothQuickActions) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "蓝牙快捷开关", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("在下拉通知中显示蓝牙状态与开关；可在桌面设置中关闭此功能");
        channel.setSound(null, null); channel.enableVibration(false); channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        registerReceiver(stateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        listening = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 包括系统重建服务和旧通知点击在内，每次入口都重新核验偏好与机型。
        if (!BluetoothQuickActions.enabled(this) || !BluetoothQuickActions.available(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        String action = intent == null ? REFRESH : intent.getAction();
        if (ENABLE.equals(action)) request(true);
        else if (DISABLE.equals(action)) request(false);
        else publish();
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void request(boolean turnOn) {
        if (BluetoothQuickActions.busy(this)) { publish(); return; }
        int target = turnOn ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
        if (BluetoothQuickActions.state(this) == target) { finishRequest(""); return; }
        BluetoothQuickActions.pending(target);
        publish();
        try {
            BluetoothAdapter adapter = BluetoothQuickActions.adapter(this);
            boolean accepted = adapter != null && (turnOn ? adapter.enable() : adapter.disable());
            if (!accepted) { finishRequest("系统未接受蓝牙切换，请重试或打开蓝牙设置"); return; }
            // 单次超时只负责核验本次操作；真实完成依靠系统广播，不做周期查询。
            main.postDelayed(timeout, 15000);
        } catch (RuntimeException e) { finishRequest("蓝牙控制失败，请检查系统设置后重试"); }
    }

    private void finishRequest(String error) {
        main.removeCallbacks(timeout);
        BluetoothQuickActions.finished(error);
        publish();
        if (!error.isEmpty()) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private void publish() {
        if (!BluetoothQuickActions.enabled(this)) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        BluetoothQuickActions.changed(this);
    }

    private Notification notification() {
        int state = BluetoothQuickActions.state(this);
        boolean turnOn = state != BluetoothAdapter.STATE_ON;
        boolean busy = BluetoothQuickActions.busy(this);
        String status = "蓝牙：" + BluetoothQuickActions.status(this);
        String error = BluetoothQuickActions.error();
        PendingIntent toggle = PendingIntent.getForegroundService(this, turnOn ? 1 : 2,
                new Intent(this, BluetoothQuickActionService.class).setAction(turnOn ? ENABLE : DISABLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent settings = PendingIntent.getActivity(this, 3,
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage("com.android.settings"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent desktop = PendingIntent.getActivity(this, 4,
                new Intent(this, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        // 折叠通知中也展示按钮，避免必须展开通知后才能操作；显式开启/关闭防止旧通知反向切换。
        RemoteViews content = new RemoteViews(getPackageName(), R.layout.notification_bluetooth);
        content.setTextViewText(R.id.bluetooth_status, status + (error.isEmpty() ? "" : "\n" + error));
        content.setTextViewText(R.id.bluetooth_toggle, busy ? "切换中" : turnOn ? "开启" : "关闭");
        content.setTextColor(R.id.bluetooth_toggle, busy ? Ui.MUTED : Ui.INK);
        content.setBoolean(R.id.bluetooth_toggle, "setEnabled", !busy && state != BluetoothQuickActions.UNKNOWN);
        content.setContentDescription(R.id.bluetooth_toggle, busy ? "蓝牙正在切换" : turnOn ? "开启蓝牙" : "关闭蓝牙");
        content.setOnClickPendingIntent(R.id.bluetooth_toggle, toggle);
        content.setOnClickPendingIntent(R.id.bluetooth_settings, settings);
        return new Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_bluetooth)
                .setContentTitle(status).setContentText(error.isEmpty() ? "蓝牙快捷开关" : error)
                .setContentIntent(desktop).setCustomContentView(content).setCustomBigContentView(content)
                .setStyle(new Notification.DecoratedCustomViewStyle()).setOngoing(true).setOnlyAlertOnce(true)
                .setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (listening) unregisterReceiver(stateReceiver);
        BluetoothQuickActions.finished("");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
