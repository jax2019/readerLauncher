package com.ireaderlauncher.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.ireaderlauncher.bridge.IPowerBridge;

/** 独立 UID 的测试应用，验证桌面以外的调用者不能获得控制。 */
public final class ProbeActivity extends Activity {
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> {
                try { IPowerBridge.Stub.asInterface(binder).status(); Log.e("IReaderProbe", "FAIL: unauthorized caller accepted"); }
                catch (SecurityException expected) { Log.i("IReaderProbe", "PASS: unauthorized caller rejected"); }
                catch (Exception e) { Log.e("IReaderProbe", "FAIL: " + e); }
                // 新增锁屏入口同样必须核验调用方，不能让其他应用远程息屏。
                try { IPowerBridge.Stub.asInterface(binder).lockScreen(); Log.e("IReaderProbe", "FAIL: unauthorized lock accepted"); }
                catch (SecurityException expected) { Log.i("IReaderProbe", "PASS: unauthorized lock rejected"); }
                catch (Exception e) { Log.e("IReaderProbe", "FAIL: lock " + e); }
                runOnUiThread(() -> { if (bound) { unbindService(connection); bound = false; } finish(); });
            }).start();
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            bound = bindService(new Intent().setClassName("com.ireaderlauncher.powerbridge", "com.ireaderlauncher.powerbridge.PowerService"), connection, Context.BIND_AUTO_CREATE);
            if (!bound) { Log.e("IReaderProbe", "FAIL: bind returned false"); finish(); }
        } catch (SecurityException e) { Log.i("IReaderProbe", "PASS: unauthorized caller rejected at bind"); finish(); }
    }
}
