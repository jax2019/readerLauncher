package com.ireaderlauncher.app;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.screen(this);
        Ui.title(this, root, "桌面设置", "桌面、设备与开发者入口");
        DeviceProfile profile = DeviceProfile.current();
        if (profile.bluetoothQuickActions) addBluetoothPreference(root);
        List<Button> entries = new ArrayList<>();
        entries.add(entry("设置默认桌面", this::chooseHome));
        entries.add(entry("管理桌面应用", () -> Ui.launch(this, new Intent(this, AppsActivity.class))));
        if (profile.hasPowerAssistant())
            entries.add(entry("省电助手", () -> Ui.launch(this, new Intent(this, PowerActivity.class))));
        if (profile.hasScreenLock()) entries.add(entry("锁屏授权管理", () -> ScreenLockActivity.manage(this)));
        if (profile.settings != null)
            entries.add(entry(AppCatalog.displayName(profile.settings.getPackageName(), "阅读器系统设置"),
                    () -> Ui.launch(this, new Intent().setComponent(profile.settings))));
        entries.add(entry("Android 系统设置", () -> Ui.launch(this, new Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"))));
        entries.add(entry("开发者选项", () -> Ui.launch(this, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage("com.android.settings"))));
        if (profile.powerControls) {
            entries.add(entry("阅读灯", () -> Ui.launch(this, new Intent("com.szzy.ireader.ink.devicesetting.READING_LIGHT").setPackage("com.szzy.ireader.settings"))));
            entries.add(entry("应用显示与刷新", () -> Ui.launch(this, new Intent("com.szzy.ireader.ink.devicesetting.APP_DISPLAY").setPackage("com.szzy.ireader.settings"))));
        }
        if (profile.officialHome != null) entries.add(entry("官方桌面", () -> Ui.officialHome(this)));
        Ui.grid(this, root, entries, 2, 96);
    }
    private void addBluetoothPreference(LinearLayout root) {
        LinearLayout panel = Ui.column(this);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        panel.setBackground(Ui.background(this, 0xffffffff, Ui.LINE));
        Switch toggle = new Switch(this); toggle.setText("启用蓝牙快捷开关"); toggle.setTextSize(18);
        toggle.setTextColor(Ui.INK); toggle.setShowText(false); toggle.setSwitchPadding(Ui.dp(this, 12));
        toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.INK));
        toggle.setTrackTintList(android.content.res.ColorStateList.valueOf(Ui.LINE));
        toggle.setContentDescription("启用蓝牙快捷开关");
        toggle.setChecked(BluetoothQuickActions.enabled(this)); toggle.setEnabled(BluetoothQuickActions.available(this));
        panel.addView(toggle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));
        TextView description = Ui.text(this, BluetoothQuickActions.available(this)
                ? bluetoothDescription(toggle.isChecked())
                : "此设备的蓝牙暂不可用。", 14, false);
        description.setTextColor(Ui.MUTED); panel.addView(description); Ui.space(this, panel, 12);
        panel.addView(Ui.button(this, "蓝牙通知设置", false, () -> {
            // 首次启用前尚未创建通知渠道，此时打开应用通知设置，避免空入口。
            boolean channelExists = getSystemService(android.app.NotificationManager.class)
                    .getNotificationChannel(BluetoothQuickActionService.CHANNEL_ID) != null;
            Intent intent = new Intent(channelExists ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (channelExists) intent.putExtra(Settings.EXTRA_CHANNEL_ID, BluetoothQuickActionService.CHANNEL_ID);
            Ui.launch(this, intent);
        }));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            // 用偏好真实值判断回调，程序回退勾选状态时不会再次执行开关。
            if (checked == BluetoothQuickActions.enabled(this)) return;
            if (!BluetoothQuickActions.setEnabled(this, checked)) {
                toggle.setChecked(BluetoothQuickActions.enabled(this));
                Toast.makeText(this, BluetoothQuickActions.error(), Toast.LENGTH_LONG).show();
            } else if (checked && !BluetoothQuickActions.notificationsAllowed(this)) {
                Toast.makeText(this, "系统已关闭此通知，请在蓝牙通知设置中允许显示", Toast.LENGTH_LONG).show();
            }
            description.setText(bluetoothDescription(BluetoothQuickActions.enabled(this)));
        });
        root.addView(panel); Ui.space(this, root, 22);
    }
    private String bluetoothDescription(boolean enabled) {
        return (enabled ? "已启用。" : "未启用。") + "同时在首页和下拉通知中显示蓝牙开关。重启后恢复入口，蓝牙开关跟随系统。";
    }
    private Button entry(String label, Runnable action) {
        Button button = Ui.button(this, label, false, action); button.setTextSize(19); return button;
    }
    private void chooseHome() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            RoleManager manager = getSystemService(RoleManager.class);
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME) && !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                Ui.launch(this, manager.createRequestRoleIntent(RoleManager.ROLE_HOME)); return;
            }
        }
        // 多看的 HOME 设置由权限控制器提供，交给系统解析而不是限定设置包。
        Ui.launch(this, new Intent(Settings.ACTION_HOME_SETTINGS));
    }
}
