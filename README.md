# 阅桌面 IReaderLauncher

为这台掌阅 Neo 3 Ultra（RM06L-CKDW，Android 14）制作的原生极简桌面。微信读书与勾选的应用使用同一图标网格，并可直接进入省电助手、官方桌面与设备设置。

## 使用

- **默认桌面**：安装工具将阅桌面设为默认 HOME；设置中也提供“设置默认桌面”。
- **官方桌面**：主页和设置均提供入口，显式打开原厂 Activity。按 Home 返回阅桌面；官方阅读服务已停用时，会提示到省电助手恢复。
- **桌面应用**：点击“编辑应用”或“全部应用”，勾选要放到主页的应用；微信读书固定排在图标网格首位，点击图标即可打开应用。
- **锁屏**：点击桌面底部居中的“锁屏”，即可进入系统锁屏待机画面；使用设备电源键正常唤醒。按钮仅在点击时连接控制组件，操作完成即释放，不修改省电设置。
- **设备设置**：使用两列大按钮，提供掌阅设置、Android 系统设置、开发者选项、阅读灯、应用显示与刷新入口。
- **省电助手**：打开时读取真实状态，支持单独开关、“应用推荐设置”和“恢复原设置”。

桌面没有联网推荐、动画、后台轮询或唤醒锁。主页仅展示日期，不显示电量；日期只在桌面可见时响应系统时间广播。

## 省电助手

“应用推荐设置”只修改三项：关闭 MTK 调试日志录制、关闭日志开机自启、开启轻度 Doze。

Doze 是 Android 的后台节电机制，息屏且满足系统条件时限制后台任务与网络，并在维护窗口允许工作。开启 Doze 只是允许系统使用该策略，不会立即强制休眠；下载和同步可能延后。界面合并为一个“待机省电（Doze）”选项：

| 档位 | 行为 |
| --- | --- |
| 关 | 关闭轻度和深度 Doze |
| 轻度 | 开启轻度、关闭深度，推荐使用 |
| 重度 | 同时开启轻度和深度阶段，后台限制更严格 |

切换档位时会核验两个系统状态，任一步骤失败会尝试回到切换前的档位。重启恢复两项已选择的状态。“应用推荐设置”会切换为轻度，包括从重度降回轻度。

可手动控制的项目：

| 类别 | 选项 |
| --- | --- |
| 日志与待机 | 调试日志录制、日志开机自启、Doze 三档、系统省电模式 |
| 连接与定位 | Wi-Fi、蓝牙、定位（GPS）、Wi-Fi 后台扫描、蓝牙后台扫描 |
| 应用优化 | 掌阅 AI、智能助手、应用市场、音乐播放器、语音朗读、翻译词典、官方桌面组件、官方书城与阅读服务、换机助手、Office 文档重排、系统打印服务 |

充电期间系统省电模式开关会禁用；蓝牙翻页器需要开启蓝牙。

定位开关控制系统定位总开关，包括 GPS 与网络定位，关闭后需要定位的功能会受影响。两项后台扫描控制无线主开关关闭后仍可用于定位的扫描，关闭扫描不等于关闭正常的 Wi-Fi 或蓝牙连接。这三项均读取系统实际状态，首次修改保存原值，支持“恢复原设置”；扫描配置最初不存在时，恢复也会还原为未设置。这些项目只由用户按需操作，不纳入一键推荐。

### 应用优化（冻结）

应用优化开关统一采用正向语义：

| 开关 | 实际效果 |
| --- | --- |
| 开启，显示“已优化” | 使用系统包管理器停用应用（`pm disable-user --user 0`），即冻结。限制应用组件启动，重启后保持停用；相关应用入口可能隐藏。系统常驻进程存在例外，见下文。 |
| 关闭，显示“未优化” | 重新启用应用（`pm enable --user 0`），允许正常使用与启动。 |

这与只杀一次进程不同：一次停止进程后，应用仍可能被广播、其他组件或用户操作重新启动；包停用会持续限制该应用的普通组件启动。APK 和应用数据保留，关闭优化后可继续使用。

