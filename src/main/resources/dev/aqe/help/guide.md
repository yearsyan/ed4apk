# AQE 离线使用指南 / Offline agent guide

AQE 是 APK 增量编辑 CLI。运行需要 Java 11+；所有库在 aqe.jar 内，不需要 Android SDK、
Gradle、外部 JAR 或联网。JRE 不包含在 JAR 中。以下命令中的 aqe.jar 换成实际 JAR 路径。
命令参考里简写的 `aqe` 均表示 `java -jar aqe.jar`，不要求安装名为 aqe 的可执行文件。

## 第一次使用：先选择任务，再检查输入

| 目标 | 命令 |
| --- | --- |
| 查看包名、版本、minSdk、DEX 文件名 | `java -jar aqe.jar info app.apk` |
| 查看全部类及其所在 DEX | `java -jar aqe.jar dex list app.apk` |
| 导出某个类，再修改其中的方法 | `java -jar aqe.jar dex export app.apk com.example.Main -o Main.smali` |
| 替换或新增 assets / res / so 文件 | `java -jar aqe.jar replace app.apk assets/config.json=config.json -o edited.apk` |
| 一次完成多种修改、新增、删除，可选签名 | `java -jar aqe.jar apply app.apk --patch patch.json -o edited.apk` |
| 签名 / 验证签名 | `java -jar aqe.jar sign ...` / `java -jar aqe.jar verify signed.apk` |
| 获取流程和可复制文件 | `java -jar aqe.jar examples` |

先读取目标 APK 的信息，核实类名与 DEX 名。不要把示例类名、路径或资源 ID 当作目标 APK 的真实值。
AQE 当前没有 APK 文件列表、资源 ID 查询或 Java 反编译命令；已有类通过 `dex list` 查询，
资源路径和 ID 需由用户提供或用其他 APK/ZIP 分析工具获取，不能猜测。

## 最小示例：修改名称并得到新的 APK

只要输入是有效 APK，这个例子不需要猜类名或资源 ID：

```sh
java -jar aqe.jar info app.apk
java -jar aqe.jar manifest set app.apk --label "AQE Demo" -o edited.apk
java -jar aqe.jar info edited.apk
```

edited.apk 是未签名输出。已有输出不会被覆盖；需要重新生成时加 `--force`。
label 是应用名称的字面量；保留多语言名称需修改对应资源字符串。

## 完整示例：新增辅助类，让已有类调用，再一次打包

前置条件：app.apk 中存在 classes.dex 与 com.example.Main，且不存在 com.example.aqe.Helper。
实际使用时先通过 info / dex list 核实并修改这些名字。以下命令在同一工作目录执行。

```sh
java -jar aqe.jar dex export app.apk com.example.Main -o Main.smali
java -jar aqe.jar examples dex-call --file Helper.smali > Helper.smali
java -jar aqe.jar examples dex-call --file patch.json > patch.json
```

Helper.smali 的完整内容如下，不需要 Java 编译器：

```smali
.class public Lcom/example/aqe/Helper;
.super Ljava/lang/Object;

.method public static onEnter()V
    .registers 2
    const-string v0, "AQE"
    const-string v1, "Helper called"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method
```

编辑刚导出的 Main.smali，在确定会执行的方法中插入下面一行。例如 Activity 的 onCreate 中，
放在原 invoke-super 调用后。这个调用没有参数、没有返回值，不额外占用调用方寄存器。

```smali
invoke-static {}, Lcom/example/aqe/Helper;->onEnter()V
```

保留 Main.smali 的原类描述符及其余成员。不要用只有一个示例方法的残缺类覆盖真实类。
AQE 替换的是整个类，不会自动合并方法或插入调用点。不能向 abstract/native 方法直接插入指令。

patch.json：

```json
{
  "version": 1,
  "operations": [
    {"op": "dex.add", "inputs": ["Helper.smali"], "dex": "classes.dex"},
    {"op": "dex.replace", "inputs": ["Main.smali"]}
  ]
}
```

应用并检查产物：

```sh
java -jar aqe.jar apply app.apk --patch patch.json -o edited.apk
java -jar aqe.jar dex list edited.apk
java -jar aqe.jar dex export edited.apk com.example.Main -o Main.after.smali
java -jar aqe.jar dex export edited.apk com.example.aqe.Helper -o Helper.after.smali
```

检查 Main.after.smali 中调用是否存在。导出成功只证明字节码可解析，不证明调用点一定会执行。
有设备和 ADB 时，签名、安装、触发该方法后，可查看 `adb -s SERIAL logcat -d -s AQE:I '*:S'`。
将 SERIAL 替换为设备序列号；ADB 是可选验证工具，不是 AQE 运行依赖。

## 签名和安装

使用自己的 JKS/PKCS12 密钥库及其中的私钥别名。非交互式调用前，环境变量 AQE_KS_PASS
必须已由调用环境设置；没有变量时仅在交互终端中提示输入。不要把密码写进补丁或命令参数。

