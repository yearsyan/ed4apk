# APK 基础浏览与原生库检查设计

状态：第一期与第二期列出的命令均已实现。更新日期：2026-09-22。

目标：在现有独立 JAR / Java 11 CLI 上补齐「发现条目 → 查看内容 → 定位问题 → 提取文件」流程，方便人工和脚本在编辑前了解 APK。命令使用与限制见 JAR 内的 `--help-all`，本文保留最初的范围划分并说明实现约定。

## 1. 实施前的能力与扩展范围

| 能力 | 现状 | 本次建议 |
| --- | --- | --- |
| APK 概览 | `info` 输出包名、版本、minSdk、DEX 文件名 | 增加 targetSdk、包体积、条目数、ABI、分包标识及 JSON |
| 文件条目 | 内部可读取/遍历，CLI 侧主要提供编辑 | 增加列表、单条目提取，随后增加文本/十六进制预览 |
| Manifest | `manifest show` 已支持整树、XPath、具名组件，输出保留类型的 JSON | 保留默认行为，增加可读 XML |
| SO | 重打包/签名已处理 ZIP 内 16 KiB 对齐 | 展示清单，独立检查 ZIP 偏移、ELF LOAD 段及 RELRO |
| DEX | 已有类列表、静态引用查询、单类 Smali 导出 | 增加过滤、统计、JSON；本轮不增加 Java/Kotlin 反编译 |
| 资源 | 已有按 ID/config 修改字符串 | 下一阶段补 ID、名称、配置和值的查询 |

实施前的实现入口（概览现由 `ApkInfoCommand` 提供，DEX 浏览现由 `DexInspectionCommands` 提供）：

- `Main.Info`、`Main.DexList`：当前概览和类列表。
- `ManifestCommands.Show` / `ManifestEditor.show`：可复用的 Manifest 选择与 typed JSON。
- `ApkArchive.alignment` / `checkAlignment`：未压缩 `lib/**/*.so` 使用 16384 字节 ZIP 对齐；其他未压缩文件使用 4 字节。
- `Signing.sign`：设置库页面对齐为 16384，并在签名后检查 ZIP 对齐。
- `ResourceEditor`：已有 Manifest 和 resources.arsc 解析依赖。

实施前没有 ELF 检查。`EditorIntegrationTest` 中的 `libdemo.so` 是零填充测试数据，旧测试证明的是 ZIP 偏移对齐；新增 `NativeInspectionTest` 使用有效 ELF 结构分别验证两层对齐。

## 2. 命令设计与交付顺序

以下命令均已提供。为简洁省略 `java -jar ed4apk.jar`，实际继续通过独立 JAR 使用。

### 第一期：直接覆盖此次需求

```sh
ed4apk info app.apk --json

ed4apk file list app.apk
ed4apk file list app.apk --prefix lib/ --json
ed4apk file list app.apk --sort size --limit 20
ed4apk file extract app.apk lib/arm64-v8a/libfoo.so -o libfoo.so

ed4apk native list app.apk
ed4apk native check app.apk --page-size 16384
ed4apk native check app.apk --abi arm64-v8a --json

ed4apk manifest show app.apk --format xml
ed4apk manifest show app.apk --path /manifest/application/activity --format xml
```

- `file` 使用单数，与现有 `file.add/replace/delete` 补丁操作一致。
- `native` 专门处理原生库；第一期 `--page-size` 默认为 16384，其他值先报参数错误，避免声称支持未经验证的其他页面策略。
- `info` 读取目录和 Manifest 即可；不自动解析所有 DEX/ELF、不自动做签名验证。签名验证继续使用 `verify`。
- 默认展示便于阅读的表格；新命令用 `--json` 给脚本读取。
- `manifest show` 是兼容性例外：默认仍为现有 JSON 数组；新增 `--format json|xml`，JSON 内容和可回用于 add/replace 的语义不变。

### 第二期：完善日常浏览

```sh
ed4apk file show app.apk assets/config.json
ed4apk file show app.apk assets/data.bin --format hex --limit 256
ed4apk native show app.apk lib/arm64-v8a/libfoo.so --json

ed4apk manifest summary app.apk --json
ed4apk dex info app.apk --json
ed4apk dex list app.apk --dex classes2.dex --prefix com.example --json
ed4apk resource list app.apk --type string --json
ed4apk resource show app.apk 0x7f010000
```

资源查询的优先级高于扩展 DEX 阅读：它可以直接解决现有 `resource set-string` 需要先知道 ID/config 的使用障碍。文件树、整包提取、APK diff、AAB/APKS 容器支持和交互式 UI 后续按实际需求评估。

## 3. 文件条目浏览

