package com.ireaderlauncher.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.ireaderlauncher.bridge.IPowerBridge;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PowerActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<android.view.View> controls = new ArrayList<>();
    private IPowerBridge bridge;
    private LinearLayout rows;
    private TextView summary, last;
    private boolean bound, busy;
    private Button recommend, restore, refresh;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) { bridge = IPowerBridge.Stub.asInterface(service); call(() -> bridge.status(), false); }
        @Override public void onServiceDisconnected(ComponentName name) { bridge = null; unavailable("控制组件已断开，请返回后重试"); }
        @Override public void onBindingDied(ComponentName name) { bridge = null; unavailable("控制组件已更新，请返回后重新进入"); }
        @Override public void onNullBinding(ComponentName name) { bridge = null; unavailable("控制组件暂不可用"); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.screen(this);
        Ui.title(this, root, "省电助手", "按需开关，修改前自动保存原设置。");
        summary = Ui.text(this, "正在连接控制组件…", 17, true); root.addView(summary); Ui.space(this, root, 16);
        recommend = Ui.button(this, "应用推荐设置", true, () -> call(() -> bridge.recommended(), true));
        root.addView(recommend, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58))); controls.add(recommend);
        Ui.space(this, root, 10);
        TextView explanation = Ui.text(this, "关闭调试日志录制与自启，开启轻度 Doze。", 15, false); explanation.setTextColor(Ui.MUTED); root.addView(explanation);
        Ui.space(this, root, 16);
        LinearLayout actions = new LinearLayout(this);
        refresh = Ui.button(this, "刷新状态", false, () -> call(() -> bridge.status(), false));
        restore = Ui.button(this, "恢复原设置", false, () -> call(() -> bridge.restore(), true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1); lp.setMarginEnd(Ui.dp(this, 10));
        actions.addView(refresh, lp); actions.addView(restore, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1)); root.addView(actions);
        controls.add(refresh); controls.add(restore);
        Ui.space(this, root, 16); last = Ui.text(this, "", 14, false); last.setTextColor(Ui.MUTED); root.addView(last);
        Ui.space(this, root, 22); rows = Ui.column(this); root.addView(rows); setBusy(true);
    }
    @Override protected void onStart() {
        super.onStart();
        try {
            bound = bindService(new Intent().setClassName("com.ireaderlauncher.powerbridge", "com.ireaderlauncher.powerbridge.PowerService"), connection, Context.BIND_AUTO_CREATE);
            if (!bound) unavailable("尚未安装控制组件，请使用项目的设备安装工具");
        } catch (Exception e) { unavailable("控制组件无法连接：" + e.getMessage()); }
    }
    @Override protected void onStop() { if (bound) { unbindService(connection); bound = false; } bridge = null; super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    private interface Task { String run() throws Exception; }
    private void call(Task task, boolean operation) {
        if (bridge == null || busy && operation) return;
        setBusy(true); summary.setText(operation ? "正在应用并核验设置…" : "正在读取系统状态…");
        worker.execute(() -> {
            try {
                JSONObject response = new JSONObject(task.run());
                JSONObject state = operation ? response.getJSONObject("state") : response;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    render(state); setBusy(false);
                    if (operation) {
                        String text = response.optString("message");
                        if (response.optBoolean("ok")) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                        else new android.app.AlertDialog.Builder(this).setTitle("设置尚未完成").setMessage(text).setPositiveButton("知道了", null).show();
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) unavailable("无法读取控制状态：" + e.getMessage()); }); }
        });
    }
    private void unavailable(String text) { summary.setText(text); setBusy(true); }
    private void setBusy(boolean value) {
        busy = value;
        for (android.view.View v : controls) v.setEnabled(!value && !Boolean.TRUE.equals(v.getTag()));
    }
    private void render(JSONObject state) {
        rows.removeAllViews();
        while (controls.size() > 3) controls.remove(controls.size() - 1);
        summary.setText(state.optBoolean("controlReady") ? "控制已就绪 · 重启后可直接使用" : "控制权限暂不可用");
        recommend.setTag(!state.optBoolean("controlReady")); restore.setTag(!state.optBoolean("hasBaseline"));
        last.setText(state.optString("lastOperation"));
        JSONArray options = state.optJSONArray("options"); if (options == null) return;
        for (int i = 0; i < options.length(); i++) {
            JSONObject o = options.optJSONObject(i); if (o == null) continue;
            String id = o.optString("id");
            if (id.equals("deep_doze")) continue;
            if (id.equals("light_doze")) { addDoze(options, o); continue; }
            if (id.equals("logger") || id.equals("wifi") || id.equals("ai")) {
                TextView group = Ui.text(this, id.equals("logger") ? "日志与待机" : id.equals("wifi") ? "连接与定位" : "应用优化", 19, true);
                rows.addView(group);
                if (id.equals("ai")) {
                    Ui.space(this, rows, 6);
                    TextView help = Ui.text(this, "开启优化 = 停用应用，限制组件启动；关闭优化 = 重新启用。重启后仍有效，数据保留。系统常驻进程可能保留，请留意各项说明。", 14, false);
                    help.setTextColor(Ui.MUTED); rows.addView(help);
                }
                Ui.space(this, rows, 12);
            }
            LinearLayout panel = new LinearLayout(this); panel.setGravity(android.view.Gravity.CENTER_VERTICAL);
            panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16));
            panel.setBackground(Ui.background(this, 0xffffffff, Ui.LINE));
            LinearLayout label = Ui.column(this); label.addView(Ui.text(this, o.optString("title"), 18, true)); Ui.space(this, label, 5);
            TextView desc = Ui.text(this, o.has("error") ? "状态不可用：" + o.optString("error") : o.optString("description"), 14, false);
            desc.setTextColor(Ui.MUTED); label.addView(desc); panel.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            boolean appOptimization = o.optBoolean("appOptimization");
            boolean checked = appOptimization ? o.optBoolean("optimized") : o.optBoolean("enabled");
            Switch toggle = new Switch(this);
            toggle.setContentDescription(o.optString("title") + (appOptimization ? "应用优化" : "")); toggle.setChecked(checked);
            // 状态文字放在滑块外侧，避免墨水屏上文字与滑块重叠。
            toggle.setShowText(false);
            toggle.setText(appOptimization ? (o.has("error") ? "未知" : checked ? "已优化" : "未优化") : checked ? "开" : "关");
            toggle.setTextSize(16); toggle.setTextColor(Ui.INK); toggle.setSwitchPadding(Ui.dp(this, 8));
            toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.INK));
            toggle.setTag(!o.optBoolean("supported"));
            toggle.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), 0, Ui.dp(this, 10));
            // 应用开关表示“是否优化”，与系统应用的 enabled 值方向相反。
            toggle.setOnCheckedChangeListener((button, selected) -> call(() -> bridge.setEnabled(id, appOptimization ? !selected : selected), true));
            panel.addView(toggle); controls.add(toggle); rows.addView(panel); Ui.space(this, rows, 10);
        }
        Ui.space(this, rows, 12);
        TextView note = Ui.text(this, "停用官方书城与阅读服务时，官方桌面入口也会暂停，可在这里关闭优化恢复。微信读书、系统设置、输入法及屏幕和电源基础服务不在停用范围内。新增项目不会自动开启，也不纳入一键推荐。", 14, false); note.setTextColor(Ui.MUTED); rows.addView(note);
    }
    private void addDoze(JSONArray options, JSONObject light) {
        JSONObject deep = null;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && option.optString("id").equals("deep_doze")) deep = option;
        }
        boolean available = light.optBoolean("supported") && deep != null && deep.optBoolean("supported");
        String current = deep != null && deep.optBoolean("enabled") ? "deep" : light.optBoolean("enabled") ? "light" : "off";
        LinearLayout panel = Ui.column(this);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        panel.setBackground(Ui.background(this, 0xffffffff, Ui.LINE));
        panel.addView(Ui.text(this, "待机省电（Doze）", 18, true)); Ui.space(this, panel, 6);
        String description = "息屏后限制后台活动；重度限制更严格，可能延迟同步。";
        if (!available) description = "状态不可用：" + light.optString("error", deep == null ? "未读取到深度状态" : deep.optString("error"));
        TextView desc = Ui.text(this, description, 14, false); desc.setTextColor(Ui.MUTED); panel.addView(desc);
        Ui.space(this, panel, 14);
        LinearLayout modes = new LinearLayout(this);
        String[] ids = {"off", "light", "deep"}, labels = {"关", "轻度", "重度"};
        for (int i = 0; i < ids.length; i++) {
            String mode = ids[i]; boolean selected = available && mode.equals(current);
            Button button = Ui.button(this, labels[i], selected, () -> call(() -> bridge.setDozeMode(mode), true));
            button.setSelected(selected); button.setContentDescription("Doze：" + labels[i]);
            button.setTag(!available || selected); controls.add(button);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1);
            if (i < ids.length - 1) lp.setMarginEnd(Ui.dp(this, 10)); modes.addView(button, lp);
        }
        panel.addView(modes); rows.addView(panel); Ui.space(this, rows, 10);
    }
}