系统标记为 `persistent` 的应用需要单独说明：实测音乐播放器在包已停用时仍保留常驻进程，`force-stop` 也未使其退出。因此“已优化”代表包已停用，不代表所有进程已退出。界面会根据真实清单标记提示此限制；不通过修改系统分区或按共享 UID 杀进程强行处理。

冻结期间对应功能不可用，例如冻结词典会影响查词，冻结语音朗读可能影响调用它的阅读功能。应用优化只控制列出的独立应用，不会按共享 UID 整体限制其他系统服务。

界面直接读取真实启用状态：旧版已经停用的应用会显示为“已优化”，升级不会自动重新启用它们。“恢复原设置”还会精确还原最初保存的启用状态（包括清单默认状态）。一键推荐只调整日志与 Doze，不会替用户批量冻结这些应用。

每项首次修改之前，控制组件会保存原始状态；推荐设置会在执行前完整保存相关原值。厂商启停日志命令会连带改变自启，组件会补回自启状态，让两个开关独立工作。应用项目还保存原始 PackageManager 状态，因此恢复时会区分“清单默认”和“显式启用”。部分失败会保留尚未恢复的备份并显示具体结果。备份位于设备内的控制组件私有数据中，卸载或清除其数据会丢失备份。

新增项目的实际边界如下，均由用户手动选择，不会在升级或一键推荐时自动开启：

| 项目 | 停用范围与影响 |
| --- | --- |
| 官方桌面组件 | 独立的 `com.szzy.ireader.ink.launcher`，含 iReader 桌面、小组件与通知监听；主页上的官方书城桌面入口属于下面的阅读主包。 |
| 官方书城与阅读服务 | `com.zhangyue.iReader.Eink` 整包，官方桌面入口、书城、本地阅读、云盘、文件导入及相关数据接口一起停用，不能伪装成两个独立开关。 |
| 换机助手 | 停用设备迁移及相关后台组件，不关闭 Wi-Fi。 |
| Office 文档重排 | 停用掌阅文档转换与重排，使用 Word 等文档前需恢复。 |
| 系统打印服务 | 停用打印任务及打印后台，也会影响通过系统打印转 PDF。 |

控制端在停用两个官方桌面相关包前核验阅桌面是否为默认 HOME；未设置时拒绝操作，恢复原设置时也执行此保护。已停用时仍可随时关闭优化重新启用。主页的官方桌面入口会给出恢复路径，不会自动解冻。

阅读主包包含系统数据和刷新计数接口；短时实测停用后微信读书、系统设置、阅读灯和刷新设置仍可打开，但不能据此保证所有厂商功能均不受影响。遇到阅读或设备功能异常，先关闭该项优化。保留基础资源包 `com.szzy.ireader.ims`、输入法、屏幕、电源、SystemUI 以及设置；不对 UID 1000 整体断网或杀进程。系统升级包具有系统常驻标记，本轮也保留。

## 构建

