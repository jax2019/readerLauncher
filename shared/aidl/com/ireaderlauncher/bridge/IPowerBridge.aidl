package com.ireaderlauncher.bridge;

// 仅提供固定的省电与锁屏操作，不暴露任意 shell 命令执行接口。
interface IPowerBridge {
    String status();
    String setEnabled(String optionId, boolean enabled);
    String recommended();
    String restore();
    String setDozeMode(String mode);
    String lockScreen();
}
