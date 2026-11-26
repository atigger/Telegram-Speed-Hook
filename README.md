# Telegram Speed Hook

Telegram 下载与视频加载优化模块，基于 Xposed / LSPosed。支持下载档位、视频分块优先调度、本地实时日志和中英文自动切换。

An Xposed / LSPosed module for Telegram download and video loading optimization, with download profiles, video chunk prioritization, local live logs and a bilingual interface.

**3.3.4** · `versionCode 12` · [下载 / Download](https://github.com/atigger/Telegram-Speed-Hook/releases/tag/v3.3.4)

[中文](#中文说明) · [English](#english) · [更新记录 / Changelog](docs/CHANGELOG.md) · [兼容性 / Compatibility](docs/COMPATIBILITY.md)

## 作者与仓库 / Credits

- 原作者 / Original author: **AraafRoyall**
- 原项目 / Original project: <https://github.com/AraafRoyall/Telegram-Speed-Hook>
- 维护仓库 / Maintained fork: <https://github.com/atigger/Telegram-Speed-Hook>

本仓库基于原项目维护，增加新版 Telegram 适配、下载设置、视频优化和日志功能。

This fork builds on the original project with updated Telegram compatibility, download settings, video optimization and diagnostics.

## 中文说明

### 安装与使用

需要 Android 8.0 或更高版本、Root，以及支持 libxposed API 101+ 和远程配置的框架。目标包名：`org.telegram.messenger`。

1. 从 [Releases](https://github.com/atigger/Telegram-Speed-Hook/releases) 下载 APK 并安装。
2. 在 LSPosed 中启用模块，勾选 Telegram 作用域，按框架提示完成激活。
3. 打开模块，选择下载档位，按需开启视频优化。
4. 点击“保存并停止 Telegram”，授予 Root 权限，再重新打开 Telegram。

保存会中断 Telegram 当前的下载和通话。无法自动停止时，可手动强制停止后重新打开。

### 升级到 3.4.0

3.4.0 改用现代 libxposed API，不再依赖旧式共享配置。覆盖安装后先打开模块，完成旧配置迁移并确认“已连接”，再保存并重开 Telegram。迁移保留下载档位、视频开关和现有本地日志；首次迁移可能需要 Root 授权。旧版框架需先升级。

### 下载档位

| 档位 | 分块大小 | 逻辑通道上限 | 请求上限 |
| --- | --- | --- | --- |
| 原版 | Telegram 默认 | Telegram 默认 | Telegram 默认 |
| 均衡 | 1 MiB | 4 | 8 |
| 高请求 | 1 MiB | 4 | 12 |

原版档保留普通下载设置，视频优化仍可单独开启。表中数值是调度上限，实际并发随任务变化；请求越多不一定越快，可根据线路表现选择。

### 视频优化

视频开关默认关闭。开启后沿用 Telegram 原生队列排序，优先请求当前读取位置的分块，用于视频起播和拖动后的加载。

适用的新任务采用 **128 KiB 分块、最多 16 个请求和 4 个逻辑通道**。已经开始的任务保留原分块大小，避免破坏请求对齐。普通下载继续使用所选档位；音频和完整缓存的本地播放不受影响。

实际加载速度取决于网络、代理线路、Telegram 服务端和播放器状态。模块调整客户端调度，不改变服务端限制。

### 日志与状态

Telegram 启动后显示模块状态提示；下载参数应用后提示“下载优化已生效”。

- 本地记录默认关闭，开启后显示彩色实时日志，支持跟随最新记录和暂停查看。日志仅保存在本机，不上传。
- 保留最近 **24 小时**，不设条数上限。Telegram 运行、查看或导出时清理过期记录；停止期间的记录在下次运行时清理。
- **暂停查看**只暂停界面刷新，不停止记录；关闭本地记录会删除保存的日志并收起面板。
- **清空日志 / 重新抓取**删除现有日志并重置统计，保留开关和下载设置，无需重启 Telegram。
- **导出日志**保存保留期内的记录，导出文件不参与自动清理。

日志包含在途分块、逻辑通道、响应、RPC 错误及视频位置请求，不记录聊天内容、文件名或视频 URI。通道数是逻辑调度统计，不是物理 TCP 连接数。

| 事件 | 含义 |
| --- | --- |
| `HOOK_READY` | 模块加载完成 |
| `PARAMS_APPLIED` / `BUDGET_APPLIED` | 下载参数 / 并发预算已应用 |
| `VIDEO_READY` | 视频调度已加载 |
| `VIDEO_POSITION_REQUESTED` | 当前请求的数据位置 |
| `VIDEO_RANGE_SUBMITTED` | 已提交分块的范围，以及是否覆盖目标位置 |
| `VIDEO_FIRST_DATA` / `VIDEO_SLOW_READ` | 首次读取数据耗时 / 不少于 250 ms 的读取 |
| `UNSUPPORTED` / `POLICY_DISABLED` / `VIDEO_DISABLED` | 适配或调度异常 |

提交、响应和读取是不同阶段；读取耗时不等同于画面恢复时间。事件名保留英文，方便检索。

### 兼容性与语言

已在 Google Play 版 Telegram **13.2.2（73031）** 和 **13.3.1（73461）** 上进行实机测试。3.3.3 在 13.3.1 上已观察到新位置分块请求和数据读取。通过 DexKit 方法特征和字段访问关系识别目标，不依赖固定混淆类名。适配逻辑见[兼容性说明](docs/COMPATIBILITY.md)。

界面跟随 Android 系统语言，中文系统显示中文，其他语言使用英文。切换语言不影响已保存的设置。

### 构建

使用 JDK 17 和 Android SDK 36：

```powershell
.\gradlew.bat assembleDebug assembleRelease
```

`assembleDebug` 生成调试签名 APK；`assembleRelease` 生成未签名 APK，需要自行签名。维护版与原作者使用不同签名，从原版切换时需要先卸载旧模块。

## English

### Setup

Requires Android 8.0 or later, Root, and a framework with libxposed API 101+ and remote preferences support. Target package: `org.telegram.messenger`.

1. Install an APK from [Releases](https://github.com/atigger/Telegram-Speed-Hook/releases).
2. Enable the module in LSPosed, select Telegram and follow the framework activation instructions.
3. Open the module, choose a download profile and optionally enable video optimization.
4. Tap **Save and stop Telegram**, grant Root access, then reopen Telegram.

Saving interrupts active Telegram downloads and calls. If automatic stopping fails, force-stop Telegram manually and reopen it.

### Upgrading to 3.4.0

3.4.0 uses modern libxposed APIs instead of legacy shared preferences. After an in-place update, open the module, wait for **Connected**, then save and reopen Telegram. Migration preserves the download profile, video setting and existing local logs. The first migration may request Root access. Upgrade older frameworks first.

### Download profiles

| Profile | Chunk size | Logical lane limit | Request limit |
| --- | --- | --- | --- |
| Original | Telegram default | Telegram default | Telegram default |
| Balanced | 1 MiB | 4 | 8 |
| High requests | 1 MiB | 4 | 12 |

Original leaves ordinary downloads unchanged; video optimization can still be enabled separately. These are scheduling limits, not fixed active counts. Choose a profile for your connection: more requests do not always mean higher speed.

### Video optimization

Off by default. Preserves native queue ranking and prioritizes chunks at the current read position for startup and seeking.

Eligible new operations use **128 KiB chunks, up to 16 requests and 4 logical lanes**. Existing operations retain their chunk size to preserve request alignment. Ordinary downloads follow the selected profile; audio and fully cached local playback are unchanged.

Loading speed depends on the network, proxy route, Telegram servers and player state. This module adjusts client scheduling, not server-side limits.

### Logs and status

A startup Toast reports module status. Once download parameters are applied, it reports **download optimization active**.

- Recording is off by default. Enable it for colored live logs, auto-scroll and pause. Logs stay on this device and are not uploaded.
- Records are kept for **24 hours**, with no entry limit. Expired records are removed while Telegram runs or when viewing or exporting logs; cleanup resumes on the next run after Telegram stops.
- **Pause viewing** pauses the display, not recording. Turning recording off deletes saved logs and collapses the panel.
- **Clear logs / start fresh** deletes local logs and resets counters while preserving recording and download settings. No Telegram restart is needed.
- **Export logs** saves all retained records. Exported files are excluded from automatic cleanup.

Logs include pending chunks, logical lanes, responses, RPC errors and video position requests, but exclude chats, filenames and video URIs. Logical lanes are scheduling statistics, not physical TCP connections.

| Event | Meaning |
| --- | --- |
| `HOOK_READY` | Hooks installed |
| `PARAMS_APPLIED` / `BUDGET_APPLIED` | Download parameters / concurrency budget applied |
| `VIDEO_READY` | Video scheduling loaded |
| `VIDEO_POSITION_REQUESTED` | Requested data position |
| `VIDEO_RANGE_SUBMITTED` | Submitted chunk range and target coverage |
| `VIDEO_FIRST_DATA` / `VIDEO_SLOW_READ` | First data timing / reads taking at least 250 ms |
| `UNSUPPORTED` / `POLICY_DISABLED` / `VIDEO_DISABLED` | Compatibility or scheduling error |

Submission, response and reading are separate stages. Read timing is not playback recovery timing. Event names remain in English for searching.

### Compatibility and language

Device testing covers Google Play Telegram **13.2.2 (73031)** and **13.3.1 (73461)**. On 13.3.1, version 3.3.3 logs show requests at new positions followed by successful reads. DexKit identifies targets using method fingerprints and field accesses instead of fixed obfuscation names. See [compatibility notes](docs/COMPATIBILITY.md) for implementation details.

The interface follows the Android system language: Chinese for Chinese locales, English otherwise. Changing languages does not affect saved settings.

### Build

Use JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat assembleDebug assembleRelease
```

`assembleDebug` creates a debug-signed APK. `assembleRelease` creates an unsigned APK that needs signing. This fork uses a different certificate from the original project; uninstall the original module before switching.
