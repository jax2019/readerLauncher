package com.ireaderlauncher.powerbridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.IBinder;
import com.ireaderlauncher.bridge.IPowerBridge;
import java.security.MessageDigest;

public final class PowerService extends Service {
    private Engine engine;
    @Override public void onCreate() { super.onCreate(); engine = Engine.get(this); }

    /** 每次 Binder 请求都核验实际调用 UID 及桌面的独立签名。 */
    private void authorize() {
        int uid = Binder.getCallingUid();
        try {
            if (getPackageManager().getPackageUid("com.ireaderlauncher.app", 0) != uid)
                throw new SecurityException("调用者未授权");
            Signature[] signatures = getPackageManager().getPackageInfo("com.ireaderlauncher.app",
                    PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners();
            if (signatures.length != 1) throw new SecurityException("桌面签名无效");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!MessageDigest.isEqual(hex.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    BuildConfig.LAUNCHER_CERT_SHA256.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new SecurityException("桌面签名不匹配");
        } catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new SecurityException("无法验证桌面身份", e); }
    }

    private final IPowerBridge.Stub binder = new IPowerBridge.Stub() {
        @Override public String status() { authorize(); return engine.status().toString(); }
        @Override public String setEnabled(String id, boolean enabled) { authorize(); return engine.change(id, enabled).toString(); }
        @Override public String recommended() { authorize(); return engine.recommended().toString(); }
        @Override public String restore() { authorize(); return engine.restore().toString(); }
        @Override public String setDozeMode(String mode) { authorize(); return engine.changeDozeMode(mode).toString(); }
        @Override public String lockScreen() { authorize(); return engine.lockScreen().toString(); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
