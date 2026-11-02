package com.ireaderlauncher.app;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** 模拟控制服务停用，验证真实首页操作；结束时恢复组件、授权和屏幕状态。 */
final class LockFallbackSmoke {
    private static final String SERVICE = "com.ireaderlauncher.powerbridge/.PowerService";
    private final Instrumentation instrumentation;
    private final Context context;
    LockFallbackSmoke(Instrumentation instrumentation) {
        this.instrumentation = instrumentation; context = instrumentation.getTargetContext();
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void waitFor(BooleanSupplier ready, String message) {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        check(ready.getAsBoolean(), message);
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return out.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }
    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text))
            if (text.contentEquals(n.getText() == null ? "" : n.getText()) && n.isVisibleToUser()) return n;
        return null;
    }
    private void click(String text) {
        waitFor(() -> {
            AccessibilityNodeInfo button = node(text);
            return button != null && button.isEnabled();
        }, "找不到可用按钮：" + text);
        AccessibilityNodeInfo button = node(text);
        check(button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "点击失败：" + text);
    }
    private void home() throws Exception {
        shell("input keyevent KEYCODE_WAKEUP");
        shell("am start -W -n com.ireaderlauncher.app/.MainActivity");
        waitFor(() -> node("锁屏") != null, "未返回桌面");
    }
    void run() throws Exception {
        check(DeviceProfile.current().powerControls, "仅在已适配掌阅上测试控制组件故障");
        ComponentName service = ComponentName.unflattenFromString(SERVICE);
        PackageManager pm = context.getPackageManager();
        int componentBefore = pm.getComponentEnabledSetting(service);
        check(componentBefore >= 0 && componentBefore <= 2, "组件原状态不适合测试");
        PowerManager power = context.getSystemService(PowerManager.class);
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        boolean authorizedBefore = ScreenLockActivity.authorized(context), awakeBefore = power.isInteractive();
        try {
            shell("pm disable --user 0 " + SERVICE);
            check(pm.getComponentEnabledSetting(service) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED, "模拟组件停用失败");
            if (!authorizedBefore) {
                home(); click("锁屏");
                waitFor(() -> node("锁屏未完成") != null, "缺少可恢复的失败提示");
                AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                check(!root.findAccessibilityNodeInfosByText("锁屏控制组件已停用").isEmpty(), "没有区分停用与未安装");
                click("使用系统锁屏");
                waitFor(() -> node("锁定屏幕") != null, "没有打开系统锁屏授权页");
                shell("input keyevent KEYCODE_BACK");
                waitFor(() -> node("锁屏") != null, "取消授权后未返回首页");
                check(!ScreenLockActivity.authorized(context) && power.isInteractive(), "取消授权意外锁屏或激活授权");
                // 临时授权只用于自动验收，不替用户保留授权选择。
                shell("dpm set-active-admin --user 0 com.ireaderlauncher.app/.ScreenLockAdminReceiver");
                waitFor(() -> ScreenLockActivity.authorized(context), "临时锁屏授权未生效");
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                home(); click("锁屏");
                waitFor(() -> !power.isInteractive(), "控制组件停用时系统锁屏未生效");
                policy.lockNow(); SystemClock.sleep(300);
                check(!power.isInteractive(), "重复锁屏意外唤醒");
            }
        } finally {
            if (!authorizedBefore && ScreenLockActivity.authorized(context)) {
                policy.removeActiveAdmin(ScreenLockActivity.admin(context));
                waitFor(() -> !ScreenLockActivity.authorized(context), "临时锁屏授权未恢复");
            }
            String command = componentBefore == 0 ? "default-state" : componentBefore == 1 ? "enable" : "disable";
            shell("pm " + command + " --user 0 " + SERVICE);
            check(pm.getComponentEnabledSetting(service) == componentBefore, "组件原状态未恢复");
            shell(awakeBefore ? "input keyevent KEYCODE_WAKEUP" : "input keyevent KEYCODE_SLEEP");
            waitFor(() -> power.isInteractive() == awakeBefore, "屏幕原状态未恢复");
        }
    }
}
