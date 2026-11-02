package com.ireaderlauncher.app;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.ireaderlauncher.bridge.IPowerBridge;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 实机验证权限、关键入口、非法输入及冻结后的原始状态恢复。 */
public final class SmokeInstrumentation extends Instrumentation {
    private boolean dozeOnly;
    private boolean backgroundOnly;
    private boolean locationOnly;
    private boolean lockOnly;
    private boolean desktopOnly;
    private boolean bluetoothOnly;
    private boolean nativePowerOnly;
    private boolean nativeLockOnly;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        dozeOnly = arguments != null && "doze".equals(arguments.getString("suite"));
        backgroundOnly = arguments != null && "background".equals(arguments.getString("suite"));
        locationOnly = arguments != null && "location".equals(arguments.getString("suite"));
        lockOnly = arguments != null && "lock".equals(arguments.getString("suite"));
        desktopOnly = arguments != null && "desktop".equals(arguments.getString("suite"));
        bluetoothOnly = arguments != null && "bluetooth".equals(arguments.getString("suite"));
        nativePowerOnly = arguments != null && "native_power".equals(arguments.getString("suite"));
        nativeLockOnly = arguments != null && "native_lock".equals(arguments.getString("suite")); start();
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = getTargetContext();
        CountDownLatch ready = new CountDownLatch(1);
        IPowerBridge[] remote = new IPowerBridge[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { remote[0] = IPowerBridge.Stub.asInterface(binder); ready.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { remote[0] = null; }
        };
        boolean bound = false;
        try {
            checkDesktop(c);
            if (nativePowerOnly || nativeLockOnly) {
                DuokanPowerSmoke nativeTest = new DuokanPowerSmoke(this);
                if (nativePowerOnly) nativeTest.power(); else nativeTest.lock();
                result.putString("result", nativePowerOnly ? "PASS: 多看省电状态、真实开关、息屏时长、推荐设置、非法输入与原始配置恢复"
                        : "PASS: 多看锁屏授权、真实息屏、重复锁屏与授权和屏幕原状态恢复");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (bluetoothOnly) {
                new BluetoothSmoke(this).run();
                result.putString("result", "PASS: 蓝牙偏好、真实开关、重复请求、通知操作、外部状态同步、恢复入口与原始状态恢复");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (desktopOnly || !DeviceProfile.current().powerControls && !lockOnly && !dozeOnly && !backgroundOnly && !locationOnly) {
                result.putString("result", "PASS: 默认桌面、厂商与系统入口、应用名称、收藏兼容及设备能力隔离");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            check(DeviceProfile.current().powerControls, "当前设备不支持掌阅省电控制测试，请使用 desktop 或 bluetooth 套件");
            bound = c.bindService(new Intent().setClassName("com.ireaderlauncher.powerbridge", "com.ireaderlauncher.powerbridge.PowerService"), connection, Context.BIND_AUTO_CREATE);
            check(bound && ready.await(10, TimeUnit.SECONDS), "权限组件无法绑定");
            if (lockOnly) {
                checkScreenLock(c, remote[0]);
                result.putString("result", "PASS: 锁屏后实际息屏、重复锁屏不唤醒、测试前屏幕状态恢复");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            JSONObject initial = new JSONObject(remote[0].status());
            check(initial.getBoolean("controlReady"), "权限组件无法执行控制");
            if (locationOnly) {
                checkLocationOptions(remote[0]);
                result.putString("result", "PASS: 21 项状态、定位与两项后台扫描开关、非法参数拒绝及原始状态恢复");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (dozeOnly) {
                checkDozeModes(remote[0]);
                result.putString("result", "PASS: Doze 关/轻度/重度双状态切换、降档、非法档位拒绝及当前档位恢复");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (backgroundOnly) {
                checkBackgroundOptions(remote[0]);
                result.putString("result", "PASS: 新增五项真实停用与重新启用、官方 HOME 保护、非法项目拒绝及原始包状态恢复");
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            check(!initial.getBoolean("hasBaseline"), "请先恢复原设置再运行实机测试，避免覆盖个人配置");
            check(initial.getJSONArray("options").length() == 21, "省电状态不完整");
            JSONArray options = initial.getJSONArray("options");
            boolean lightBefore = false, loggerBefore = false, loggerBootBefore = false;
            for (int i = 0; i < options.length(); i++) {
                JSONObject row = options.getJSONObject(i);
                if (!row.getString("id").equals("battery_saver"))
                    check(row.getBoolean("supported"), row.getString("id") + "状态读取失败：" + row.optString("error"));
                if (row.getString("id").equals("light_doze")) lightBefore = row.getBoolean("enabled");
                if (row.getString("id").equals("logger")) loggerBefore = row.getBoolean("enabled");
                if (row.getString("id").equals("logger_boot")) loggerBootBefore = row.getBoolean("enabled");
            }
            check(!new JSONObject(remote[0].setEnabled("wifi; reboot", false)).getBoolean("ok"), "未拒绝注入参数");
            check(!new JSONObject(remote[0].setEnabled("com.android.systemui", false)).getBoolean("ok"), "未保护系统组件");
            // 使用独立可选应用验证停用和恢复，恢复时必须保留原本的默认状态 0。
            int before = c.getPackageManager().getApplicationEnabledSetting("com.szzy.ireader.ink.ai");
            try {
                check(new JSONObject(remote[0].setEnabled("light_doze", !lightBefore)).getBoolean("ok"), "Doze 无法切换");
                check(new JSONObject(remote[0].setEnabled("ai", false)).getBoolean("ok"), "可选应用无法停用");
                check(c.getPackageManager().getApplicationEnabledSetting("com.szzy.ireader.ink.ai") == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, "应用停用未生效");
                // 回归厂商启停日志连带改写自启的问题，验证两个开关相互独立。
                check(new JSONObject(remote[0].setEnabled("logger", !loggerBefore)).getBoolean("ok"), "日志录制无法切换");
                check(enabled(remote[0], "logger_boot") == loggerBootBefore, "切换录制意外改变日志自启");
                check(new JSONObject(remote[0].recommended()).getBoolean("ok"), "推荐配置未完整生效");
                check(!enabled(remote[0], "logger") && !enabled(remote[0], "logger_boot") && enabled(remote[0], "light_doze")
                        && !enabled(remote[0], "deep_doze"), "推荐配置状态不正确");
                check(new JSONObject(remote[0].setEnabled("logger", true)).getBoolean("ok"), "日志无法重新开启");
                check(!enabled(remote[0], "logger_boot"), "开启录制意外打开日志自启");
            } finally {
                check(new JSONObject(remote[0].restore()).getBoolean("ok"), "原设置恢复失败");
            }
            check(c.getPackageManager().getApplicationEnabledSetting("com.szzy.ireader.ink.ai") == before, "应用原始状态没有精确恢复");
            check(enabled(remote[0], "logger") == loggerBefore && enabled(remote[0], "logger_boot") == loggerBootBefore
                    && enabled(remote[0], "light_doze") == lightBefore, "日志或 Doze 原值未完整恢复");
            result.putString("result", "PASS: 默认桌面、官方入口、开发者入口、全部项目状态、非法输入、Doze 切换、应用停用、日志开关独立、推荐配置及精确恢复");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("result", "FAIL: " + e);
            finish(android.app.Activity.RESULT_CANCELED, result);
        } finally { if (bound) c.unbindService(connection); }
    }
    /** 通用桌面验收独立于掌阅特权组件，多看无需绑定权限服务。 */
    private void checkDesktop(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        android.content.pm.ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        check(home != null && home.activityInfo.packageName.equals(c.getPackageName()), "默认桌面未生效");
        if (android.os.Build.VERSION.SDK_INT >= 29)
            check(c.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_HOME), "默认桌面角色未同步，重启可能改变默认桌面");
        DeviceProfile profile = DeviceProfile.current();
        // 掌阅允许用户主动冻结官方阅读服务，通用检查不要求重新启用它。
        check(profile.officialHome != null && pm.getActivityInfo(profile.officialHome, PackageManager.MATCH_DISABLED_COMPONENTS).exported, "官方桌面入口不可用");
        check(profile.settings != null && pm.getActivityInfo(profile.settings, 0).exported, "厂商设置入口不可用");
        for (String action : new String[]{android.provider.Settings.ACTION_SETTINGS,
                android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, android.provider.Settings.ACTION_HOME_SETTINGS})
            check(pm.resolveActivity(new Intent(action), 0) != null, "系统入口不可用：" + action);
        check(AppCatalog.displayName("com.android.settings", "设置").equals("Android 系统设置"), "Android 设置名称未区分");
        check(AppCatalog.displayName("com.moan.setting", "设置").equals("多看系统设置"), "多看设置名称未区分");
        check(AppCatalog.displayName("com.szzy.ireader.settings", "设置").equals("掌阅系统设置"), "掌阅设置名称未区分");
        check(AppCatalog.displayName("com.example", "设置").equals("设置"), "误改无关应用名称");
        java.util.Set<String> favorites = AppCatalog.favorites(c);
        for (android.content.pm.ResolveInfo app : AppCatalog.apps(c)) AppCatalog.displayName(c, app);
        check(AppCatalog.favorites(c).equals(favorites), "名称转换改动收藏记录");
        DeviceProfile duokan = DeviceProfile.forDevice("MiDuoKanReaderProII", 30);
        DeviceProfile ireader = DeviceProfile.forDevice("Neo 3 Ultra", 34);
        check(!duokan.powerControls && duokan.bluetoothQuickActions, "多看能力错误");
        check(duokan.hasPowerAssistant() && duokan.hasScreenLock() && duokan.nativePowerControls(), "多看省电或锁屏入口缺失");
        check(ireader.powerControls && !ireader.bluetoothQuickActions, "掌阅能力回归");
        check(ireader.officialHome.getPackageName().equals("com.zhangyue.iReader.Eink"), "掌阅官方入口回归");
        check(!DeviceProfile.forDevice("MiDuoKanReaderProII", 34).bluetoothQuickActions, "未知固件意外开启旧蓝牙 API");
        check(!DeviceProfile.forDevice("unknown", 30).powerControls, "未知设备意外启用特权功能");
    }
    /** 独立验证即时锁屏，不运行会修改个人配置的全量省电测试。 */
    private void checkScreenLock(Context context, IPowerBridge bridge) throws Exception {
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        boolean awake = power.isInteractive();
        try {
            shell("input keyevent KEYCODE_WAKEUP");
            for (int i = 0; i < 20 && !power.isInteractive(); i++) android.os.SystemClock.sleep(50);
            check(power.isInteractive(), "测试前无法唤醒屏幕");
            JSONObject result = new JSONObject(bridge.lockScreen());
            check(result.getBoolean("ok"), result.optString("message"));
            check(!power.isInteractive(), "锁屏后屏幕仍处于交互状态");
            check(new JSONObject(bridge.lockScreen()).getBoolean("ok"), "重复锁屏未成功");
            check(!power.isInteractive(), "重复锁屏意外唤醒屏幕");
        } finally {
            shell(awake ? "input keyevent KEYCODE_WAKEUP" : "input keyevent KEYCODE_SLEEP");
            for (int i = 0; i < 20 && power.isInteractive() != awake; i++) android.os.SystemClock.sleep(50);
            check(power.isInteractive() == awake, "测试前屏幕状态未恢复");
        }
    }
    /** 单独验证保留的定位与扫描功能，不改变无线主开关或已有省电配置。 */
    private void checkLocationOptions(IPowerBridge bridge) throws Exception {
        check(new JSONObject(bridge.status()).getJSONArray("options").length() == 21, "省电项目数量不正确");
        boolean location = enabled(bridge, "location");
        String wifiScan = shell("settings get global wifi_scan_always_enabled").trim();
        String bluetoothScan = shell("settings get global ble_scan_always_enabled").trim();
        check(!new JSONObject(bridge.setEnabled("location; reboot", false)).getBoolean("ok"), "未拒绝非法定位参数");
        try {
            for (String id : new String[]{"location", "wifi_scan", "bluetooth_scan"}) {
                check(option(bridge, id).getBoolean("supported"), id + "状态不可用");
                for (boolean value : new boolean[]{true, false}) {
                    JSONObject result = new JSONObject(bridge.setEnabled(id, value));
                    check(result.getBoolean("ok"), id + "切换失败：" + result.optString("message"));
                    check(enabled(bridge, id) == value, id + "实际状态不匹配");
                }
            }
        } finally {
            shell("cmd location set-location-enabled " + location + " --user 0");
            restoreScan("wifi_scan_always_enabled", wifiScan);
            restoreScan("ble_scan_always_enabled", bluetoothScan);
        }
        check(enabled(bridge, "location") == location, "定位原值未恢复");
        check(shell("settings get global wifi_scan_always_enabled").trim().equals(wifiScan), "Wi-Fi 扫描原值未恢复");
        check(shell("settings get global ble_scan_always_enabled").trim().equals(bluetoothScan), "蓝牙扫描原值未恢复");
    }
    private void restoreScan(String key, String raw) throws Exception {
        check(raw.equals("null") || raw.equals("0") || raw.equals("1"), "扫描原值无效");
        shell(raw.equals("null") ? "settings delete global " + key : "settings put global " + key + " " + raw);
    }
    /** 针对新增范围实测；不运行全局恢复，避免改变用户已有省电选择。 */
    private void checkBackgroundOptions(IPowerBridge bridge) throws Exception {
        String[] ids = {"official_launcher", "official_reader", "migration", "reflow", "printing"};
        String[] packages = {"com.szzy.ireader.ink.launcher", "com.zhangyue.iReader.Eink", "com.szzy.ireader.ink.wifidirect",
                "com.szzy.ireader.ink.office.reflow", "com.android.printspooler"};
        PackageManager pm = getTargetContext().getPackageManager();
        int[] originals = new int[ids.length];
        for (int i = 0; i < ids.length; i++) originals[i] = pm.getApplicationEnabledSetting(packages[i]);
        check(!new JSONObject(bridge.setEnabled("official_reader; reboot", false)).getBoolean("ok"), "未拒绝非法项目");
        check(!new JSONObject(bridge.setEnabled("com.szzy.ireader.ims", false)).getBoolean("ok"), "基础资源包未受保护");
        try {
            // 真正切换系统默认 HOME，验证控制端拒绝操作，而非仅依赖按钮禁用。
            shell("cmd package set-home-activity --user 0 com.zhangyue.iReader.Eink/.home.ActivityLauncher");
            check(!new JSONObject(bridge.setEnabled("official_reader", false)).getBoolean("ok"), "官方阅读 HOME 保护失效");
            check(!new JSONObject(bridge.setEnabled("official_launcher", false)).getBoolean("ok"), "独立桌面 HOME 保护失效");
            for (int i = 0; i < 2; i++) check(pm.getApplicationEnabledSetting(packages[i]) == originals[i], "保护拒绝后仍修改了包状态");
        } finally { shell("cmd package set-home-activity --user 0 com.ireaderlauncher.app/.MainActivity"); }
        for (int i = 0; i < ids.length; i++) {
            try {
                check(new JSONObject(bridge.setEnabled(ids[i], false)).getBoolean("ok"), "新增项目无法停用：" + ids[i]);
                check(pm.getApplicationEnabledSetting(packages[i]) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, "包未真实停用：" + ids[i]);
                check(option(bridge, ids[i]).getBoolean("optimized") && !enabled(bridge, ids[i]), "优化状态方向错误：" + ids[i]);
                check(new JSONObject(bridge.setEnabled(ids[i], true)).getBoolean("ok"), "新增项目无法启用：" + ids[i]);
                check(pm.getApplicationEnabledSetting(packages[i]) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED, "包未真实启用：" + ids[i]);
                check(!option(bridge, ids[i]).getBoolean("optimized") && enabled(bridge, ids[i]), "解除优化状态错误：" + ids[i]);
            } finally {
                String[] verbs = {"default-state", "enable", "disable", "disable-user", "disable-until-used"};
                shell("pm " + verbs[originals[i]] + " --user 0 " + packages[i]);
                check(pm.getApplicationEnabledSetting(packages[i]) == originals[i], "原始包状态恢复失败：" + ids[i]);
            }
        }
    }
    /** 仅测试程序借助 Instrumentation 的 shell 恢复精确原值，正式桌面没有此能力。 */
    private String shell(String command) throws Exception {
        ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    /** 只测试新增档位，保留用户已有日志配置与原始恢复备份。 */
    private void checkDozeModes(IPowerBridge bridge) throws Exception {
        boolean light = enabled(bridge, "light_doze"), deep = enabled(bridge, "deep_doze");
        try {
            check(!new JSONObject(bridge.setDozeMode("deep; reboot")).getBoolean("ok"), "未拒绝非法档位");
            check(enabled(bridge, "light_doze") == light && enabled(bridge, "deep_doze") == deep, "非法档位改变了系统状态");
            for (String mode : new String[]{"off", "light", "deep", "light", "off"}) {
                check(new JSONObject(bridge.setDozeMode(mode)).getBoolean("ok"), "档位切换失败：" + mode);
                check(enabled(bridge, "light_doze") == !mode.equals("off")
                        && enabled(bridge, "deep_doze") == mode.equals("deep"), "系统实际档位不匹配：" + mode);
            }
        } finally {
            check(new JSONObject(bridge.setDozeMode(deep ? "deep" : light ? "light" : "off")).getBoolean("ok"), "当前档位恢复失败");
            if (deep && !light) check(new JSONObject(bridge.setEnabled("light_doze", false)).getBoolean("ok"), "原轻度状态恢复失败");
        }
    }
    private boolean enabled(IPowerBridge bridge, String id) throws Exception {
        return option(bridge, id).getBoolean("enabled");
    }
    private JSONObject option(IPowerBridge bridge, String id) throws Exception {
        JSONArray options = new JSONObject(bridge.status()).getJSONArray("options");
        for (int i = 0; i < options.length(); i++) {
            JSONObject row = options.getJSONObject(i);
            if (row.getString("id").equals(id)) return row;
        }
        throw new AssertionError("缺少状态：" + id);
    }
}
