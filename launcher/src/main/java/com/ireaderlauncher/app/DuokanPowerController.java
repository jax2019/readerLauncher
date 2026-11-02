package com.ireaderlauncher.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 多看普通应用权限实现：固定设置白名单，首次修改备份，读回实际状态后报告结果。 */
final class DuokanPowerController {
    static final String[] IDS = {"battery_saver", "screen_timeout", "bluetooth", "location", "wifi_scan", "bluetooth_scan"};
    private final Context context;
    private final SharedPreferences preferences;

    DuokanPowerController(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("duokan_power", Context.MODE_PRIVATE);
    }
    private JSONObject object(Object... values) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private JSONObject baseline() throws Exception { return new JSONObject(preferences.getString("baseline", "{}")); }
    private void saveBaseline(JSONObject baseline) {
        if (!preferences.edit().putString("baseline", baseline.toString()).commit())
            throw new IllegalStateException("无法保存原设置，操作已停止");
    }
    private boolean securePermission() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean writable(String id) {
        if ("bluetooth".equals(id)) return BluetoothQuickActions.available(context);
        if ("screen_timeout".equals(id)) return Settings.System.canWrite(context);
        return securePermission();
    }
    private boolean charging() {
        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return status != null && status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }
    private String key(String id) {
        switch (id) {
            case "battery_saver": return "low_power";
            case "screen_timeout": return Settings.System.SCREEN_OFF_TIMEOUT;
            case "location": return Settings.Secure.LOCATION_MODE;
            case "wifi_scan": return "wifi_scan_always_enabled";
            case "bluetooth_scan": return "ble_scan_always_enabled";
            default: throw new IllegalArgumentException("不支持的省电项目");
        }
    }
    private String raw(String id) {
        if ("bluetooth".equals(id)) {
            int state = BluetoothQuickActions.state(context);
            if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF)
                throw new IllegalStateException("蓝牙正在切换或暂不可用");
            return state == BluetoothAdapter.STATE_ON ? "1" : "0";
        }
        String key = key(id);
        if ("screen_timeout".equals(id)) return Settings.System.getString(context.getContentResolver(), key);
        if ("location".equals(id)) return Settings.Secure.getString(context.getContentResolver(), key);
        return Settings.Global.getString(context.getContentResolver(), key);
    }
    private boolean enabled(String id) {
        if ("battery_saver".equals(id)) return context.getSystemService(PowerManager.class).isPowerSaveMode();
        if ("location".equals(id)) return context.getSystemService(LocationManager.class).isLocationEnabled();
        return "1".equals(raw(id));
    }
    static String title(String id) {
        switch (id) {
            case "battery_saver": return "系统省电模式";
            case "screen_timeout": return "自动息屏";
            case "bluetooth": return "蓝牙";
            case "location": return "定位（GPS）";
            case "wifi_scan": return "Wi-Fi 后台扫描";
            case "bluetooth_scan": return "蓝牙后台扫描";
            default: return id;
        }
    }
    private String description(String id) {
        switch (id) {
            case "battery_saver": return "使用系统省电策略；充电时由系统关闭";
            case "screen_timeout": return "无操作后自动息屏；恢复时还原原来的时长";
            case "bluetooth": return "耳机、键盘和翻页器需要开启；不要求启用桌面快捷通知";
            case "location": return "控制系统定位总开关；关闭后需要定位的功能可能受影响";
            case "wifi_scan": return "关闭 Wi-Fi 后仍可用于定位的扫描；不关闭正常 Wi-Fi 连接";
            case "bluetooth_scan": return "关闭蓝牙后仍可用于定位的扫描；不关闭正常蓝牙连接";
            default: return "";
        }
    }
    synchronized JSONObject status() throws Exception {
        JSONArray options = new JSONArray();
        for (String id : IDS) {
            JSONObject row = object("id", id, "title", title(id), "description", description(id), "supported", writable(id));
            try {
                if ("screen_timeout".equals(id)) row.put("milliseconds", Long.parseLong(raw(id)));
                else row.put("enabled", enabled(id));
                if (!writable(id)) row.put("description", "尚未完成系统设置授权，请重新运行项目安装工具。");
                if ("battery_saver".equals(id) && charging()) {
                    row.put("supported", false); row.put("description", "正在充电，系统暂不允许开启省电模式");
                }
            } catch (Exception e) { row.put("supported", false); row.put("error", e.getMessage()); }
            options.put(row);
        }
        boolean ready = securePermission() && Settings.System.canWrite(context);
        return object("controlReady", ready, "options", options, "hasBaseline", baseline().length() != 0,
                "controlSummary", ready ? "多看系统控制已就绪 · 授权重启后保留" : "部分控制未授权，请重新运行项目安装工具",
                "lastOperation", preferences.getString("lastOperation", "尚未修改省电设置"));
    }
    private void checkWritable(String id) {
        if (!DeviceProfile.current().nativePowerControls()) throw new IllegalStateException("此控制方式仅适用于已适配多看");
        if (!"bluetooth".equals(id)) key(id);
        if (!writable(id)) throw new IllegalStateException("尚未完成系统设置授权，请重新运行项目安装工具");
    }
    private void remember(JSONObject baseline, String id) throws Exception {
        if (!baseline.has(id)) { String value = raw(id); baseline.put(id, value == null ? JSONObject.NULL : value); }
    }
    @SuppressWarnings("deprecation")
    private void writeBluetooth(boolean turnOn) throws Exception {
        int target = turnOn ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
        int before = BluetoothQuickActions.state(context);
        if (before == target) return;
        if (BluetoothQuickActions.busy(context)) throw new IllegalStateException("蓝牙正在切换，请稍后重试");
        CountDownLatch changed = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (BluetoothQuickActions.state(c) == target) changed.countDown();
            }
        };
        context.registerReceiver(receiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        try {
            BluetoothAdapter adapter = BluetoothQuickActions.adapter(context);
            if (adapter == null || !(turnOn ? adapter.enable() : adapter.disable()))
                throw new IllegalStateException("系统未接受蓝牙切换");
            if (BluetoothQuickActions.state(context) != target) changed.await(15, TimeUnit.SECONDS);
            if (BluetoothQuickActions.state(context) != target) throw new IllegalStateException("蓝牙切换超时，请刷新状态");
        } finally { context.unregisterReceiver(receiver); }
    }
    private void write(String id, String value) throws Exception {
        checkWritable(id);
        if ("bluetooth".equals(id)) { writeBluetooth("1".equals(value)); return; }
        if ("battery_saver".equals(id) && "1".equals(value) && charging())
            throw new IllegalStateException("充电时不能开启系统省电模式");
        String key = key(id);
        boolean written = true;
        if (value == null) {
            // 原配置不存在时删除该项，避免把“未设置”变成显式的零值。
            android.net.Uri uri = "screen_timeout".equals(id) ? Settings.System.getUriFor(key)
                    : "location".equals(id) ? Settings.Secure.getUriFor(key) : Settings.Global.getUriFor(key);
            context.getContentResolver().delete(uri, null, null);
        } else if ("screen_timeout".equals(id)) written = Settings.System.putString(context.getContentResolver(), key, value);
        else if ("location".equals(id)) written = Settings.Secure.putString(context.getContentResolver(), key, value);
        else written = Settings.Global.putString(context.getContentResolver(), key, value);
        if (!written) throw new IllegalStateException("系统未接受此设置");
        // 设置观察者可能异步更新服务，只在本次操作内短暂等待真实状态。
        long end = SystemClock.elapsedRealtime() + 3000;
        while (!verified(id, value) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        if (!verified(id, value)) throw new IllegalStateException("设置未生效或已被系统调整，请刷新状态");
    }
    private boolean verified(String id, String value) {
        if (!Objects.equals(raw(id), value)) return false;
        if (value == null) return true;
        if ("battery_saver".equals(id)) return enabled(id) == "1".equals(value);
        if ("location".equals(id)) return enabled(id) == !"0".equals(value);
        return true;
    }
    private JSONObject result(boolean ok, String message) throws Exception {
        preferences.edit().putString("lastOperation", message).apply();
        return object("ok", ok, "message", message, "state", status());
    }
    synchronized JSONObject change(String id, boolean enabled) throws Exception {
        if ("screen_timeout".equals(id)) return result(false, "自动息屏请使用时长选项");
        String value = "location".equals(id) ? (enabled ? "3" : "0") : (enabled ? "1" : "0");
        return changeRaw(id, value);
    }
    synchronized JSONObject setTimeout(long milliseconds) throws Exception {
        if (milliseconds != 60000 && milliseconds != 120000 && milliseconds != 300000 && milliseconds != 600000)
            return result(false, "不支持的自动息屏时长");
        return changeRaw("screen_timeout", Long.toString(milliseconds));
    }
    private JSONObject changeRaw(String id, String value) throws Exception {
        try {
            checkWritable(id);
            JSONObject saved = baseline(); remember(saved, id); saveBaseline(saved);
            write(id, value);
            return result(true, "已更新" + title(id));
        } catch (Exception e) { return result(false, "操作未完成：" + e.getMessage()); }
    }
    synchronized JSONObject recommended() throws Exception {
        String[] ids = {"screen_timeout", "wifi_scan", "bluetooth_scan"};
        try {
            JSONObject saved = baseline();
            for (String id : ids) { checkWritable(id); remember(saved, id); }
            saveBaseline(saved);
            write("screen_timeout", "120000"); write("wifi_scan", "0"); write("bluetooth_scan", "0");
            return result(true, "已将自动息屏设为 2 分钟，并关闭两项后台扫描");
        } catch (Exception e) { return result(false, "推荐设置未全部完成，可恢复原设置：" + e.getMessage()); }
    }
    synchronized JSONObject restore() throws Exception {
        JSONObject saved = baseline(); List<String> ids = new ArrayList<>(), failures = new ArrayList<>();
        Iterator<String> keys = saved.keys(); while (keys.hasNext()) ids.add(keys.next());
        for (String id : ids) {
            try {
                write(id, saved.isNull(id) ? null : saved.getString(id));
                saved.remove(id); saveBaseline(saved);
            } catch (Exception e) { failures.add(title(id) + "：" + e.getMessage()); }
        }
        return result(failures.isEmpty(), failures.isEmpty() ? "已恢复原设置" : "部分项目未恢复，原值备份已保留：" + String.join("；", failures));
    }
}
