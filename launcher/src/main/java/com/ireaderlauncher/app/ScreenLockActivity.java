package com.ireaderlauncher.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/** 系统仅锁屏授权；多看默认使用，掌阅可作为控制组件不可用时的独立锁屏方式。 */
public final class ScreenLockActivity extends Activity {
    private static final int AUTHORIZE = 1;
    private static final String AUTHORIZE_ONLY = "authorize_only";
    private boolean awaitingAuthorization;
    static ComponentName admin(Context c) { return new ComponentName(c, ScreenLockAdminReceiver.class); }
    static boolean authorized(Context c) {
        DevicePolicyManager manager = c.getSystemService(DevicePolicyManager.class);
        return manager != null && manager.isAdminActive(admin(c));
    }
    static void manage(Activity activity) {
        if (!authorized(activity)) {
            Ui.launch(activity, new Intent(activity, ScreenLockActivity.class).putExtra(AUTHORIZE_ONLY, true)); return;
        }
        new AlertDialog.Builder(activity).setTitle("锁屏授权已开启")
                .setMessage("阅桌面仅使用立即锁屏权限。取消后，多看锁屏需重新授权，掌阅恢复使用控制组件；卸载桌面前也可在这里取消。")
                .setPositiveButton("保留授权", null)
                .setNegativeButton("取消锁屏授权", (dialog, which) -> {
                    activity.getSystemService(DevicePolicyManager.class).removeActiveAdmin(admin(activity));
                    Toast.makeText(activity, "已提交取消锁屏授权", Toast.LENGTH_SHORT).show();
                }).show();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!DeviceProfile.current().hasScreenLock()) { finish(); return; }
        awaitingAuthorization = state != null && state.getBoolean("awaiting_authorization");
        if (awaitingAuthorization) return;
        if (authorized(this)) { complete(); return; }
        try {
            awaitingAuthorization = true;
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅用于点击桌面锁屏按钮时立即锁屏，不清除数据、不管理密码。可在桌面设置中取消授权。");
            startActivityForResult(intent, AUTHORIZE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法打开锁屏授权，请从系统设备管理应用中启用阅桌面锁屏", Toast.LENGTH_LONG).show(); finish();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaiting_authorization", awaitingAuthorization); super.onSaveInstanceState(state);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != AUTHORIZE) return;
        awaitingAuthorization = false;
        if (authorized(this)) complete();
        else { Toast.makeText(this, "未授予锁屏权限，未改变屏幕状态", Toast.LENGTH_SHORT).show(); finish(); }
    }
    private void complete() {
        if (getIntent().getBooleanExtra(AUTHORIZE_ONLY, false)) { finish(); return; }
        try {
            getSystemService(DevicePolicyManager.class).lockNow();
            // 给系统短暂完成息屏的时间；不使用唤醒锁，也不修改任何省电配置。
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (getSystemService(PowerManager.class).isInteractive())
                    Toast.makeText(this, "系统尚未息屏，请重试或使用电源键", Toast.LENGTH_LONG).show();
                finish();
            }, 600);
        } catch (RuntimeException e) {
            Toast.makeText(this, "锁屏失败，请检查锁屏授权后重试", Toast.LENGTH_LONG).show(); finish();
        }
    }
}