```sh
java -jar aqe.jar sign edited.apk --ks release.p12 --alias release -o signed.apk
java -jar aqe.jar verify signed.apk
```

也可以直接让 apply 输出签名后的 APK，省去单独 sign：

```sh
java -jar aqe.jar apply app.apk --patch patch.json --ks release.p12 --alias release -o signed.apk
java -jar aqe.jar verify signed.apk
```

换用密码变量名：`--ks-pass-env MY_STORE_PASSWORD`；私钥密码不同则加 `--key-pass-env MY_KEY_PASSWORD`。
可选设备验证：`adb -s SERIAL install -r signed.apk`。更新已有应用需要签名身份兼容；
AQE 无法恢复原应用私钥。若安装失败，不要自动卸载原应用或清除其数据。

## 批量补丁格式：version 1

顶层只有 `version`（整数 1）和 `operations`（非空数组）。一项操作是一个 JSON 对象。
不允许注释、尾随逗号、重复键、未知字段、未知操作；可选字段请省略，不要写 null。

| op | 必填字段 | 可选字段与默认值 |
| --- | --- | --- |
| file.add | path:字符串，source:本地文件路径 | 无；目标必须不存在 |
| file.replace | path:字符串，source:本地文件路径 | 无；目标必须存在 |
| file.delete | path:字符串 | 无；目标必须存在；不递归删除目录 |
| dex.add | inputs:非空路径数组 | dex:"classes.dex"；api:当前 APK minSdk；libraries:[] |
| dex.replace | inputs:非空路径数组 | api:当前 APK minSdk；libraries:[] |
| dex.delete | classes:非空类名数组 | allowReferenced:false；全部类必须存在；默认最终仍有直接引用则整批失败 |
| manifest.set | label / versionName / versionCode 至少一个 | label、versionName 是字符串；versionCode 是正整数 |
| resource.set-string | id:字符串，如 "0x7f010000"；value:字符串 | config:""（默认配置），例如 "en" 或 "-en" |

完整的八种操作示例：`java -jar aqe.jar examples batch`。

文件路径规则：`path` 是 APK 内部路径，使用 `/`，区分大小写，不是本地路径。
JSON 中的 source、inputs、libraries 相对于 JSON 所在目录；支持绝对路径。
命令行中的 APK、输出、密钥库、--lib 路径相对于当前工作目录。路径含空格时加引号。
Windows JSON 路径优先用 `/`；使用反斜杠时必须按 JSON 规则转义。

顺序规则：后续操作看到前面操作的结果，可先新增再修改/删除，或先删除再新增。
同一文件的整文件替换覆盖此前结构化修改；之后的结构化操作使用新的文件内容。
每个受影响 DEX 最后只序列化一次；其余条目保留原压缩数据。
任意操作、写回或签名失败，都不发布半成品，也不覆盖输入或已有输出。

## 安全删除类

```sh
java -jar aqe.jar dex delete app.apk com.example.Legacy 'Lcom/example/Unused;' -o edited.apk
```

`dex delete` 和 apply 的 `dex.delete` 默认都检查整批操作的最终状态。扫描所有 DEX 中保留的类，
检查继承/接口、字段/方法签名、数组元素类型、指令引用、注解及其值、异常处理类型、
调试局部变量类型、method handle / method type / call site；同时检查 Manifest 中字面量组件类名。
即使引用只存在于未执行的方法中，也会拒绝删除。

有引用时退出码为 1，错误包含被删类、删除操作序号、引用所在 DEX/类/方法/指令位置，最多列 30 条。
整批操作不输出 APK、不签名、不覆盖已有输出；`--force` 仅允许覆盖输出文件，不能绕过引用检查。

需要删除被引用的类时，先导出并编辑调用方，移除或改写相关引用，再一起提交：

```sh
java -jar aqe.jar dex export app.apk com.example.Main -o Main.smali
# 编辑 Main.smali，移除对 Legacy 的调用、签名等直接引用
java -jar aqe.jar apply app.apk --patch patch.json -o edited.apk
```

patch.json（以下两项顺序互换也可以）：

```json
{
  "version": 1,
  "operations": [
    {"op": "dex.delete", "classes": ["com.example.Legacy"]},
    {"op": "dex.replace", "inputs": ["Main.smali"]}
  ]
}
```

相互引用的类可以一起删除；删除后又新增同名类，则最终仍存在，不触发删除保护。
Manifest 组件声明需另行准备修改后的二进制 Manifest，通过 file.replace 提交；
manifest.set 目前不编辑组件声明。

检查不能保证任意运行时链接都成功：反射字符串、JNI、动态加载、布局 XML 的自定义 View、
资源字符串等不在范围内，也不验证替换/重新添加类后的方法和字段兼容性。
单独用原始文件操作替换/删除整个 DEX 而不使用 dex.delete，不启用此保护。

### 批量列表与显式豁免

类名较多时，可通过 UTF-8 文件输入，每行一个点分类名或描述符；忽略空行和以 `#` 开头的注释行。
文件中的类名会与命令行 CLASS 参数合并：

