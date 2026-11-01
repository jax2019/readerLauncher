package com.ireaderlauncher.app;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Button;
import java.util.ArrayList;
import java.util.List;

public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.screen(this);
        Ui.title(this, root, "设置", "桌面、设备与开发者入口");
        List<Button> entries = new ArrayList<>();
        entries.add(entry("设置默认桌面", this::chooseHome));
        entries.add(entry("管理桌面应用", () -> Ui.launch(this, new Intent(this, AppsActivity.class))));
        entries.add(entry("省电助手", () -> Ui.launch(this, new Intent(this, PowerActivity.class))));
        entries.add(entry("掌阅设置", () -> Ui.launch(this, new Intent().setClassName("com.szzy.ireader.settings", "com.szzy.ireader.settings.MainActivity"))));
        entries.add(entry("Android 系统设置", () -> Ui.launch(this, new Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"))));
        entries.add(entry("开发者选项", () -> Ui.launch(this, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage("com.android.settings"))));
        entries.add(entry("阅读灯", () -> Ui.launch(this, new Intent("com.szzy.ireader.ink.devicesetting.READING_LIGHT").setPackage("com.szzy.ireader.settings"))));
        entries.add(entry("应用显示与刷新", () -> Ui.launch(this, new Intent("com.szzy.ireader.ink.devicesetting.APP_DISPLAY").setPackage("com.szzy.ireader.settings"))));
        entries.add(entry("官方桌面", () -> Ui.officialHome(this)));
        Ui.grid(this, root, entries, 2, 96);
    }
    private Button entry(String label, Runnable action) {
        Button button = Ui.button(this, label, false, action); button.setTextSize(19); return button;
    }
    private void chooseHome() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            RoleManager manager = getSystemService(RoleManager.class);
            if (manager.isRoleAvailable(RoleManager.ROLE_HOME) && !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                Ui.launch(this, manager.createRequestRoleIntent(RoleManager.ROLE_HOME)); return;
            }
        }
        Ui.launch(this, new Intent(Settings.ACTION_HOME_SETTINGS).setPackage("com.android.settings"));
    }
}
