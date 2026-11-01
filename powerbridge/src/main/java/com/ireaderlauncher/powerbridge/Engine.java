package com.ireaderlauncher.powerbridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 读回真实状态后才报告成功，并在首次修改之前保存可恢复的原值。 */
final class Engine {
    private static Engine instance;
    private final Context context;
    private final SharedPreferences prefs;
    private Boolean controlAvailable;

    Engine(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.createDeviceProtectedStorageContext().getSharedPreferences("power_state", Context.MODE_PRIVATE);
    }
    static synchronized Engine get(Context context) {
        if (instance == null) instance = new Engine(context);
        return instance;
    }
    private static JSONObject object(Object... values) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) o.put((String) values[i], values[i + 1]); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return o;
    }
    private JSONObject stored(String name) {
        try { return new JSONObject(prefs.getString(name, "{}")); }
        catch (Exception e) { throw new IllegalStateException("保存的省电配置无法读取，请先导出诊断", e); }
    }
    private boolean controlReady() {
        if (controlAvailable == null || !controlAvailable) {
            try {
                String uid = ControlShell.run("id -u");
                controlAvailable = "2000".equals(uid) || "0".equals(uid);
            }
            catch (Exception e) { controlAvailable = false; }
        }
        return controlAvailable;
    }
    private boolean charging() {
        Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i != null && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }
    JSONObject lockScreen() {
        try {
            if (!controlReady()) throw new IllegalStateException("控制权限暂不可用");
            // 专用休眠键只会息屏，重复请求不会像电源键那样再次点亮屏幕。
            ControlShell.run("input keyevent KEYCODE_SLEEP");
            PowerManager power = context.getSystemService(PowerManager.class);
            for (int i = 0; i < 20 && power.isInteractive(); i++) android.os.SystemClock.sleep(50);
            if (power.isInteractive()) throw new IllegalStateException("设备尚未进入息屏状态，请重试");
            // 锁屏是即时操作，不写入省电备份，也不更改任何持久配置。
            return object("ok", true, "message", "已锁屏");
        } catch (Exception e) { return object("ok", false, "message", "锁屏失败：" + e.getMessage()); }
    }
    private boolean flag(String command) throws Exception {
        String s = ControlShell.run(command).trim();
        if (!s.equals("0") && !s.equals("1")) throw new IllegalStateException("系统未返回有效状态");
        return s.equals("1");
    }
    private JSONObject query(String id) throws Exception {
        if (!Policy.allowed(id)) throw new IllegalArgumentException("不支持的省电项目");
        if (Policy.PACKAGES.containsKey(id)) {
            String pkg = Policy.PACKAGES.get(id);
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            int raw = pm.getApplicationEnabledSetting(pkg);
            return object("enabled", info.enabled, "raw", raw,
                    "persistent", (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0);
        }
        boolean enabled;
        switch (id) {
            case "logger": enabled = flag("getprop vendor.MB.running"); break;
            case "logger_boot": {
                enabled = loggerAutoStart(); break;
            }
            case "light_doze": enabled = flag("cmd deviceidle enabled light"); break;
            case "deep_doze": enabled = flag("cmd deviceidle enabled deep"); break;
            case "wifi": enabled = flag("settings get global wifi_on"); break;
            case "bluetooth": enabled = flag("settings get global bluetooth_on"); break;
            case "location": enabled = context.getSystemService(LocationManager.class).isLocationEnabled(); break;
            case "wifi_scan": enabled = context.getSystemService(WifiManager.class).isScanAlwaysAvailable(); break;
            case "bluetooth_scan": enabled = Settings.Global.getInt(context.getContentResolver(), "ble_scan_always_enabled", 0) != 0; break;
            case "battery_saver": enabled = context.getSystemService(PowerManager.class).isPowerSaveMode(); break;
            default: throw new IllegalArgumentException("不支持的省电项目");
        }
        // 扫描配置可能尚未写入数据库，备份时保留“未设置”，恢复时精确删除。
        if (id.equals("wifi_scan") || id.equals("bluetooth_scan"))
            return object("enabled", enabled, "raw", ControlShell.run("settings get global " + scanKey(id)));
        return object("enabled", enabled, "raw", enabled);
    }
    private static String scanKey(String id) {
        if (id.equals("wifi_scan")) return "wifi_scan_always_enabled";
        if (id.equals("bluetooth_scan")) return "ble_scan_always_enabled";
        throw new IllegalArgumentException("不支持的扫描项目");
    }

    private boolean loggerAutoStart() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        boolean[] value = new boolean[1];
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if ("get_log_auto_status".equals(i.getStringExtra("result_name")) && i.hasExtra("result_value")) {
                    value[0] = i.getBooleanExtra("result_value", false);
                    received.countDown();
                }
            }
        };
        // 日志应用实际持有此签名权限，普通应用无法伪造状态广播。
        IntentFilter filter = new IntentFilter("com.debug.loggerui.result");
        if (android.os.Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, "android.permission.SET_DEBUG_APP", null, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, "android.permission.SET_DEBUG_APP", null);
        try {
            loggerCommand("get_log_auto_status");
            if (!received.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("厂商日志状态查询超时");
            return value[0];
        } finally { context.unregisterReceiver(receiver); }
    }
    private static String title(String id) {
        switch (id) {
            case "logger": return "调试日志录制";
            case "logger_boot": return "调试日志开机自启";
            case "light_doze": return "轻度 Doze";
            case "deep_doze": return "深度 Doze";
            case "wifi": return "Wi-Fi";
            case "bluetooth": return "蓝牙";
            case "location": return "定位（GPS）";
            case "wifi_scan": return "Wi-Fi 后台扫描";
            case "bluetooth_scan": return "蓝牙后台扫描";
            case "battery_saver": return "系统省电模式";
            case "ai": return "掌阅 AI";
            case "assistant": return "掌阅智能助手";
            case "market": return "掌阅应用市场";
            case "music": return "掌阅音乐播放器";
            case "tts": return "掌阅语音朗读";
            case "dictionary": return "掌阅翻译词典";
            case "official_launcher": return "官方桌面组件";
            case "official_reader": return "官方书城与阅读服务";
            case "migration": return "换机助手";
            case "reflow": return "Office 文档重排";
            case "printing": return "系统打印服务";
            default: return id;
        }
    }
    private static String description(String id) {
        switch (id) {
            case "logger": return "关闭可减少额外日志处理与持续写盘";
            case "logger_boot": return "关闭后，设备重启不再自动录制调试日志";
            case "light_doze": return "建议开启，限制息屏后的后台任务与联网";
            case "deep_doze": return "实验选项：限制更严格，可能延迟同步";
            case "wifi": return "离线阅读可关闭；下载和进度同步需要联网";
            case "bluetooth": return "耳机、键盘和蓝牙翻页器需要开启";
            case "location": return "关闭系统定位，包括 GPS 和网络定位；需要定位的应用会受影响";
            case "wifi_scan": return "关闭 Wi-Fi 后仍可用于定位的扫描；关闭此项不影响正常联网";
            case "bluetooth_scan": return "关闭蓝牙后仍可用于定位的扫描；关闭此项不影响已开启的蓝牙连接";
            case "battery_saver": return "使用系统省电策略，充电时由系统关闭";
            case "ai": return "冻结后无法使用掌阅 AI 功能";
            case "assistant": return "冻结后无法使用智能助手及相关快捷入口";
            case "market": return "冻结后暂停掌阅应用市场及其更新功能";
            case "music": return "冻结后无法使用掌阅音乐播放器";
            case "tts": return "冻结后可能影响朗读及依赖它的应用";
            case "dictionary": return "冻结后可能影响查词与翻译";
            case "official_launcher": return "停用独立的 iReader 桌面及小组件；主页的官方桌面入口由下一项控制";
            case "official_reader": return "同时停用官方桌面入口、书城、本地阅读、云盘、文件导入及相关数据接口；需要时关闭此项恢复";
            case "migration": return "停用换机迁移与相关后台组件，不会关闭 Wi-Fi；迁移设备前需恢复";
            case "reflow": return "停用掌阅 Office 文档转换与重排；阅读 Word 等文档前需恢复";
            case "printing": return "停用打印任务与打印后台；使用打印或系统打印转 PDF 前需恢复";
            default: return "开启优化会冻结此应用，可随时关闭优化";
        }
    }

    synchronized JSONObject status() {
        JSONArray options = new JSONArray();
        List<String> ids = new ArrayList<>(Arrays.asList("logger", "logger_boot", "light_doze", "deep_doze", "battery_saver", "wifi", "bluetooth", "location", "wifi_scan", "bluetooth_scan"));
        ids.addAll(Policy.PACKAGES.keySet());
        boolean ready = controlReady();
        for (String id : ids) {
            boolean appOptimization = Policy.PACKAGES.containsKey(id);
            JSONObject row = object("id", id, "title", title(id), "description", description(id), "supported", ready,
                    "appOptimization", appOptimization);
            try {
                if (!ready) throw new IllegalStateException("权限组件暂时不可用");
                JSONObject value = query(id);
                row.put("enabled", value.getBoolean("enabled"));
                if (appOptimization) {
                    // 保留 enabled 的系统语义，额外提供正向的优化状态，兼容旧数据与旧调用。
                    boolean optimized = !value.getBoolean("enabled");
                    row.put("optimized", optimized);
                    boolean persistent = value.optBoolean("persistent");
                    row.put("persistent", persistent);
                    String detail = (optimized ? "已停用，组件启动受限。" : "应用可用，开启优化后冻结。") + "\n" + description(id);
                    // 系统常驻应用的包停用状态，不能等同于进程已退出。
                    if (persistent) detail += "\n系统常驻进程可能保留，停用不代表后台已完全退出。";
                    if (Policy.requiresLauncherHome(id) && !optimized && !isLauncherHome()) {
                        row.put("supported", false);
                        detail += "\n请先在设置中将阅桌面设为默认桌面。";
                    }
                    row.put("description", detail);
                }
                if (id.equals("battery_saver") && charging()) {
                    row.put("supported", false);
                    row.put("description", "正在充电，系统暂不允许开启省电模式");
                }
            } catch (Exception e) {
                try { row.put("supported", false); row.put("error", message(e)); }
                catch (Exception ignored) { /* 固定键与基础类型不应导致 JSON 错误。 */ }
            }
            options.put(row);
        }
        return object("controlReady", ready, "options", options, "hasBaseline", stored("baseline").length() != 0,
                "lastOperation", prefs.getString("lastOperation", "尚未应用省电设置"));
    }

    /** 广播命令来自实机日志应用的固定接口，参数完全由内部枚举生成。 */
    private void loggerCommand(String command) throws Exception {
        ControlShell.run("am broadcast --user 0 -a com.debug.loggerui.ADB_CMD"
                + " -n com.debug.loggerui/.framework.LogReceiver --es cmd_name " + command + " --ei cmd_target 1");
    }
    private void write(String id, boolean enabled) throws Exception {
        if (!controlReady()) throw new IllegalStateException("当前没有控制权限");
        if (Policy.PACKAGES.containsKey(id)) {
            checkHomeProtection(id, enabled);
            ControlShell.run("pm " + (enabled ? "enable" : "disable-user") + " --user 0 " + Policy.PACKAGES.get(id));
            return;
        }
        switch (id) {
            case "logger": {
                // 厂商的启停录制命令会连带改写自启；两个开关需要保持独立。
                boolean autoStart = loggerAutoStart();
                loggerCommand(enabled ? "start" : "stop");
                verify("logger", enabled);
                if (loggerAutoStart() != autoStart) {
                    loggerCommand("set_auto_start_" + (autoStart ? "1" : "0"));
                    verify("logger_boot", autoStart);
                }
                break;
            }
            case "logger_boot": loggerCommand("set_auto_start_" + (enabled ? "1" : "0")); break;
            case "light_doze": ControlShell.run("cmd deviceidle " + (enabled ? "enable" : "disable") + " light"); break;
            case "deep_doze": ControlShell.run("cmd deviceidle " + (enabled ? "enable" : "disable") + " deep"); break;
            case "wifi": ControlShell.run("cmd wifi set-wifi-enabled " + (enabled ? "enabled" : "disabled")); break;
            case "bluetooth": ControlShell.run("cmd bluetooth_manager " + (enabled ? "enable" : "disable")); break;
            case "location": ControlShell.run("cmd location set-location-enabled " + enabled + " --user 0"); break;
            case "wifi_scan": ControlShell.run("cmd wifi set-scan-always-available " + (enabled ? "enabled" : "disabled")); break;
            case "bluetooth_scan": ControlShell.run("settings put global ble_scan_always_enabled " + (enabled ? "1" : "0")); break;
            case "battery_saver":
                if (enabled && charging()) throw new IllegalStateException("充电期间无法开启系统省电模式");
                ControlShell.run("cmd power set-mode " + (enabled ? "1" : "0")); break;
            default: throw new IllegalArgumentException("不支持的省电项目");
        }
    }
    private boolean isLauncherHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT);
        ResolveInfo resolved = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && "com.ireaderlauncher.app".equals(resolved.activityInfo.packageName)
                && "com.ireaderlauncher.app.MainActivity".equals(resolved.activityInfo.name);
    }
    /** 在控制端保护 HOME，绕过界面直接调用也不能冻结当前唯一桌面。 */
    private void checkHomeProtection(String id, boolean enabled) {
        if (!enabled && Policy.requiresLauncherHome(id) && !isLauncherHome())
            throw new IllegalStateException("请先将阅桌面设为默认桌面，再优化官方桌面或阅读服务");
    }
    private void verify(String id, boolean expected) throws Exception {
        // 系统广播和无线状态变化是异步的；只在用户操作期间短暂等待。
        for (int i = 0; i < 20; i++) {
            if (query(id).getBoolean("enabled") == expected) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("系统尚未达到目标状态，请刷新；原设置已保留");
    }
    private void rememberDoze(String id, boolean enabled) throws Exception {
        if (id.equals("light_doze") || id.equals("deep_doze")) {
            JSONObject desired = stored("boot");
            desired.put(id, enabled);
            if (!prefs.edit().putString("boot", desired.toString()).commit()) throw new IllegalStateException("开机设置保存失败");
        }
    }
    private void rememberOriginal(String... ids) throws Exception {
        JSONObject baseline = stored("baseline");
        for (String id : ids) {
            if (!Policy.allowed(id)) throw new IllegalArgumentException("不支持的省电项目");
            if (!baseline.has(id)) baseline.put(id, query(id));
        }
        if (!prefs.edit().putString("baseline", baseline.toString()).commit()) throw new IllegalStateException("原设置备份失败");
    }
    private void apply(String id, boolean enabled) throws Exception {
        if (!Policy.allowed(id)) throw new IllegalArgumentException("不支持的省电项目");
        checkHomeProtection(id, enabled);
        // 先备份可能受厂商命令联动影响的项目，再执行任何修改。
        if (id.equals("logger")) rememberOriginal("logger", "logger_boot");
        else rememberOriginal(id);
        write(id, enabled);
        verify(id, enabled);
        rememberDoze(id, enabled);
    }
    private static String message(Exception e) {
        String s = e.getMessage();
        return s == null || s.trim().isEmpty() ? e.getClass().getSimpleName() : s;
    }
    private JSONObject result(boolean ok, String text) {
        prefs.edit().putString("lastOperation", text).apply();
        return object("ok", ok, "message", text, "state", status());
    }
    synchronized JSONObject change(String id, boolean enabled) {
        try {
            apply(id, enabled);
            if (Policy.PACKAGES.containsKey(id)) {
                String text = title(id) + (enabled ? "：优化已关闭，应用已重新启用" : "：优化已开启，应用已停用");
                if (!enabled && query(id).optBoolean("persistent")) text += "；系统常驻进程可能保留";
                return result(true, text);
            }
            return result(true, title(id) + "已" + (enabled ? "开启" : "关闭"));
        }
        catch (Exception e) { return result(false, message(e)); }
    }
    /** 重度包含轻度阶段；三档切换同时控制两项，避免遗留上一个档位的开关。 */
    private void writeDoze(boolean light, boolean deep) throws Exception {
        if (!deep) write("deep_doze", false);
        write("light_doze", light);
        if (deep) write("deep_doze", true);
        verify("light_doze", light);
        verify("deep_doze", deep);
    }
    private void applyDozeMode(String mode) throws Exception {
        if (!"off".equals(mode) && !"light".equals(mode) && !"deep".equals(mode))
            throw new IllegalArgumentException("不支持的 Doze 档位");
        rememberOriginal("light_doze", "deep_doze");
        boolean beforeLight = query("light_doze").getBoolean("enabled");
        boolean beforeDeep = query("deep_doze").getBoolean("enabled");
        boolean light = !mode.equals("off"), deep = mode.equals("deep");
        JSONObject originalBoot = stored("boot");
        try {
            writeDoze(light, deep);
            JSONObject boot = stored("boot");
            boot.put("light_doze", light); boot.put("deep_doze", deep);
            if (!prefs.edit().putString("boot", boot.toString()).commit()) throw new IllegalStateException("开机设置保存失败");
        } catch (Exception e) {
            // 其中一步失败时回到切换前的两项实际状态，并保留最初的恢复备份。
            try {
                writeDoze(beforeLight, beforeDeep);
                if (!prefs.edit().putString("boot", originalBoot.toString()).commit()) throw new IllegalStateException("开机设置回退失败");
            } catch (Exception rollback) { throw new IllegalStateException(message(e) + "；回退未完成：" + message(rollback), e); }
            throw e;
        }
    }
    synchronized JSONObject changeDozeMode(String mode) {
        try {
            applyDozeMode(mode);
            return result(true, "Doze 已设为" + (mode.equals("off") ? "关闭" : mode.equals("light") ? "轻度" : "重度"));
        } catch (Exception e) { return result(false, message(e)); }
    }
    synchronized JSONObject recommended() {
        try { rememberOriginal(Policy.recommendations()); }
        catch (Exception e) { return result(false, "原设置尚未完整备份：" + message(e)); }
        StringBuilder errors = new StringBuilder();
        for (String id : Policy.recommendations()) {
            if (id.equals("deep_doze")) continue;
            try {
                if (id.equals("light_doze")) applyDozeMode("light");
                else apply(id, Policy.recommendedValue(id));
            }
            catch (Exception e) { errors.append(title(id)).append("：").append(message(e)).append('\n'); }
        }
        return result(errors.length() == 0, errors.length() == 0
                ? "已关闭调试录制与自启，并开启轻度 Doze" : "部分设置未完成：\n" + errors);
    }
    synchronized JSONObject restore() {
        JSONObject baseline = stored("baseline");
        JSONObject boot = stored("boot");
        StringBuilder errors = new StringBuilder();
        JSONArray keys = baseline.names();
        if (keys == null) return result(true, "暂无需要恢复的设置");
        for (int i = 0; i < keys.length(); i++) {
            String id = keys.optString(i);
            try {
                if (!Policy.allowed(id)) throw new IllegalStateException("备份项目不在允许范围内");
                JSONObject original = baseline.getJSONObject(id);
                if (Policy.PACKAGES.containsKey(id)) {
                    checkHomeProtection(id, original.getBoolean("enabled"));
                    int raw = original.getInt("raw");
                    String verb;
                    switch (raw) {
                        case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT: verb = "default-state"; break;
                        case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: verb = "enable"; break;
                        case PackageManager.COMPONENT_ENABLED_STATE_DISABLED: verb = "disable"; break;
                        case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER: verb = "disable-user"; break;
                        case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED: verb = "disable-until-used"; break;
                        default: throw new IllegalStateException("原应用状态无效");
                    }
                    ControlShell.run("pm " + verb + " --user 0 " + Policy.PACKAGES.get(id));
                } else if (id.equals("wifi_scan") || id.equals("bluetooth_scan")) {
                    String raw = original.getString("raw");
                    if (!raw.equals("null") && !raw.equals("0") && !raw.equals("1")) throw new IllegalStateException("扫描备份无效");
                    ControlShell.run(raw.equals("null") ? "settings delete global " + scanKey(id)
                            : "settings put global " + scanKey(id) + " " + raw);
                } else write(id, original.getBoolean("enabled"));
                verify(id, original.getBoolean("enabled"));
                baseline.remove(id);
                // 原本即开启的 Doze 也要在重启后恢复，避免回退后丢失原状态。
                if (id.equals("light_doze") || id.equals("deep_doze")) boot.put(id, original.getBoolean("enabled"));
                prefs.edit().putString("baseline", baseline.toString()).putString("boot", boot.toString()).commit();
            } catch (Exception e) { errors.append(title(id)).append("：").append(message(e)).append('\n'); }
        }
        return result(errors.length() == 0, errors.length() == 0 ? "已恢复修改前的设置" : "部分项目尚未恢复：\n" + errors);
    }
    synchronized void restoreBootSettings() {
        try {
            JSONObject boot = stored("boot");
            Iterator<String> keys = boot.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                if (!id.equals("light_doze") && !id.equals("deep_doze")) continue;
                write(id, boot.getBoolean(id));
                verify(id, boot.getBoolean(id));
            }
        } catch (Exception e) { prefs.edit().putString("lastOperation", "开机恢复未完成：" + message(e)).apply(); }
    }
}