```sh
java -jar aqe.jar dex delete app.apk --classes-file removal.txt -o edited.apk
```

做删类实验时，可显式使用 `--allow-referenced`，允许此次选定的类在仍有直接引用时被删除。
这可能造成运行时类解析失败，需重新测试 APK；`--force` 仍只控制覆盖已有输出。

```sh
java -jar aqe.jar dex delete app.apk --classes-file removal.txt --allow-referenced -o edited.apk
```

批处理的对应字段为布尔值 `allowReferenced`，默认是 `false`，不能使用字符串或 null：

```json
{
  "version": 1,
  "operations": [
    {"op": "dex.delete", "classes": ["com.example.Experimental"], "allowReferenced": true},
    {"op": "dex.delete", "classes": ["com.example.Legacy"]}
  ]
}
```

豁免只适用于该条操作删除的类。上例中 `Legacy` 仍受保护，有剩余直接引用时整批失败，
两条操作顺序互换不影响保护范围。同一个类若先删除、再新增、再删除，使用它最后一次删除的设置；
最终仍存在的类不触发删除检查。

## 输入选择与功能边界

- Smali 文件/目录：适合精确修改已有类。目录始终按 Smali 处理，不是 JVM class 目录。
- DEX：导入其中所有类。新增要求全不存在；替换要求全存在，不自动做混合 upsert。
- .class / JAR：通过内置 D8 转 DEX；JAR 中所有类均视为待导入程序类。
- .java：不直接接受。先用 JDK javac 编译，再导入 .class 或 JAR；运行 AQE 本身只需 JRE。
  完整流程见 `java -jar aqe.jar examples java-class`。
- --lib / libraries 是 D8 的库声明，不会打进 APK，不会自动补齐依赖。
- 类名可写 `com.example.Main` 或 `Lcom/example/Main;`；shell 中描述符需引号保护分号。
- 只编辑标准 DEX 035–040，拒绝 041+ 容器。新增类写入指定的现有 DEX，不自动创建或拆分。
- 不自动修复引用或 Manifest 组件声明。删除类使用 `dex delete` 或 apply 的 dex.delete，
  默认有剩余直接引用会失败；显式豁免只影响指定类。删空的 DEX 保留，不自动重排 DEX 文件名。资源 ID 引用不检查。
- file 操作复制原始数据：文本布局 XML、原始 nine-patch 不会自动编译；新增 res 文件也不会创建资源 ID。
- 字符串编辑只支持已有资源 ID 的已有简单字符串配置，不支持新增 ID、复杂样式或复数字符串。
- 修改输出会移除旧签名。签名创建 v2/v3，并在 minSdk < 24 时启用 v1；不生成 v4 idsig。
- ZIP 中未压缩文件按 4 字节对齐，未压缩 so 按 16 KiB 对齐；不转换 ELF 段布局或 ABI。
- 没有 dry-run、JSON 状态输出、自动 Java 反编译、自动插桩、split APK 集合处理或设备部署命令。

## 失败诊断与 agent 调用约定

运行编辑命令前核对输入并选择新输出路径。模板导出到 stdout；shell `>` 会覆盖其目标文件。
不要让重定向目标与输入 APK/JAR 或需要保留的文件同名。

退出码：0=成功或帮助，1=操作失败，2=命令行语法错误。
普通操作结果是给人读的文本，程序应以退出码判断成功，不要依赖输出句子的固定格式。
help / examples 输出到 stdout；操作失败写 stderr；Smali/D8 的底层诊断可能混用输出流。
`examples TOPIC --file NAME` 的成功输出是纯文件内容，可直接重定向保存。

| 错误 | 检查和处理 |
| --- | --- |
| Unknown field / Unknown operation | 对照 op 和字段表，检查拼写，不要忽略错误 |
| Operation #N (...) | 第 N 项执行失败，从 1 计数；整个补丁尚未发布 |
| Entry/Class not found | 核实 APK、条目/类名及前序删除；缺失类需要 dex.add |
| Entry/Class already exists | 核实目标；已有类用 dex.replace，不要重复新增 |
| Refusing class deletion | 按错误位置修复/删除调用方或 Manifest 组件声明，在同一批补丁提交；--force 不能绕过 |
| No classes found / Smali assembly failed | 检查输入类型、Smali 语法、目录是否包含正确文件 |
| Target DEX must exist / DEX reference limit exceeded | 从 info 输出选择已有且容量足够的 DEX；不会自动拆分 |
| Expected one resource / not a simple string | 核实资源 ID、准确 config 和资源类型，不能猜 ID |
| Output must differ from input / output exists | 更换输出路径；只有确认替换已有输出时才加 --force |
| Set AQE_KS_PASS / no private key for alias | 检查密码环境变量、密钥库格式及私钥别名 |

必要时用 `java -Daqe.debug=true -jar aqe.jar ...` 查看异常堆栈。
文件布局、资源值、调用引用需要在编辑后验证；成功生成 APK 不代表应用行为必然正确。
下方命令参考直接由当前 CLI 生成，列出所有支持的参数，不需要联网或源码仓库。
