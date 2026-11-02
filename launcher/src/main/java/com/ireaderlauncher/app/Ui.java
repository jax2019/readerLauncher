package com.ireaderlauncher.app;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** 简单的原生布局，避免动画与持续绘制，适合墨水屏。 */
final class Ui {
    static final int INK = 0xff202020, MUTED = 0xff626262, PAPER = 0xfff6f6f2, LINE = 0xffcacac4;
    private Ui() {}
    static int dp(Activity a, float n) { return Math.round(a.getResources().getDisplayMetrics().density * n); }
    static TextView text(Activity a, String s, int size, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s); t.setTextSize(size); t.setTextColor(INK);
        t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(dp(a, 3), 1);
        return t;
    }
    static GradientDrawable background(Activity a, int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(a, 12));
        if (stroke != 0) d.setStroke(dp(a, 1), stroke);
        return d;
    }
    static LinearLayout column(Activity a) {
        LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); return v;
    }
    static LinearLayout screen(Activity a) {
        a.getWindow().setStatusBarColor(PAPER);
        a.getWindow().setNavigationBarColor(PAPER);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(PAPER); scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = column(a);
        content.setPadding(dp(a, 28), dp(a, 20), dp(a, 28), dp(a, 28));
        scroll.addView(content); a.setContentView(scroll); return content;
    }
    static void space(Activity a, LinearLayout parent, int height) {
        View v = new View(a); parent.addView(v, new LinearLayout.LayoutParams(1, dp(a, height)));
    }
    static void title(Activity a, LinearLayout parent, String title, String description) {
        Button back = button(a, "‹  返回", false, a::finish);
        parent.addView(back, new LinearLayout.LayoutParams(-2, dp(a, 46)));
        space(a, parent, 18); parent.addView(text(a, title, 30, true));
        if (!description.isEmpty()) { space(a, parent, 8); TextView t = text(a, description, 16, false); t.setTextColor(MUTED); parent.addView(t); }
        space(a, parent, 24);
    }
    static Button button(Activity a, String label, boolean filled, Runnable click) {
        Button b = new Button(a); b.setText(label); b.setTextSize(17); b.setAllCaps(false);
        b.setTextColor(filled ? 0xffffffff : INK);
        b.setBackground(background(a, filled ? INK : 0xffffffff, filled ? 0 : LINE));
        b.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
        b.setMinimumHeight(dp(a, 52)); b.setElevation(0); b.setStateListAnimator(null);
        b.setOnClickListener(v -> click.run()); return b;
    }
    static void addButton(Activity a, LinearLayout parent, String label, Runnable click) {
        parent.addView(button(a, label, false, click), new LinearLayout.LayoutParams(-1, dp(a, 60))); space(a, parent, 12);
    }
    /** 空余位置也保留格子，避免只有一个入口时被拉成整行宽度。 */
    static void grid(Activity a, LinearLayout parent, List<? extends View> items, int columns, int height) {
        for (int start = 0; start < items.size(); start += columns) {
            LinearLayout row = new LinearLayout(a);
            for (int column = 0; column < columns; column++) {
                int index = start + column;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, height), 1);
                if (column < columns - 1) lp.setMarginEnd(dp(a, 14));
                row.addView(index < items.size() ? items.get(index) : new View(a), lp);
            }
            parent.addView(row);
            if (start + columns < items.size()) space(a, parent, 14);
        }
    }
    static void launch(Activity a, Intent intent) {
        try { a.startActivity(intent); a.overridePendingTransition(0, 0); }
        catch (Exception e) { Toast.makeText(a, "此入口暂时无法打开，请从系统设置进入", Toast.LENGTH_LONG).show(); }
    }
    static void officialHome(Activity a) {
        DeviceProfile profile = DeviceProfile.current();
        ComponentName home = profile.officialHome;
        if (home == null) { Toast.makeText(a, "此设备尚未配置官方桌面入口", Toast.LENGTH_LONG).show(); return; }
        try {
            // 停用期间引导用户恢复，不因点击入口而悄悄改变省电选择。
            if (!a.getPackageManager().getActivityInfo(home, PackageManager.MATCH_DISABLED_COMPONENTS).applicationInfo.enabled) {
                if (!profile.powerControls) {
                    Toast.makeText(a, "官方桌面已停用，请在 Android 系统设置中恢复", Toast.LENGTH_LONG).show(); return;
                }
                new android.app.AlertDialog.Builder(a).setTitle("官方阅读服务已停用")
                        .setMessage("请到省电助手关闭“官方书城与阅读服务”的应用优化，再打开官方桌面。")
                        .setPositiveButton("打开省电助手", (dialog, which) -> launch(a, new Intent(a, PowerActivity.class)))
                        .setNegativeButton("返回", null).show();
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(a, "未找到官方桌面", Toast.LENGTH_LONG).show(); return;
        }
        // 显式打开原厂 Activity，避免 HOME Intent 再次跳回当前默认桌面。
        Intent i = new Intent().setComponent(home);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); launch(a, i);
    }
}
