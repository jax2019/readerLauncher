package com.ireaderlauncher.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import com.ireaderlauncher.bridge.IPowerBridge;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private TextView date;
    private boolean listening;
    private Button lockButton;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServiceConnection lockConnection;
    private Runnable lockTimeout;
    private boolean lockBound;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateDate();
        }
    };
    @Override public void onCreate(Bundle state) { super.onCreate(state); }
    @Override protected void onResume() {
        super.onResume(); render();
        // 首页仅显示日期，只在桌面可见时监听时间广播，不注册定时器或闹钟。
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_DATE_CHANGED); f.addAction(Intent.ACTION_TIME_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        listening = true;
    }
    @Override protected void onPause() { if (listening) { unregisterReceiver(receiver); listening = false; } super.onPause(); }
    @Override protected void onDestroy() { finishLock(lockConnection, null); super.onDestroy(); }
    @Override public void onBackPressed() { /* HOME 页面保持显示，其他页面使用正常返回行为。 */ }
    private void updateDate() { if (date != null) date.setText(new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(new Date())); }
    private void render() {
        LinearLayout root = Ui.screen(this);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        date = Ui.text(this, "", 16, false); date.setTextColor(Ui.MUTED); updateDate();
        header.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header); Ui.space(this, root, 30);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.text(this, "阅桌面", 32, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(Ui.button(this, "编辑应用", false, () -> Ui.launch(this, new Intent(this, AppsActivity.class))),
                new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 50)));
        root.addView(heading); Ui.space(this, root, 28);
        List<ResolveInfo> installed = AppCatalog.apps(this);
        ResolveInfo weread = null;
        for (ResolveInfo app : installed) if (app.activityInfo.packageName.equals(AppCatalog.WEREAD)) weread = app;
        // 微信读书与用户勾选的应用共用同一网格和图标尺寸，固定排在第一位。
        List<View> icons = new ArrayList<>();
        icons.add(appIcon(AppCatalog.WEREAD, "微信读书", weread == null ? getPackageManager().getDefaultActivityIcon() : weread.loadIcon(getPackageManager())));
        Set<String> favorites = AppCatalog.favorites(this);
        for (ResolveInfo r : installed) {
            String pkg = r.activityInfo.packageName;
            if (!favorites.contains(pkg) || pkg.equals(AppCatalog.WEREAD)) continue;
            icons.add(appIcon(pkg, r.loadLabel(getPackageManager()).toString(), r.loadIcon(getPackageManager())));
        }
        Ui.grid(this, root, icons, getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3, 158);
        Ui.space(this, root, 32);
        addRow(root, "省电助手", () -> Ui.launch(this, new Intent(this, PowerActivity.class)),
                "设置", () -> Ui.launch(this, new Intent(this, SettingsActivity.class)));
        Ui.space(this, root, 12);
        addRow(root, "官方桌面", () -> Ui.officialHome(this), "全部应用", () -> Ui.launch(this, new Intent(this, AppsActivity.class)));
        // 占满剩余空白，让按钮底部居中；底边距沿用页面留白，应用较多时仍可滚动到按钮。
        LinearLayout footer = Ui.column(this); footer.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        footer.setMinimumHeight(Ui.dp(this, 144));
        footer.setPadding(0, Ui.dp(this, 24), 0, 0);
        lockButton = Ui.button(this, "锁屏", false, this::lockScreen);
        lockButton.setEnabled(lockConnection == null);
        footer.addView(lockButton, new LinearLayout.LayoutParams(Ui.dp(this, 160), Ui.dp(this, 64)));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2, 1));
    }
    private void lockScreen() {
        if (lockConnection != null) return;
        lockButton.setEnabled(false);
        // 仅在点击时短暂绑定控制组件，命令完成即释放，不常驻监听或轮询。
        ServiceConnection request = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (lockConnection != this) return;
                IPowerBridge bridge = IPowerBridge.Stub.asInterface(service);
                new Thread(() -> {
                    String error = null;
                    try {
                        JSONObject result = new JSONObject(bridge.lockScreen());
                        if (!result.optBoolean("ok")) error = result.optString("message", "锁屏未成功，请重试");
                    } catch (Exception e) {
                        error = "锁屏失败，请确认控制组件已更新后重试";
                    }
                    String message = error;
                    main.post(() -> finishLock(this, message));
                }, "screen-lock").start();
            }
            @Override public void onServiceDisconnected(ComponentName name) { finishLock(this, "控制组件已断开，请重试"); }
            @Override public void onBindingDied(ComponentName name) { finishLock(this, "控制组件已更新，请重试"); }
            @Override public void onNullBinding(ComponentName name) { finishLock(this, "控制组件暂不可用"); }
        };
        lockConnection = request;
        lockTimeout = () -> finishLock(request, "锁屏连接超时，请重试");
        main.postDelayed(lockTimeout, 20000);
        try {
            lockBound = bindService(new Intent().setClassName("com.ireaderlauncher.powerbridge",
                    "com.ireaderlauncher.powerbridge.PowerService"), request, Context.BIND_AUTO_CREATE);
            if (!lockBound) finishLock(request, "尚未安装控制组件，请使用项目的设备安装工具");
        } catch (Exception e) { finishLock(request, "控制组件无法连接，请重试"); }
    }
    private void finishLock(ServiceConnection request, String error) {
        // 忽略超时或页面销毁后到达的旧回调，避免影响下一次点击。
        if (request == null || lockConnection != request) return;
        if (lockTimeout != null) main.removeCallbacks(lockTimeout);
        if (lockBound) unbindService(request);
        lockBound = false; lockConnection = null; lockTimeout = null;
        if (lockButton != null) lockButton.setEnabled(true);
        if (error != null && !isFinishing() && !isDestroyed()) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }
    private View appIcon(String pkg, String name, Drawable drawable) {
        LinearLayout tile = Ui.column(this); tile.setGravity(Gravity.CENTER);
        tile.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 10));
        tile.setBackground(Ui.background(this, 0xffffffff, Ui.LINE));
        ImageView icon = new ImageView(this); icon.setImageDrawable(drawable); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72))); Ui.space(this, tile, 10);
        TextView label = Ui.text(this, name, 17, false); label.setGravity(Gravity.CENTER);
        label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(label);
        tile.setContentDescription("打开" + name); tile.setFocusable(true); tile.setClickable(true);
        tile.setOnClickListener(v -> openApp(pkg)); return tile;
    }
    private void addRow(LinearLayout root, String left, Runnable first, String right, Runnable second) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1); a.setMarginEnd(Ui.dp(this, 12));
        row.addView(Ui.button(this, left, false, first), a);
        row.addView(Ui.button(this, right, false, second), new LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1)); root.addView(row);
    }
    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) Ui.launch(this, i);
        else new android.app.AlertDialog.Builder(this).setMessage("应用尚未安装或已停用，请在应用管理中检查。").setPositiveButton("知道了", null).show();
    }
}
