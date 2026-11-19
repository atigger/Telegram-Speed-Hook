# Compatibility / 兼容性说明

## Attribution / 归属

- Original author / 原作者: **AraafRoyall**
- Original repository / 原项目仓库: <https://github.com/AraafRoyall/Telegram-Speed-Hook>
- Adaptation repository / 当前适配仓库: <https://github.com/atigger/Telegram-Speed-Hook>

The original project attribution is retained. The notes below describe the later Telegram 13.2.2 adaptation and its observed test scope.

本项目保留原项目署名。以下内容描述后续 Telegram 13.2.2 适配及当前实测范围。

## Tested build / 测试版本

The tested device runs Telegram `13.2.2`, version code `73031`, package `org.telegram.messenger`.

测试设备上的 Telegram 为 `13.2.2`，version code 为 `73031`，包名为 `org.telegram.messenger`。

The Play APK is obfuscated. The source-level `FileLoadOperation` is emitted under an obfuscated name, and its parameter method and fields are also renamed. A fixed `XposedHelpers.findAndHookMethod("org.telegram.messenger.FileLoadOperation", ...)` therefore fails on this build.

Google Play APK 经过混淆。源码层的 `FileLoadOperation`、参数方法和字段都会被重命名，因此固定查找 `org.telegram.messenger.FileLoadOperation` 在此版本上会失败。

## Dynamic discovery / 动态发现

DexKit identifies the parameter method from:

DexKit 根据以下特征识别下载参数方法：

- no arguments and `void` return type / 无参数且返回 `void`；
- constants `131072`, `524288`, and `2097152000` / 常量 `131072`、`524288`、`2097152000`；
- exactly four non-static `int` fields written / 恰好写入四个非静态 `int` 字段；
- exactly two non-static `boolean` guard fields read / 恰好读取两个非静态 `boolean` 守卫字段。

The newer byte-budget method is discovered separately. Its same-class field access shape, division opcode, and final request-field write are validated before it is hooked. The adapter then applies the selected profile only when runtime values match the expected Telegram defaults.

新的字节预算方法会单独发现，并校验同类字段访问关系、除法指令和最终请求字段写入。只有运行时值符合 Telegram 预期默认值时，适配器才会应用用户选择的档位。

## Profiles / 档位

- Original / 原版: no fields changed / 不修改字段。
- Balanced / 均衡: 1 MiB chunks, 4 lanes, 8 requests / 1 MiB 分块、4 路连接、8 请求。
- High requests / 高请求: 1 MiB chunks, 4 lanes, 12 requests / 1 MiB 分块、4 路连接、12 请求。

The user reported about 2 MB/s with the earlier eight-lane experiment and about 3 MB/s with Balanced. Both connection and request limits changed. This was not a controlled comparison, so neither a single-parameter cause nor a general performance guarantee is established. The four-lane/twelve-request profile still needs further testing.

用户反馈此前八路连接实验约为 2 MB/s，均衡档约为 3 MB/s。两档同时改变了连接数和请求上限，且不是受控对比，尚未确定单一参数原因，也不能作为通用性能保证。四路、十二请求档仍待进一步实测。

## Safety boundaries / 安全边界

- Only the `org.telegram.messenger` main process is targeted / 只处理 `org.telegram.messenger` 主进程。
- Discovery failures log `UNSUPPORTED` before hooks are installed; later policy failures log `POLICY_DISABLED` and stop subsequent writes / 发现阶段失败时在安装钩子前记录 `UNSUPPORTED`；运行中失败时记录 `POLICY_DISABLED` 并停止后续写入。
- Runtime writes validate the defaults and attempt to restore the current batch on a write failure. Earlier successfully modified operations are not globally restored; restart Telegram with Original selected to return to defaults / 运行时先校验默认值；写入失败时尝试恢复本批字段，不会全局还原先前已修改的操作。选择原版档并重启 Telegram 才能恢复默认行为。
- The module changes client-side scheduling only / 只修改客户端调度，不修改协议或服务端限制。
- A future Telegram release can change the implementation fingerprint / Telegram 后续版本可能改变实现特征，需要重新验证。
