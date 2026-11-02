package com.ireaderlauncher.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import java.util.Set;

public final class AppsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.screen(this);
        Ui.title(this, root, "全部应用", "勾选后与微信读书一起显示在桌面；点击名称打开应用。");
        Set<String> selected = AppCatalog.favorites(this);
        for (ResolveInfo r : AppCatalog.apps(this)) {
            String pkg = r.activityInfo.packageName;
            LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.Button launch = Ui.button(this, AppCatalog.displayName(this, r), false, () -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg); if (i != null) Ui.launch(this, i);
            });
            row.addView(launch, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1));
            CheckBox check = new CheckBox(this); check.setContentDescription("在桌面显示" + launch.getText());
            check.setChecked(pkg.equals(AppCatalog.WEREAD) || selected.contains(pkg)); check.setEnabled(!pkg.equals(AppCatalog.WEREAD));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(pkg); else selected.remove(pkg); AppCatalog.favorites(this, selected);
            });
            row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 58))); root.addView(row); Ui.space(this, root, 10);
        }
    }
}