`file list` 默认按完整 ZIP 路径排序，输出 `path / size / compressedSize / method`。JSON 额外包含目录标记、CRC32 和可确认的 `dataOffset`。

- `size` 是解压后字节数，`compressedSize` 是 ZIP 中载荷字节数，不能将后者之和称为整个 APK 大小；ZIP 头、签名块等也占体积。
- `--prefix` 是大小写敏感的 APK 路径前缀，不使用宿主系统路径分隔符。
- `--sort path|size|compressed-size`：路径升序，大小降序，同值按路径排序。
- 不传 `--limit` 返回全部；指定时 JSON 返回 `matchedCount / returnedCount / truncated`，不静默丢弃条目。
- 列表只读取 ZIP 元数据；不为识别类型而解压每个文件。扩展名类别只能标记为提示，ELF 类型必须读取 magic 确认。

`file extract` 第一期仅提取一个确切路径到用户指定的 `-o` 文件；输出是条目解压后的原始字节，二进制 Manifest 不会自动转 XML。使用流式复制、CRC 校验及现有 `Outputs.write` 发布方式；不覆盖输入 APK，输出已存在需要 `--force`。签名文件允许读取/提取，不能误用当前禁止编辑签名条目的校验器。

第二期 `file show` 默认至多预览 64 KiB：有效 UTF-8 文本做可读展示，否则显示十六进制摘要；转义终端控制字符，明确截断状态。完整原始内容用 `extract`。二进制 XML 展示可复用 Manifest 的解码能力，但只在明确请求 XML 时尝试。

读取与编辑分开校验：对 JDK `ZipFile` 可打开的 ZIP，列表尽可能显示条目及诊断，即使 Manifest 损坏也能浏览。目录枚举保留重复名字的各条记录并报告冲突，不能先塞进按名字去重的 Map。按路径提取遇到重名必须报歧义。不支持的压缩方法、加密条目等可能在打开 ZIP 时就被 JDK 拒绝，此时明确报告无法建立目录；第一期不另写通用的损坏 ZIP 恢复器或中央目录解析器。

## 4. SO 与 16 KiB 检查

对齐报告拆为独立字段，避免一个含糊的 `aligned=true`：

| 检查项 | 含义 | 建议判定 |
| --- | --- | --- |
| `zipAlignment` | 库在 APK 内的实际载荷起点 | 未压缩 SO 的 `dataOffset % 16384 == 0` |
| `loadAlignment` | ELF 中每个 `PT_LOAD` 的布局 | 检查每个 LOAD 的 `p_align` 及偏移/地址同余 |
| `relroAlignment` | ELF 中 `PT_GNU_RELRO` 的范围末端 | 若存在，检查 `(p_vaddr + p_memsz) % 16384 == 0` |
| `packaging` | 压缩方式是否与声明的加载方式冲突 | 联合 `extractNativeLibs` 声明和条目存储方式诊断 |