需要 Python 3、JDK 17、Android SDK Platform 35、Build Tools 35.0.0、platform-tools，以及 OpenSSL。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
python3 tools/build.py
```

默认使用官方 SDK 工具直接构建，不需要下载 Maven 依赖。首次构建会获取与目标固件匹配的 AOSP 公开开发证书，并在 `.local/` 生成桌面的独立签名密钥。密钥与密码文件已排除版本管理；更新设备上的桌面时需要保留它们。

产物：

- `output/IReaderLauncher.apk`：普通应用身份的桌面。
- `output/IReaderPowerBridge.apk`：适配当前固件的省电控制组件。

`python3 tools/build.py --check` 额外构建实机测试 APK，并校验 APK 签名；它不会自动执行设备测试或 Android lint。另提供可选的 `--gradle` 构建路径，需要可用的 Gradle/Android 插件依赖仓库，当前验收采用 SDK 构建。

## 安装与恢复

连接已开启 USB 调试的阅读器后：

```sh
python3 tools/install.py
# 连接多台设备时指定序列号：
python3 tools/install.py --serial LR6PL7WOZ5FE4XVO
```

安装工具会核验设备型号、Android API 和系统 Shell APK 的实际证书，保存原默认桌面，再安装两个 APK 并设为默认 HOME。当前适配仅覆盖已检查的这台固件，不能据此保证其他掌阅机型或其他固件可用。

恢复默认桌面：

```sh
python3 tools/install.py --restore-home
```

该命令只恢复默认桌面。完整撤销时，先在省电助手点击“恢复原设置”，再恢复默认桌面，最后按需卸载两个应用：

```sh
adb uninstall com.ireaderlauncher.app
adb uninstall com.ireaderlauncher.powerbridge
```

## 控制组件与权限

这台 userdebug 固件使用 AOSP 公开 platform 开发证书。独立控制组件用匹配证书加入已有 `android.uid.shell`，直接执行固定系统操作，因此重启后无需电脑重新激活。桌面使用独立私有密钥签名，保持普通应用 UID。

每次 Binder 请求都会检查调用者实际 UID 与桌面的私有签名指纹；不以公开平台证书判断调用者可信。AIDL 只接受固定操作 ID，不接受任意命令或任意包名。没有修改系统分区、开放网络 ADB 或部署常驻 root 守护进程。

开机接收器只恢复用户已选择、重启会丢失的 Doze 状态，执行后退出；日志自启和应用启用状态由系统及厂商应用自身持久保存。

## 验证与耗电观察

初步诊断见 [设备耗电分析](docs/device-analysis-2026-11-01.md)，已完成的实机检查见 [验收记录](docs/verification-2026-11-01.md)。原始诊断和设备截图位于本机 `diagnostics/`，不纳入版本管理。

内置实机测试覆盖默认桌面、官方/开发者入口解析、21 项状态读取、非法参数拒绝、轻度 Doze 切换、可选应用停用、日志开关相互独立、推荐设置和原始状态精确恢复。独立 UID 的测试应用用于确认未授权调用被拒绝。

```sh
python3 tools/build.py --check
adb install -r -t output/IReaderSmokeTest.apk
adb shell am instrument -w \
  com.ireaderlauncher.app.test/com.ireaderlauncher.app.SmokeInstrumentation
adb install -r output/IReaderSecurityProbe.apk
adb shell am start -n com.ireaderlauncher.probe/.ProbeActivity
adb logcat -b all -d -s IReaderProbe:V
```

实机测试会修改并恢复设置，适合在首次应用个人省电配置之前运行；已有备份时会拒绝开始，需先通过省电助手恢复原设置。验证后可卸载 `com.ireaderlauncher.app.test` 和 `com.ireaderlauncher.probe`。

只验证 Doze 三档、保留已有其他省电配置时，可运行：

```sh
adb shell am instrument -w -e suite doze \
  com.ireaderlauncher.app.test/com.ireaderlauncher.app.SmokeInstrumentation
```

此测试验证关闭、轻度、重度、降档和非法档位拒绝，完成后恢复测试前的实际档位，保留最初的恢复备份。

只验证新增五个应用的停用/启用与官方 HOME 保护时，可在官方阅读服务仍启用的状态下运行：

```sh
adb shell am instrument -w -e suite background \
  com.ireaderlauncher.app.test/com.ireaderlauncher.app.SmokeInstrumentation
```

该测试不会全局恢复已有个人设置；测试结束精确恢复五个包的原始启用状态，首次修改新增项目时会保留相应原值备份。

只验证定位与两项后台扫描的开关及状态恢复：

```sh
adb shell am instrument -w -e suite location \
  com.ireaderlauncher.app.test/com.ireaderlauncher.app.SmokeInstrumentation
```

测试结束还原测试前的定位和扫描状态，保留其他省电设置与原始备份。

只验证锁屏、重复请求不唤醒及屏幕状态恢复：

```sh
adb shell am instrument -w -e suite lock \
  com.ireaderlauncher.app.test/com.ireaderlauncher.app.SmokeInstrumentation
```

该测试不修改省电配置；独立 UID 安全探测还会验证未授权的锁屏调用被拒绝。

当前设备连接 USB 充电，状态与日志写盘检查不能证明续航提高了多少。续航应拔线测试：记录合盖待机 8–12 小时，以及固定书籍、前光、网络和翻页方式的阅读耗电；前后条件一致，保留原始电量统计。
