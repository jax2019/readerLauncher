package com.ireaderlauncher.powerbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/** 可操作范围固定在代码中，外部输入不能扩展包名或命令。 */
public final class Policy {
    private Policy() {}
    public static final Map<String, String> PACKAGES = new LinkedHashMap<>();
    static {
        PACKAGES.put("ai", "com.szzy.ireader.ink.ai");
        PACKAGES.put("assistant", "com.szzy.ireader.smartassistant");
        PACKAGES.put("market", "com.szzy.ireader.ink.appmarket");
        PACKAGES.put("music", "com.szzy.ireader.ink.musicplayer");
        PACKAGES.put("tts", "com.szzy.ireader.ink.tts");
        PACKAGES.put("dictionary", "com.szzy.ireader.ink.translatelexicon");
        // 官方 HOME 与书城并非同一个独立应用，按真实包边界分别控制。
        PACKAGES.put("official_launcher", "com.szzy.ireader.ink.launcher");
        PACKAGES.put("official_reader", "com.zhangyue.iReader.Eink");
        PACKAGES.put("migration", "com.szzy.ireader.ink.wifidirect");
        PACKAGES.put("reflow", "com.szzy.ireader.ink.office.reflow");
        PACKAGES.put("printing", "com.android.printspooler");
    }
    public static boolean requiresLauncherHome(String id) {
        return "official_launcher".equals(id) || "official_reader".equals(id);
    }
    public static boolean allowed(String id) {
        return id != null && (PACKAGES.containsKey(id)
                || id.equals("logger") || id.equals("logger_boot")
                || id.equals("light_doze") || id.equals("deep_doze")
                || id.equals("wifi") || id.equals("bluetooth") || id.equals("battery_saver")
                || id.equals("location") || id.equals("wifi_scan") || id.equals("bluetooth_scan"));
    }
    public static String[] recommendations() { return new String[]{"logger", "logger_boot", "light_doze", "deep_doze"}; }
    public static boolean recommendedValue(String id) { return id.equals("light_doze"); }
}