Android 官方分别要求检查 ZIP 与 ELF，并在当前指南中列出 RELRO 末端检查；静态对齐检查通过后仍需在 16 KiB 环境验证运行行为。[页面大小指南](https://developer.android.com/guide/practices/page-sizes)

### ZIP 层

- 使用实际 file data offset，不能用 Local File Header 的起始偏移。
- 压缩 SO 的 ZIP 对齐状态为 `not_applicable`，仍须检查解压后 ELF；不能把压缩直接判为兼容。
- 对未压缩 SO 总是报告实测对齐结果，不因 `extractNativeLibs=true` 隐藏它。第一期检查使用明确的静态打包策略：目标 ABI 的未压缩 SO 须满足 16 KiB ZIP 对齐。
- `extractNativeLibs=false` 却存在压缩的标准路径原生库，报告独立的打包冲突。
- Manifest 缺失属性时记录 `declared=null`，不猜测构建工具配置；官方说明其构建默认值与 minSdk/AGP 有关，参见 [extractNativeLibs](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs)。在加载方式不能确认且存在压缩 SO 时，`packaging` 为 `unknown`；所有目标 SO 均未压缩时，本项可确认没有压缩冲突，ZIP/ELF 仍分别判定。Manifest 无法解析或当前分包缺少加载上下文时保留明确诊断。
- 对齐修复可通过重新打包改变 ZIP 布局；修改 ZIP 后要重新签名。参考官方 [zipalign](https://developer.android.com/tools/zipalign)。

### ELF 层

读取 ELF header 和 program headers 即可完成第一期检查，不需要反汇编、符号表或 section headers。

1. 验证 magic、ELF class、字节序、类型、program header 表范围及段文件范围。解析器支持 ELF32/ELF64 和端序识别；Android 目标 ABI 不支持的组合单独诊断。
2. 对每个 `PT_LOAD` 保留 `p_offset / p_vaddr / p_filesz / p_memsz / p_align / flags`；确认 `p_filesz <= p_memsz`。
3. 静态 16 KiB 策略要求 `p_align` 是不小于 16384 的 2 的幂，且 `p_vaddr % p_align == p_offset % p_align`。同时展示对 16384 的余数，定位异常段。
4. 不要求 `p_offset` 和 `p_vaddr` 各自为 16384 的倍数；关键是它们同余。ELF 的该约束来自 [ELF Program Loading 规范](https://gabi.xinuos.com/elf/07-pheader.html)。
5. 检查所有 `PT_GNU_RELRO` 的末端；不存在时该项为 `not_applicable`。`p_vaddr + p_memsz` 和所有范围运算做溢出检查。
6. 没有 LOAD、头被截断、超出解析能力/读取预算等情况不能记为通过：分别返回具体诊断及 `unknown`。

展示路径声明的 ABI 和 ELF 实际 machine；两者冲突单独报错。所有标准 `lib/<abi>/*.so` 都列出，16 KiB 汇总默认覆盖 `arm64-v8a`、`x86_64`；32 位 ABI 的原始 ELF 信息可查看，16 KiB 策略标记为不适用。未知 ABI 不能静默忽略，应显示未覆盖原因。

报告只覆盖当前 APK 内的标准原生库路径，并包含 `scope`、目标 ABI 和扫描数量。`assets` 中的自定义库、嵌套归档、运行时下载及其他 split 不在默认扫描范围；包内无标准原生库时写「未发现标准原生库」，汇总为 `not_applicable`，不宣称整个应用没有 native code。显式指定不存在的 `--abi` 返回错误，避免空集合被当作通过。

示意输出（虚构数据）：

```text
PATH                              ZIP16K  LOAD16K  RELRO16K  RESULT
lib/arm64-v8a/libgood.so           PASS    PASS     PASS      PASS
lib/arm64-v8a/liblegacy.so         PASS    FAIL     PASS      FAIL
lib/arm64-v8a/libcompressed.so     N/A     PASS     N/A       PASS

liblegacy.so: PT_LOAD[1].p_align=4096; required >= 16384.
Rebuild or replace this library; ZIP realignment cannot repair ELF layout.
```

最后一行的压缩库示例假定 Manifest 允许提取加载。ELF 不合格时建议重新编译/更换库；本轮不提供修改 `p_align` 字段的所谓一键修复。

`native list` 返回清单、大小、存储方式、ZIP 对齐，不默认解压扫描所有库。`native check` 才执行完整静态检查；`native show` 在第二期提供单库 ELF 段细节。底层报告结构在第一期就保存失败段，文本和 JSON 均能定位问题。

## 5. Manifest、概览与 DEX 边界

Manifest XML 是解码后的可读表示，不能恢复源码的注释、排版或所有资源符号名。优先复用 ARSCLib 的 XML 序列化能力；保持命名空间、属性语义和转义。多节点选择以 XML 片段输出，帮助文档应说明不是单一根节点文档。

第一期不强制加载资源表来展开所有引用；保留 `@0x7f...` 比给出错误值更可靠。第二期资源查询可提供 `resource ID + 符号名 + config + 值`；无法解析外部/框架引用时保持原引用。避免随意选一个语言配置作为唯一值。

`info` 新增字段：APK 实际字节数、条目数、targetSdk、ABI、DEX 文件数/名称、`split` 等已声明的分包信息。Manifest 解析失败时 JSON 保留可用的 ZIP 概览和诊断，退出码表示未完整成功。不把基础包缺少 ABI、当前 split 缺少代码等情况直接推断为整个应用不存在相应内容。

`manifest summary` 后续提供权限、组件、启动入口候选、debuggable、extractNativeLibs。属性区分声明值与推导值；例如 exported 未声明不能直接显示成 false，启动入口应考虑 activity-alias。

DEX 只扩展结构信息：文件版本、类定义数、method/field ID 数和可选的定义数（字段命名区分引用表项与实际定义）。`dex list` 增加 DEX/包前缀过滤与 JSON，保留无选项时的旧文本格式。不添加全量 Java/Kotlin 反编译依赖；需要单类指令仍用现有 `dex export`。

现有 `DexEditor.parse` 拒绝 DEX 041 及以上。新的头信息浏览只在格式已确认时显示字段；不支持的 DEX 容器必须保留版本与明确诊断，不能阻止 ZIP 列表，也不能误套旧格式的计数偏移。

## 6. 实现结构与输出契约

实现使用直接服务本次需求的类：

```text
FileCommands / NativeCommands / ManifestCommands / Main.Info
                         |
               ApkInspectionSession
               /         |          \
         ZIP metadata   Manifest   NativeInspector
                                      |
                                 ElfInspector
                         |
              plain result objects -> text / JSON
```

- `ApkInspectionSession`：只读、可关闭、一次建立条目索引，按需打开内容流；避免多个命令内反复打开并扫描同一 APK。
- ZIP 目录信息与内容流复用 JDK `ZipFile`；确认名字唯一和结构受支持后可用 zipflinger 获取 payload offset。无法可靠获取偏移时标 `unknown`，不根据猜测给对齐结论。
- `NativeInspector` 组合路径、存储方式、Manifest 声明与 ELF 报告；`ElfInspector` 仅处理二进制结构和段信息。
- `Inspection` 用 Java 11 的 Map/List 封装数据和诊断，原生库/DEX 使用小型结果对象；CLI 处理参数/渲染，业务逻辑放在各 Inspector 中。
- 元数据列表不读取大文件内容。ELF 只按范围读取头部，压缩条目使用有读取预算的流或有上限的临时文件；避免复用当前 `ApkArchive.read()` 的无上限 `readAllBytes()`。预算耗尽必须输出 `unknown` 和限制原因。
- 保留现有编辑链路的校验语义；浏览可以展示异常，写入仍按原规则拒绝异常。第一期不把新 ELF 检查隐式变为所有编辑/签名操作的强制门槛。

新 JSON 报告使用对象封装：`schemaVersion: 1`、`data`、`diagnostics`；native 报告在 data 内包含 scope、libraries、summary。状态值为 `pass / fail / unknown / not_applicable`，同时给稳定的 `code`、`entry`、`message` 和必要的段索引/实测值。

字节数、计数、ZIP 偏移使用整数；ELF 的无符号 64 位地址/值使用约定的十六进制字符串，避免后续 JavaScript 消费者丢失精度。未知值用 null 并给原因，不能伪造为 0。Manifest 现有 JSON 和已有 `dex refs --json` 不套新封装，避免破坏兼容。

延续现有退出码：

- `0`：浏览结果正常生成；`native check` 的目标范围全部满足策略，或明确无适用条目。
- `1`：读取/解析未完成，或 `native check` 存在目标范围的 fail/unknown。尽可能保留其他条目的结果；不能因为一条坏 SO 就失去其余诊断。
- `2`：参数错误。

`native list` 中展示 ZIP 不对齐属于正常读取的事实，不因此返回检查失败；自动化门禁使用 `native check`。JSON 模式 stdout 只输出一个 JSON 文档；人类日志放 stderr。unknown 与已知不满足的 fail 必须可区分，CLI 帮助同步说明这些语义。

## 7. 验收重点

第一期验收覆盖真实风险，不只验证输出里出现某个字符串：

1. 文件列表能枚举普通条目、目录、中文路径、零字节文件和签名条目；排序/前缀/截断可预测。未压缩大小与压缩大小正确区分。
2. 单条目流式提取字节一致；CRC 不符拒绝发布；输出与输入同文件、输出已存在、读取中断均保护原文件。
3. 构造最小有效 ELF，覆盖 ZIP 通过/ELF 失败、ZIP 失败/ELF 通过、压缩 SO、多个 LOAD、合法非零同余偏移、64 KiB p_align、RELRO 末端未对齐、无 RELRO。
4. 头截断、无 LOAD、无效范围/对齐值、整数溢出、ABI 与 machine 不匹配、解析预算耗尽都不能误报 pass；其他条目继续显示。
5. 覆盖 Manifest 属性 false/true/缺失/解析失败、32 位库、无原生库、未覆盖 ABI、单独 split 和不存在的 --abi。
6. 默认 `manifest show` JSON 与旧版兼容；XML 转义正确、命名空间可理解、资源引用保留、多选择结果行为明确。
7. 新 JSON 和退出码可由脚本稳定消费；读取前后 APK 字节完全不变。文件列表与原生库清单不触发整包解压或 DEX 解析。
8. 开发/CI 有工具时，以 `zipalign -c -P 16 -v 4`、`llvm-readelf -lW` 交叉核对有效 ELF fixture。SDK/NDK 仅作为开发验证工具，不成为分发 JAR 的运行依赖。

新增验证分别位于 `FileInspectionTest`、`NativeInspectionTest`、`ManifestResourceInspectionTest` 和 `DexInspectionTest`，并保留原有编辑/签名测试。README、离线 guide 和命令 help 已同步；分发方式仍为 Java 11+ 独立 JAR，无新增运行依赖。
