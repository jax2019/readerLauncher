package com.ireaderlauncher.app;

import android.content.ComponentName;
import android.os.Build;

/** 厂商入口和特权能力按已验证机型隔离，避免把掌阅控制组件用在其他固件上。 */
final class DeviceProfile {
    final String id;
    final ComponentName settings;
    final ComponentName officialHome;
    final boolean powerControls;
    final boolean bluetoothQuickActions;

    private DeviceProfile(String id, ComponentName settings, ComponentName home, boolean power, boolean bluetooth) {
        this.id = id; this.settings = settings; this.officialHome = home;
        this.powerControls = power; this.bluetoothQuickActions = bluetooth;
    }

    static DeviceProfile current() { return forDevice(Build.MODEL, Build.VERSION.SDK_INT); }
    boolean nativePowerControls() { return "duokan".equals(id); }
    boolean hasPowerAssistant() { return powerControls || nativePowerControls(); }
    boolean hasScreenLock() { return powerControls || nativePowerControls(); }

    static DeviceProfile forDevice(String model, int sdk) {
        if ("MiDuoKanReaderProII".equals(model) && sdk == 30)
            return new DeviceProfile("duokan", new ComponentName("com.moan.setting", "com.moan.setting.setting.MySettingActivity"),
                    new ComponentName("com.duokan.einkreader", "com.duokan.home.DkHomeActivity"), false, true);
        if ("Neo 3 Ultra".equals(model) && sdk == 34)
            return new DeviceProfile("ireader", new ComponentName("com.szzy.ireader.settings", "com.szzy.ireader.settings.MainActivity"),
                    new ComponentName("com.zhangyue.iReader.Eink", "com.zhangyue.iReader.Eink.home.ActivityLauncher"), true, false);
        return new DeviceProfile("generic", null, null, false, false);
    }
}
