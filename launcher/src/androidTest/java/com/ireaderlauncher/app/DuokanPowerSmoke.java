package com.ireaderlauncher.app;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** 多看专项会修改并恢复有限设置；已有个人省电备份时拒绝运行。 */
final class DuokanPowerSmoke {
    private final Instrumentation instrumentation;
    private final Context context;
    DuokanPowerSmoke(Instrumentation instrumentation) {
        this.instrumentation = instrumentation; context = instrumentation.getTargetContext();
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void waitFor(BooleanSupplier ready, String message) {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        check(ready.getAsBoolean(), message);
    }
    private void progress(String text) { Bundle b = new Bundle(); b.putString("step", text); instrumentation.sendStatus(0, b); }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return out.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }
    private JSONObject option(DuokanPowerController controller, String id) throws Exception {
        JSONArray rows = controller.status().getJSONArray("options");
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getString("id").equals(id)) return rows.getJSONObject(i);
        throw new AssertionError("缺少省电项：" + id);
    }
    private void ok(JSONObject result) throws Exception { check(result.getBoolean("ok"), result.optString("message")); }

    @SuppressWarnings("deprecation")
    void power() throws Exception {
        check(DeviceProfile.current().nativePowerControls(), "当前设备不是已适配多看");
        DuokanPowerController controller = new DuokanPowerController(context);
        JSONObject initial = controller.status();
        check(initial.getBoolean("controlReady"), "请先运行安装工具授予系统设置权限");
        check(!initial.getBoolean("hasBaseline"), "请先恢复个人省电配置，再运行多看专项测试");
        check(initial.getJSONArray("options").length() == 6, "多看省电项目不完整");
        check(!shell("dumpsys battery").contains("UPDATES STOPPED"), "已有电池模拟状态，测试不会覆盖它");
        String[] tables = {"global", "system", "secure", "global", "global"};
        String[] keys = {"low_power", "screen_off_timeout", "location_mode", "wifi_scan_always_enabled", "ble_scan_always_enabled"};
        String[] original = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            original[i] = shell("settings get " + tables[i] + " " + keys[i]);
            check(original[i].equals("null") || original[i].matches("[0-9]+"), "原设置不属于安全恢复范围");
        }
        boolean bluetooth = BluetoothQuickActions.state(context) == BluetoothAdapter.STATE_ON;
        boolean wifi = context.getSystemService(android.net.wifi.WifiManager.class).isWifiEnabled();
        boolean simulated = false;
        try {
            check(!controller.change("location; reboot", false).getBoolean("ok"), "未拒绝非法省电项目");
            check(!controller.setTimeout(13).getBoolean("ok"), "未拒绝非法息屏时长");
            check(!controller.status().getBoolean("hasBaseline"), "非法请求写入了原始备份");
            for (String id : new String[]{"location", "wifi_scan", "bluetooth_scan", "bluetooth"}) {
                for (boolean enabled : new boolean[]{false, true}) {
                    ok(controller.change(id, enabled));
                    check(option(controller, id).getBoolean("enabled") == enabled, "实际状态不匹配：" + id);
                }
            }
            progress("定位、扫描与蓝牙真实开关通过");
            ok(controller.setTimeout(60000)); check(shell("settings get system screen_off_timeout").equals("60000"), "自动息屏时长未生效");
            ok(controller.setTimeout(300000)); check(shell("settings get system screen_off_timeout").equals("300000"), "自动息屏第二档未生效");
            boolean locationBeforeRecommendation = option(controller, "location").getBoolean("enabled");
            boolean bluetoothBeforeRecommendation = option(controller, "bluetooth").getBoolean("enabled");
            ok(controller.recommended());
            check(shell("settings get system screen_off_timeout").equals("120000"), "推荐息屏时长错误");
            check(!option(controller, "wifi_scan").getBoolean("enabled") && !option(controller, "bluetooth_scan").getBoolean("enabled"), "推荐扫描状态错误");
            check(option(controller, "location").getBoolean("enabled") == locationBeforeRecommendation
                    && option(controller, "bluetooth").getBoolean("enabled") == bluetoothBeforeRecommendation
                    && context.getSystemService(android.net.wifi.WifiManager.class).isWifiEnabled() == wifi, "推荐设置改变了无线主开关或定位");
            progress("自动息屏档位及推荐设置通过");
            // 只模拟电池上报为未充电，以验证系统省电模式；finally 始终恢复真实电池上报。
            shell("dumpsys battery unplug"); simulated = true; SystemClock.sleep(300);
            ok(controller.change("battery_saver", true));
            check(context.getSystemService(PowerManager.class).isPowerSaveMode(), "系统未真正进入省电模式");
            ok(controller.change("battery_saver", false));
            check(!context.getSystemService(PowerManager.class).isPowerSaveMode(), "系统未退出省电模式");
            progress("系统省电模式实际开启及关闭通过");
        } finally {
            if (simulated) shell("dumpsys battery reset");
            JSONObject restored = controller.restore();
            // 若被测恢复路径失败，测试仍用 shell 还原精确原值，再报告失败，避免遗留设备修改。
            if (!restored.optBoolean("ok")) {
                for (int i = 0; i < keys.length; i++) shell(original[i].equals("null")
                        ? "settings delete " + tables[i] + " " + keys[i]
                        : "settings put " + tables[i] + " " + keys[i] + " " + original[i]);
                BluetoothAdapter adapter = BluetoothQuickActions.adapter(context);
                if (bluetooth) adapter.enable(); else adapter.disable();
            }
            for (int i = 0; i < keys.length; i++) check(shell("settings get " + tables[i] + " " + keys[i]).equals(original[i]), "原值未恢复：" + keys[i]);
            waitFor(() -> BluetoothQuickActions.state(context) == (bluetooth ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF), "蓝牙原值未恢复");
            ok(restored);
            check(!controller.status().getBoolean("hasBaseline"), "恢复成功后仍保留备份");
            for (int i = 0; i < keys.length; i++) if (original[i].equals("null"))
                check(!shell("settings list " + tables[i]).contains(keys[i] + "="), "未设置的键没有精确删除：" + keys[i]);
        }
        progress("原值、未设置状态与电池上报恢复通过");
    }

    void lock() throws Exception {
        check(DeviceProfile.current().nativePowerControls(), "当前设备不是已适配多看");
        PowerManager power = context.getSystemService(PowerManager.class);
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        boolean authorized = ScreenLockActivity.authorized(context), awake = power.isInteractive();
        try {
            if (!authorized) shell("dpm set-active-admin --user 0 com.ireaderlauncher.app/.ScreenLockAdminReceiver");
            waitFor(() -> ScreenLockActivity.authorized(context), "锁屏授权未生效");
            shell("input keyevent KEYCODE_WAKEUP"); waitFor(power::isInteractive, "测试前无法唤醒屏幕");
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, ScreenLockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            waitFor(() -> !power.isInteractive(), "桌面锁屏操作未实际息屏");
            policy.lockNow(); SystemClock.sleep(500);
            check(!power.isInteractive(), "重复锁屏意外唤醒屏幕");
        } finally {
            if (!authorized) {
                policy.removeActiveAdmin(ScreenLockActivity.admin(context));
                waitFor(() -> !ScreenLockActivity.authorized(context), "测试前锁屏授权状态未恢复");
            }
            shell(awake ? "input keyevent KEYCODE_WAKEUP" : "input keyevent KEYCODE_SLEEP");
            waitFor(() -> power.isInteractive() == awake, "屏幕原状态未恢复");
        }
    }
}
