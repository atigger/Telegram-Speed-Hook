# Changelog / 更新记录

## 3.3.4 — 2026-11-25

### 中文

- 发布视频优化与本地日志功能，保留原版、均衡和高请求下载档位。
- 视频调度沿用 Telegram 原生队列排序，拖动后优先请求新位置的分块；已有下载任务保留原分块大小。
- 实时日志支持自动滚动、暂停查看、清空重抓和导出；默认关闭，仅本地保存，保留 24 小时，不限制条数。
- 修复部分 Root 环境下日志目录访问与记录开关状态同步问题。
- 更新中英文说明、状态提示和文档，保留原作者 AraafRoyall 及原仓库链接。
- versionCode 更新为 12，沿用维护版签名，支持覆盖升级。

### English

- Release video optimization and local diagnostics alongside Original, Balanced and High requests download profiles.
- Keep native video queue ranking and prioritize chunks at the new position after seeking. Existing operations retain their chunk size.
- Add live logs with auto-scroll, pause, fresh captures and export. Recording is off by default, local-only, with 24-hour retention and no entry limit.
- Fix log directory access and recording state synchronization on affected Root setups.
- Refresh bilingual UI copy, status messages and documentation, retaining AraafRoyall and upstream attribution.
- Set versionCode to 12 and retain the maintained fork signing certificate for in-place upgrades.

## 3.3.3 — 2026-11-24

### 中文

- 修正视频任务复用时的队列排序，保留 Telegram 原生优先级和重新调度行为。
- 拖动后优先请求新位置的分块；新建视频任务使用独立参数，已有任务保留分块对齐。
- 日志增加目标偏移、实际请求范围、目标覆盖和读取耗时。
- 11 月 25 日在 Telegram 13.3.1 上完成位置请求与读取验证，包含重开和前后拖动操作。
- 精简中英文界面、状态提示和文档，整理安装、档位、视频及日志说明；文案整理不涉及下载逻辑。

### English

- Fix queue ordering when reusing video operations; preserve native priority and rescheduling.
- Prioritize chunks at the new position after seeking. Use separate parameters for new video tasks and preserve existing chunk alignment.
- Log target offsets, submitted ranges, target coverage and read timings.
- Validate position requests and reads on Telegram 13.3.1 on November 25, including reopening and seeking in both directions.
- Streamline bilingual UI copy, status messages and documentation. No scheduling changes in the copy update.

## 3.3.2 — 2026-11-24

### 中文

- 新增“清空日志 / 重新抓取”，清除本地日志、重置请求计数与峰值，保留设置和导出文件。
- 独立标记每轮抓取，过滤旧记录；Telegram 未运行时显示等待重置。
- 关闭本地记录时收起日志面板和相关控件，减少页面滚动。

### English

- Add Clear logs / start fresh to delete local logs and reset counters and peaks while preserving settings and exported files.
- Isolate captures, discard stale entries and show a pending reset when Telegram is not running.
- Collapse the log panel and controls when recording is off.

## 3.3.1 — 2026-11-24

### 中文

- 修复已授权 Root 仍无法开启记录的问题，统一日志查看和导出的目录访问方式。
- 进入或返回设置时同步记录状态，避免旧轮询结果覆盖新操作。
- 分别统计响应、RPC 错误、提交失败、未知结果和取消，明确旧版未分类记录。

### English

- Fix recording access after Root authorization and use the same directory access for viewing and export.
- Refresh recording status on entering or returning to settings and discard stale polling results.
- Separate responses, RPC errors, submission failures, unknown outcomes and cancellations; label unclassified legacy records.

## 3.3.0 — 2026-11-23

### 中文

- 新增独立视频优化开关，保留普通下载三档设置。
- 动态识别视频流，为适用任务配置 128 KiB 分块、16 请求和 4 个逻辑通道。
- 新增视频打开、首次读取、慢读取和关闭事件。

### English

- Add an independent video optimization switch while retaining all three ordinary download profiles.
- Discover video streams dynamically and configure eligible tasks with 128 KiB chunks, 16 requests and 4 logical lanes.
- Add video open, first data, slow read and close events.

## 3.2.1 — 2026-11-23

### 中文

- 新增模块状态 Toast 和彩色富文本实时日志，支持自动滚动、暂停与导出。
- 记录原生下载请求、写出、完成、清理事件及并发峰值。
- 日志默认关闭，仅保存在本机，保留 24 小时，不限制条数；关闭时清空。
- 提供中英文界面，不记录聊天、文件名、令牌或原始账号标识。

### English

- Add module status Toasts and colored live logs with auto-scroll, pause and export.
- Observe native request submissions, writes, completions, clears and concurrency peaks.
- Keep recording off by default, local-only, with 24-hour retention and no entry limit. Turning it off clears saved logs.
- Localize the interface and exclude chats, filenames, tokens and raw account identifiers.

## 3.2.0 — 2026-11-19

### 中文

- 适配 Google Play 版 Telegram 13.2.2（73031），通过 DexKit 动态识别混淆方法与字段。
- 新增原版、均衡、高请求三档，分别处理下载参数和并发预算。
- 保存后通过 Root 停止 Telegram，重开生效；界面自动跟随系统语言。
- 保留原作者 AraafRoyall 与原仓库，由维护仓库发布。
- 版本更新为 3.2.0，versionCode 为 6。Release APK 使用本地开发证书签名，便于后续覆盖升级。

### English

- Support Google Play Telegram 13.2.2 (73031) through DexKit discovery of obfuscated methods and fields.
- Add Original, Balanced and High requests profiles for download parameters and concurrency budgets.
- Save settings and stop Telegram through Root; reopen to apply. Follow the system language automatically.
- Retain original author AraafRoyall and upstream attribution; publish from the maintained fork.
- Set version 3.2.0 with versionCode 6. Sign the Release APK with the local development certificate for in-place upgrades.
