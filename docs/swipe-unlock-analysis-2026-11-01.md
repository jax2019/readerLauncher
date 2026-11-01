# Neo 3 Ultra 上滑解锁与待机功耗核查

核查日期：2026-11-01。对象为当前连接的 Neo 3 Ultra（RM06L-CKDW）及其已安装固件。本次读取原厂界面、系统配置与 APK 实现，没有更改解锁或电源设置。

## 开关位置与本机状态

原厂设置程序为部分机型提供“掌阅设置 → 电池 → 休眠上滑解锁”。本机电池页实际只有电池百分比、省电模式、休眠时保留蓝牙、休眠时保留 WLAN，未显示上滑解锁选项。

设置程序的电池页面实现 `G6.d.V` 仅在设备配置 `features.support.slide.unlock=true` 时添加此开关；缺省值为 false。已安装设置 APK 中 `assets/RM06L-CKDW/Neo 3 Ultra/device.properties` 未声明该能力，因此当前固件未对本机开放此界面开关。

该选项读取系统配置 `key_enabled_slide_unlock`，缺省按 0（关闭）处理。实机读取结果为 null，即尚未写入，并非已经开启隐藏的常驻上滑模式。

## 接电时为何仍然能上滑

原厂 SystemUI 的延时处理 `T3.g.handleMessage` 决定是否结束锁屏 DreamService：

- 上滑解锁选项为 1 时，跳过结束锁屏服务。
- `key_disallow_finish_dream=1` 时，也跳过结束。
- 在本机 Android 14 上，默认情况下检测到外部供电时，同样跳过结束。
- 未满足上述保留条件时，该处理会调用 DreamService.finish()。

实机当前报告外部供电为 true；`key_enabled_slide_unlock`、`key_disallow_finish_dream` 和 `disabled_plugged_dream` 都未设置。根据上述实现，接电期间保留锁屏界面符合系统逻辑。接电时可上滑，以及刚锁屏时能响应手势，都不能直接证明拔线后的稳定待机会一直保留该状态。

## 耗电结论与边界

原厂设置程序给“休眠上滑解锁”使用的说明文字为“开启后会增加设备耗电，缩短续航时间”。实现也表明该选项开启会保留锁屏服务，因此不能将它简单视为一个已验证的、零额外功耗的硬件手势功能。

本机当前没有开启该隐藏选项。当前接电观察不足以量化拔线待机功耗，也不能据此断言上滑解锁是这台设备待机耗电的主要原因。本次未做拔线后的长时间待机对照，不提供每天或每小时增加多少电量的估计。

要验证用户看到的行为，应拔掉供电线，锁屏放置一段时间后再检查能否上滑。要量化耗电差异，则需在无线连接、后台应用、屏保和测试时长一致的条件下做离电对照。

## 原始证据

本机诊断目录为 `diagnostics/2026-11-01-swipe-unlock/`：

- `battery-final.xml`、`battery-page.png`：原厂电池页面。
- `relevant-settings.json`、`battery-state.txt`：相关配置与供电状态。
- `neo3-ultra-device.properties`：本机型号的原厂能力配置。
- `swipe-settings.smali`：选项显示条件及耗电说明文字引用。
- `swipe-setting-handler.smali`：开关对应的系统配置读写逻辑。
- `systemui-sleep.smali`：锁屏服务结束条件与接电分支。

核查完成后设备已返回阅桌面。
