# Telegram Speed Hook

Xposed/LSPosed module for experimenting with Telegram client-side download scheduling.

Current version / 当前版本: **3.2.0** (`versionCode 6`). See [release notes / 更新记录](docs/CHANGELOG.md).

## Attribution / 归属

- Original author / 原作者: **AraafRoyall**
- Original repository / 原项目仓库: <https://github.com/AraafRoyall/Telegram-Speed-Hook>
- Adaptation repository / 当前适配仓库: <https://github.com/atigger/Telegram-Speed-Hook>

This repository keeps the original author attribution and original project link. The 13.2.2 compatibility work is an adaptation; it is not presented as the original author's release.

本仓库保留原作者署名和原项目链接。Telegram 13.2.2 兼容工作属于后续适配，不冒充原作者发布。

## 中文说明

### Telegram 13.2.2

Google Play 版 Telegram 13.2.2 的类名和字段名经过混淆，原项目固定查找 `FileLoadOperation.updateParams` 的方式无法直接工作。本适配使用 DexKit 根据方法常量、签名、字段读写关系动态查找目标，不把本次 APK 的混淆类名写死。

### 设置档位

- **原版**：不修改 Telegram 下载参数。
- **均衡**：1 MiB 分块、4 路连接、8 请求上限。
- **高请求**：1 MiB 分块、4 路连接、12 请求上限。

模块设置页跟随 Android 系统语言：中文使用中文界面，英文使用英文界面；其他未翻译语言回退到英文。语言变化不会更改已保存档位。保存按钮会先写入配置，再尝试通过 Root 强制停止 Telegram；重新打开 Telegram 后生效。强制停止会中断正在进行的下载和其他 Telegram 操作。

### 当前验证结果

- 已在 Telegram `13.2.2`、version code `73031`、包名 `org.telegram.messenger` 上验证动态发现和字段回读。
- 已观察到 `PARAMS_APPLIED` 和 `BUDGET_APPLIED` 回读日志；字段写入不等同于实际活跃并发或提速。
- 用户反馈均衡档约为 3 MB/s，此前 8 路高并发档约为 2 MB/s。两档同时改变了连接数和请求上限，且不是受控测速，不能归因为单一参数。当前高请求档保留 4 路、只提高请求上限，仍需继续实测。
- 这些结果不是通用基准，也未确定瓶颈原因；不能把代理测速直接视为 Telegram 下载速度保证。

### 构建与安装

可在 [GitHub Releases](https://github.com/atigger/Telegram-Speed-Hook/releases) 下载已签名 APK。`3.2.0` 使用 Release 构建，并沿用本次测试安装的本地开发证书签名，以便覆盖升级；它不是原作者的签名，也不是原作者发布。签名私钥不进入仓库或发布附件。其他来源安装的版本若签名不同，不能直接覆盖安装。

需要 JDK 17、Android SDK 35，以及同时支持旧版 Xposed 钩子 API 和 API 93 共享配置扩展的 LSPosed 兼容环境：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

在 LSPosed 中启用模块并选择 Telegram，打开模块设置页保存档位。若共享配置不可读，钩子会记录 `PROFILE_READ_FAILED` 并保持原版行为。更新模块后等待 LSPosed 刷新模块缓存，再启动 Telegram。`assembleRelease` 生成未签名 APK；仓库不包含签名密钥。

### 日志与边界

日志标签为 `TgSpeedHook`：

- `HOOK_READY`：挂钩安装完成。
- `PARAMS_APPLIED`：下载参数写入并回读确认。
- `BUDGET_APPLIED`：预算连接数和请求上限写入并回读确认。
- `UNSUPPORTED` / `POLICY_DISABLED`：无法识别或安全校验失败。

诊断日志保留固定英文标识，方便跨语言检索；界面说明和状态消息提供中英版本。

本模块只调整客户端调度，不修改 Telegram 协议，也不能移除服务端限速。未来 Telegram 版本改变实现后，需要重新检查日志和方法特征。

## English

### Telegram 13.2.2

The Google Play build of Telegram 13.2.2 obfuscates class and field names, so the original fixed lookup of `FileLoadOperation.updateParams` no longer works directly. This adaptation uses DexKit to discover the target from method constants, signatures, and field read/write relationships instead of hardcoding the obfuscation names from one APK.

### Profiles

- **Original**: do not change Telegram download parameters.
- **Balanced**: 1 MiB chunks, 4 connection lanes, 8-request limit.
- **High requests**: 1 MiB chunks, 4 connection lanes, 12-request limit.

The settings screen follows the Android system language: Chinese and English are provided, with English as the fallback for untranslated languages. Changing the language does not change the saved profile. The save button writes the profile first, then tries to force-stop Telegram through Root; the profile is read when Telegram starts again. Force-stopping interrupts active downloads and other Telegram operations.

### Current validation

- Dynamic discovery and field read-back were verified on Telegram `13.2.2`, version code `73031`, package `org.telegram.messenger`.
- `PARAMS_APPLIED` and `BUDGET_APPLIED` read-back logs have been observed; written fields are not proof of active concurrency or a speed increase.
- The user reported roughly 3 MB/s with Balanced and 2 MB/s with the earlier eight-lane profile. Both connection and request limits changed, and the comparison was not a controlled benchmark, so no single cause is established. The current High requests profile keeps four lanes and only raises the request limit; more testing is still needed.
- These observations are not universal benchmarks, and the bottleneck is not established. Proxy benchmark throughput is not a guaranteed Telegram download speed.

### Build and install

Download the signed APK from [GitHub Releases](https://github.com/atigger/Telegram-Speed-Hook/releases). Version `3.2.0` is a Release build signed with the same local development certificate as this adaptation's test installation for in-place upgrades. It is neither signed nor released by the original author. Private signing keys are not included in the repository or release assets. Installations signed with a different certificate cannot be updated in place.

Requires JDK 17, Android SDK 35, and an LSPosed-compatible environment supporting both legacy Xposed hooks and the API 93 shared-preference extension:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Enable the module in LSPosed, select Telegram, and open the module settings to save a profile. If the shared settings are unreadable, the hook logs `PROFILE_READ_FAILED` and leaves the original behavior enabled. After updating the module, wait for LSPosed to refresh its module cache before starting Telegram. `assembleRelease` produces an unsigned APK; no signing key is included.

### Logs and boundaries

The log tag is `TgSpeedHook`:

- `HOOK_READY`: hooks installed.
- `PARAMS_APPLIED`: download parameters were written and read back.
- `BUDGET_APPLIED`: budget lanes and request limit were written and read back.
- `UNSUPPORTED` / `POLICY_DISABLED`: discovery or safety validation failed.

Diagnostic logs keep fixed English identifiers for cross-language troubleshooting; the interface explanations and status messages are localized.

This module changes client-side scheduling only. It does not modify the Telegram protocol or remove server-side limits. A future Telegram implementation change requires rechecking the method fingerprint and logs.
