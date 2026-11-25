# Compatibility / 兼容性

原作者 / Original author: **AraafRoyall** · [原项目 / Upstream](https://github.com/AraafRoyall/Telegram-Speed-Hook) · [维护仓库 / Fork](https://github.com/atigger/Telegram-Speed-Hook)

## 中文

### 实机测试

| Telegram 版本 | Version code | 已检查内容 |
| --- | --- | --- |
| 13.2.2（Google Play） | 73031 | 动态目标识别、下载参数和并发预算应用 |
| 13.3.1（Google Play） | 73461 | 下载策略、视频位置请求、目标分块、数据读取和本地日志 |

目标包名为 `org.telegram.messenger`，仅处理主进程。3.3.3 的 13.3.1 实机日志记录了拖动后对新位置的请求和成功读取；缓存命中与未缓存读取分别存在。该记录用于验证调度行为，不作为带宽基准。

### 动态识别

Google Play 构建会混淆 `FileLoadOperation` 的类名、方法和字段。模块使用 DexKit 按特征发现目标，而不是固定查找源码类名。

下载参数方法需要满足：

- 无参数、返回 `void`，包含常量 `131072`、`524288`、`2097152000`。
- 写入四个非静态 `int` 字段，读取两个非静态 `boolean` 保护字段。

并发预算方法单独识别，检查同类字段访问、除法指令和请求字段写入顺序；应用参数前再检查运行时值。视频数据源通过方法签名、URI 参数和监听接口识别，位置加载方法同时校验监听器写入关系。

### 视频调度

- 只处理 `tg` 数据源中的 `video/*` 流。
- 保留 Telegram 原生队列优先级和递增排序；非零读取位置启用原生优先分块，零偏移沿用原路径。
- 适用的新任务采用 128 KiB 分块、16 个请求和 4 个逻辑通道；已有任务保持分块对齐。
- 视频开关独立于普通下载档位。恢复完整原生行为时，选择“原版下载”、关闭视频优化，保存后重开 Telegram。

### 异常处理与统计

识别失败记录 `UNSUPPORTED`；普通下载或视频策略异常分别记录 `POLICY_DISABLED`、`VIDEO_DISABLED` 并停止后续修改。字段写入失败时尝试回滚当批修改；先前任务的修改在重启 Telegram 后恢复。

在途分块是尚未完成的请求；逻辑通道来自 Telegram 内部调度索引，不对应物理 TCP 连接。提交、响应、读取和播放是不同阶段，RPC 错误计数也不能单独确定网络故障。

后续 Telegram 版本若改变方法特征，需要重新适配。

## English

### Device testing

| Telegram version | Version code | Checks |
| --- | --- | --- |
| 13.2.2 (Google Play) | 73031 | Dynamic discovery, download parameters and concurrency budget |
| 13.3.1 (Google Play) | 73461 | Download policy, video positions, target chunks, data reads and local logs |

Only the main process of `org.telegram.messenger` is targeted. On 13.3.1, version 3.3.3 logs show requests at new positions after seeking, followed by successful reads, including cached and uncached reads. These are scheduling checks, not bandwidth benchmarks.

### Dynamic discovery

Google Play builds obfuscate the `FileLoadOperation` class, methods and fields. DexKit discovers targets by their implementation fingerprints instead of source-level names.

The parameter method must:

- Take no arguments, return `void`, and use constants `131072`, `524288` and `2097152000`.
- Write four non-static `int` fields and read two non-static `boolean` guard fields.

The concurrency budget method is discovered separately using same-class field accesses, division instructions and the request-field write order. Runtime values are checked before applying parameters. Video discovery checks method signatures, URI parameters, listener interfaces and the position loader write to the listener field.

### Video scheduling

- Applies to `video/*` streams using the `tg` data source.
- Keeps native queue priority and increasing ranks. Nonzero read positions use native priority chunks; zero-offset requests retain the original path.
- Eligible new operations use 128 KiB chunks, 16 requests and 4 logical lanes. Existing operations keep their chunk alignment.
- Video optimization is independent of ordinary download profiles. To restore all native scheduling, choose Original downloads, disable video optimization, save and reopen Telegram.

### Errors and statistics

Discovery failure records `UNSUPPORTED`. Ordinary download or video policy errors record `POLICY_DISABLED` or `VIDEO_DISABLED` and stop further changes. Failed writes attempt to roll back the current batch; earlier operations return to defaults after restarting Telegram.

Pending chunks are unfinished requests. Logical lanes use internal scheduling indices, not physical TCP connections. Submission, response, reading and playback are separate stages. RPC error counts alone do not identify a network fault.

Later Telegram versions may change these fingerprints and require an updated adapter.
