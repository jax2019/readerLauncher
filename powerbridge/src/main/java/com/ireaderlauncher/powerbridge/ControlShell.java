package com.ireaderlauncher.powerbridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 以组件已有的系统 shell 身份执行固定操作，完成后退出。 */
final class ControlShell {
    static String run(String command) throws Exception {
        Process p = new ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() < 128 * 1024) out.write(buffer, 0, n);
                }
            } catch (Exception ignored) { /* 进程超时退出时关闭读取。 */ }
        }, "power-output");
        reader.start();
        if (!p.waitFor(12, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(1000);
            throw new IllegalStateException("系统操作超时，请刷新状态后重试");
        }
        reader.join(1000);
        String result = out.toString(StandardCharsets.UTF_8.name()).trim();
        if (p.exitValue() != 0) throw new IllegalStateException("系统命令未成功：" + result);
        return result;
    }
}
