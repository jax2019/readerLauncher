package com.ireaderlauncher.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppCatalog {
    static final String WEREAD = "com.tencent.weread.eink";
    /** 仅替换已知系统应用的展示名；收藏和启动始终沿用原包名。 */
    static String displayName(String pkg, String original) {
        switch (pkg) {
            case "com.android.settings": return "Android 系统设置";
            case "com.moan.setting": return "多看系统设置";
            case "com.szzy.ireader.settings": return "掌阅系统设置";
            default: return original;
        }
    }
    static String displayName(Context c, ResolveInfo app) {
        return displayName(app.activityInfo.packageName, app.loadLabel(c.getPackageManager()).toString());
    }
    static Set<String> favorites(Context c) {
        return new HashSet<>(c.getSharedPreferences("desktop", Context.MODE_PRIVATE).getStringSet("favorites", new HashSet<>()));
    }
    static void favorites(Context c, Set<String> values) {
        c.getSharedPreferences("desktop", Context.MODE_PRIVATE).edit().putStringSet("favorites", new HashSet<>(values)).apply();
    }
    static List<ResolveInfo> apps(Context c) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = c.getPackageManager().queryIntentActivities(query, 0);
        List<ResolveInfo> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ResolveInfo r : all) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(c.getPackageName()) && !pkg.equals("com.ireaderlauncher.powerbridge") && seen.add(pkg)) result.add(r);
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        result.sort((a, b) -> collator.compare(displayName(c, a), displayName(c, b)));
        return result;
    }
}
