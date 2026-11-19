# Changelog / 更新记录

## 3.2.0 — 2026-11-19

### 中文

- 适配 Google Play 版 Telegram `13.2.2`（`73031`，`org.telegram.messenger`）。
- 使用 DexKit 按方法特征和字段读写动态发现目标，避免写死混淆类名；同时处理参数初始化与下载预算更新。
- 增加原版、均衡（1 MiB / 4 路 / 8 请求）、高请求（1 MiB / 4 路 / 12 请求）三档选择。
- 保存配置成功后通过 Root 强制停止 Telegram，重新打开后生效；强制停止会中断下载、通话等操作。
- 设置界面自动跟随系统语言，提供中英文，其他语言回退到英文；文档提供中英说明。
- 保留原作者 **AraafRoyall** 及[原仓库](https://github.com/AraafRoyall/Telegram-Speed-Hook)，本版本由[适配仓库](https://github.com/atigger/Telegram-Speed-Hook)发布。
- 版本号统一为 `3.2.0`，`versionCode` 提升到 `6`。

已验证目标发现和参数回读，未进行受控测速。用户反馈均衡档有一定提升，高请求档不一定更快；不保证达到代理测速，也不能解除服务端限制。Telegram 后续版本尚未验证。

发布 APK 是 Release 构建，沿用本次测试安装的本地开发证书签名，便于覆盖升级；不使用原作者签名。私钥不公开。源码执行 `assembleRelease` 仍生成未签名 APK，需要自行签名。

### English

- Adapt the Google Play build of Telegram `13.2.2` (`73031`, `org.telegram.messenger`).
- Discover targets dynamically with DexKit method fingerprints and field accesses instead of hardcoded obfuscation names; hook both parameter initialization and download-budget updates.
- Add Original, Balanced (1 MiB / 4 lanes / 8 requests), and High requests (1 MiB / 4 lanes / 12 requests) profiles.
- Force-stop Telegram through Root only after settings are saved successfully; reopen Telegram to apply. This interrupts downloads, calls, and other operations.
- Follow the system language automatically, with Chinese and English resources and an English fallback; provide bilingual documentation.
- Retain original author **AraafRoyall** and the [original repository](https://github.com/AraafRoyall/Telegram-Speed-Hook). This release comes from the [adaptation repository](https://github.com/atigger/Telegram-Speed-Hook).
- Set the module version to `3.2.0` and increment `versionCode` to `6`.

Target discovery and parameter read-back were verified; no controlled speed benchmark was performed. The user reported some improvement with Balanced, but High requests is not necessarily faster. Proxy benchmark speeds are not guaranteed, server-side limits are unchanged, and later Telegram versions have not been validated.

The published APK is a Release build signed with the same local development certificate as this adaptation's test installation for in-place upgrades, not the original author's certificate. The private key is not published. Building the source with `assembleRelease` still produces an unsigned APK that needs your own signing step.
