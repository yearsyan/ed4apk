# AQE — APK Quick Editor

独立 Java APK 编辑工具。所有库依赖打进一个 `aqe.jar`，运行需要 **Java 11 或更高版本**，
无需 Android SDK、Android Studio、Gradle 安装或额外 JAR。JRE 本身不包含在 JAR 内。

## 构建

构建与测试工具链使用 JDK 21，应用源码通过 `--release 11` 编译为 Java 11 字节码（版本 55）。
使用已构建的 JAR 只需要 Java 11+，不需要 JDK 21。Gradle Wrapper 会下载固定版本的 Gradle，首次构建需要访问
Google Maven、Maven Central 和 Gradle Plugin Portal。

```sh
./gradlew build
java -jar build/libs/aqe.jar --help
```

Windows 使用 `gradlew.bat build`。发布时只分发 `build/libs/aqe.jar`。
依赖版本记录在 `gradle.lockfile` 中；运行时不会下载依赖。
Zipflinger 固定为支持 Java 11 的 9.2.1；构建会检查 fat JAR 的基础类字节码版本，
防止依赖升级后再次产生 `UnsupportedClassVersionError`。

可指定其他运行时执行现有的独立 JAR 集成测试（测试框架本身仍由 JDK 21 运行）：

```sh
./gradlew test -PaqeTestJava=/path/to/java11/bin/java
```

## 自动发布

向 GitHub 推送 tag 会触发 [Build and release](.github/workflows/release.yml)：使用 JDK 21 构建，
运行全部测试（独立 JAR 测试使用 Java 11），检查 Java 11 字节码兼容性，通过后自动创建 GitHub Release，
上传 `aqe.jar` 和 `aqe.jar.sha256`。不需要配置额外密钥，发布使用工作流内置的 `GITHUB_TOKEN`。

```sh
git tag -a v0.1.0 -m "AQE 0.1.0"
git push origin v0.1.0
```

任何 tag 都会触发；推荐使用 `v1.2.3` 格式。JAR 内的版本号及 `--version` 取 tag 去掉开头 `v` 后的值；
包含 `-` 的 tag（如 `v0.2.0-rc1`）发布为预发布版本。普通分支 push 不发布 Release。
在 Actions 页面手动运行此工作流只构建、测试并保存产物，不创建 Release。
每个版本使用新 tag；构建或测试失败时不会发布。

下载校验文件后，可在同一目录执行 `sha256sum --check aqe.jar.sha256`（Linux）或
`shasum -a 256 --check aqe.jar.sha256`（macOS）。本地可用 `./gradlew build -PreleaseVersion=0.1.0`
覆盖构建版本号。

## 命令帮助

```sh
java -jar aqe.jar --help
java -jar aqe.jar --help-all
java -jar aqe.jar help apply
java -jar aqe.jar dex add --help
java -jar aqe.jar examples
java -jar aqe.jar examples dex-call
```

各级 help 包含参数、默认值和操作示例。`apply --help` 内置完整 JSON 示例，以及输入类型、
相对路径、执行顺序、失败保护和签名规则；离线使用也无需另查 README。
退出码为 `0`（成功/帮助）、`1`（操作失败）、`2`（命令行参数错误）。

**交给 LLM 使用时，建议先读取 `--help-all`**：一次输出完整中文指南、任务选择、端到端示例、
输入/输出约定、全部补丁字段、错误处理、功能边界，以及从当前 CLI 自动生成的所有子命令帮助。
文档和示例内置在 fat JAR，无需仓库、额外文件或联网。

`examples` 提供四组可复制流程：`files`（文件增改删）、`dex-call`（Smali 新增类并调用）、
`java-class`（Java 编译后导入）、`batch`（八种操作混合并签名）。每组说明前置条件、
准备步骤、文件内容和预期结果。类名、资源 ID 和条目名需按真实 APK 调整。

`--file` 只输出文件原文，可直接保存，无标题或日志混入：

```sh
java -jar aqe.jar examples dex-call --file Helper.smali > Helper.smali
java -jar aqe.jar examples dex-call --file patch.json > patch.json
```

该例的 `Main.smali` 需先从目标 APK 导出、插入调用后再 apply；完整步骤可在
`examples dex-call` 中查看。示例资源源文件位于 `src/main/resources/dev/aqe/help/`。

## 文件替换

```sh
java -jar aqe.jar info app.apk
java -jar aqe.jar replace app.apk \
  assets/config.json=./config.json \
  res/drawable/logo.png=./logo.png \
  lib/arm64-v8a/libdemo.so=./libdemo.so \
  -o edited.apk
```

同一次调用可以替换或新增多个条目。未修改条目保留原压缩数据，避免全量重新压缩。
导出时移除旧 APK Signing Block、旧 JAR 签名及旧 source stamp，保留其他 `META-INF` 内容。
未压缩条目按 4 字节对齐；未压缩的 `lib/*/*.so` 按 16 KiB 对齐。替换的 so 和
`resources.arsc` 以未压缩方式写入。ZIP 对齐不改变 ELF 段布局，也不检查或转换 so 的 ABI。

文件替换不会编译源文件。替换布局 XML 必须提供 Android 二进制 XML，不能直接塞入文本 XML；
Nine-patch 等编译资源同样需要提供正确的编译后数据。

## 一次执行多条补丁

`apply` 将文件、类、Manifest 和字符串资源操作放在同一个 JSON 中，按顺序执行，最后统一写出 APK：

```sh
java -jar aqe.jar apply app.apk --patch patch.json -o edited.apk

# 同一条命令完成修改与签名；密码读取方式与 sign 相同
java -jar aqe.jar apply app.apk --patch patch.json \
  --ks release.p12 --alias release -o signed.apk
```

`patch.json` 示例（根据目标 APK 调整条目名、类名和源文件）：

```json
{
  "version": 1,
  "operations": [
    {"op": "file.replace", "path": "assets/config.json", "source": "config.json"},
    {"op": "file.add", "path": "assets/new.json", "source": "new.json"},
    {"op": "file.delete", "path": "assets/obsolete.json"},
    {"op": "dex.add", "inputs": ["Helper.smali"], "dex": "classes.dex"},
    {"op": "dex.replace", "inputs": ["Main.smali"]},
    {"op": "dex.delete", "classes": ["com.example.Legacy"]},
    {"op": "manifest.set", "label": "新名称", "versionName": "2.0", "versionCode": 2},
    {"op": "resource.set-string", "id": "0x7f010000", "config": "en", "value": "New name"}
  ]
}
```

| 操作 | 参数 | 行为 |
| --- | --- | --- |
| `file.add` | `path`, `source` | 新增 APK 条目，要求目标不存在 |
| `file.replace` | `path`, `source` | 替换已有条目，适用于资源、assets、so 等 |
| `file.delete` | `path` | 删除已有条目；单条操作处理一个文件，不递归删除目录 |
| `dex.add` | `inputs`, 可选 `dex`, `api`, `libraries` | 导入新类；默认写入现有 `classes.dex` |
| `dex.replace` | `inputs`, 可选 `api`, `libraries` | 替换已有同名类，保留各类所在 DEX |
| `dex.delete` | `classes` | 安全删除指定类；最终仍有直接引用则拒绝整批输出；支持点分名和 `L...;` 描述符 |
| `manifest.set` | 至少一个 `label`, `versionName`, `versionCode` | 修改 Manifest 字段 |
| `resource.set-string` | `id`, `value`, 可选 `config` | 修改已有简单字符串；默认配置为 `""` |

`inputs`、`classes`、`libraries` 均为字符串数组。类输入支持 Smali 文件/目录、DEX、`.class`、JAR，
不直接接受 `.java`。`api` 是正整数，默认使用执行到该操作时 APK 的 minSdk。
`source`、`inputs` 和 `libraries` 中的相对路径均相对于 **JSON 文件所在目录**；
命令行 APK、输出和密钥库路径则相对于当前工作目录。

顺序和失败语义：

- 后面的操作可以修改或删除前面新增的文件、类；删除后也可重新新增。
- 修改同一 DEX 的多条类操作会合并，该 DEX 最后只序列化一次；未修改条目保留原压缩数据。
- 整文件替换会覆盖此前对该文件的类/资源/Manifest 修改，后续结构化操作读取替换后的内容。
- 缺失的替换/删除目标、重复新增的目标、未知字段/操作、重复 JSON 键均报错；执行错误会标出操作序号。
- 任意操作、最终写回或签名失败，均不发布部分结果，已有输出即使加 `--force` 也保留。
- 最终必须保留 `AndroidManifest.xml`。删空的 DEX 保留为空 DEX，不自动重排 multidex。

例如，先导出并编辑 `Main.smali`，在其目标方法中添加对 `Helper` 的调用，再将
`dex.add Helper.smali` 与 `dex.replace Main.smali` 放进同一补丁即可。
调用示例（假设辅助方法为无参数的 `public static run()V`）：

```smali
invoke-static {}, Lcom/example/Helper;->run()V
```

工具不会自动插入调用点或清理引用。`dex.delete` 会拒绝仍有直接引用的删除，需在同一批补丁中
修改或删除相关调用方。`file.delete res/...` 不会自动删除资源表 ID。

## DEX 类编辑

```sh
java -jar aqe.jar dex list app.apk
java -jar aqe.jar dex export app.apk com.example.Main -o Main.smali

# 编辑 Main.smali 后，替换同名类；可同时提供多个输入
java -jar aqe.jar dex replace app.apk Main.smali -o edited.apk

# 新增类；类描述符不能与 APK 中已有类重复
java -jar aqe.jar dex add app.apk ./new-smali/ --dex classes.dex -o edited.apk

# 删除一个或多个类；存在剩余直接引用时失败，不写出 APK
java -jar aqe.jar dex delete app.apk com.example.Legacy 'Lcom/example/Unused;' -o edited.apk

# 也可从 DEX 导入，或通过内置 D8 转换 JVM class / JAR
java -jar aqe.jar dex add app.apk extra.dex -o edited.apk
java -jar aqe.jar dex add app.apk Extra.class -o edited.apk
java -jar aqe.jar dex add app.apk extra.jar --lib ./android.jar -o edited.apk
```

类名支持 `com.example.Main` 和 `'Lcom/example/Main;'` 两种格式。目录输入按 Smali 目录处理。
DEX/JAR 输入导入其中所有类；替换操作要求这些类全部已经存在，新增操作要求它们全部不存在。
Smali/D8 的 API 级别默认读取 APK 的 minSdk，可用 `--api` 显式指定。
`--lib` 可重复传入 D8 所需的库声明；不会自动下载 Android API 或应用依赖。

只重写受到影响的 DEX，其余 DEX 的压缩数据原样复制。同一次替换涉及同一 DEX 的多个类时，
该 DEX 只序列化一次。DEX 类的引用仍必须在目标运行环境中有效。

当前版本处理传统 DEX 035–040，明确拒绝 041+ 容器。新增类写入指定的**现有** DEX；
索引溢出时报错，不自动拆分 multidex，不自动修改主 DEX 放置策略。
复杂 Java 源码编译及依赖解析不在当前命令范围内。

### 删除保护

`dex delete` 和批处理 `dex.delete` 共用检查：按整批操作的**最终状态**扫描所有 DEX 中保留的类，
检查继承、接口、字段/方法签名、数组元素类型、指令中的类型/字段/方法引用、注解及其值、
异常处理类型、调试局部变量类型、method handle / method type / call site。
也会检查 Manifest 中以字面量声明的 Application、Activity、Service 等组件类名。
不做可达性裁剪，即使调用代码暂时未执行，也会阻止删除。

例如先将 `Main.smali` 中对 Legacy 的调用修好，再执行下面的补丁；两项顺序互换也可以：

```json
{
  "version": 1,
  "operations": [
    {"op": "dex.delete", "classes": ["com.example.Legacy"]},
    {"op": "dex.replace", "inputs": ["Main.smali"]}
  ]
}
```

相互引用的类可以一起删除；删除后又新增同名类，则该类最终仍存在，不触发删除保护。
失败返回退出码 1，列出被删除类、操作序号、引用所在 DEX/类/方法/指令位置（最多 30 条明细），
不发布 APK、不开始签名，也不覆盖已有输出。`--force` 不能跳过检查。

保护范围是**显式删除类后残留的直接引用**，不是完整的运行时链接验证。
反射字符串、JNI、动态加载、布局 XML 中的自定义 View、资源字符串等不在检查范围；
重新添加/替换类后的方法或字段兼容性也不检查。仅通过原始文件操作替换/删除整个 DEX
而没有 `dex.delete` 时，不启用此保护。工具无法保证任意 APK 编辑后绝无运行时链接错误。

## 编译资源和 Manifest

```sh
# 修改指定资源 ID 的默认配置；保留其他语言版本
java -jar aqe.jar resource set-string app.apk 0x7f010000 '新名称' -o edited.apk

# 只修改英文配置，配置必须已存在
java -jar aqe.jar resource set-string app.apk 0x7f010000 'New name' --config en -o edited.apk

java -jar aqe.jar manifest set app.apk \
  --label '新名称' --version-name 2.0 --version-code 2 -o edited.apk
```

资源编辑使用 ARSCLib 直接修改二进制资源表，当前命令支持简单字符串值。
`manifest set --label` 将 application label 设为字面量，会替换原来的资源引用；
需要保留多语言名称时请修改资源表中相应配置的字符串。

## 签名

编辑命令输出的是未签名 APK。准备好自己的 JKS 或 PKCS12 密钥库后：

```sh
# 可以交互输入密码，也可以事先在环境变量 AQE_KS_PASS 中设置
java -jar aqe.jar sign edited.apk --ks release.p12 --alias release -o signed.apk
java -jar aqe.jar verify signed.apk
```

`--ks-pass-env` 可指定其他密码环境变量名；私钥密码不同于库密码时使用 `--key-pass-env`。
命令行不接受明文密码参数。签名使用 apksig，并在发布输出文件前验证签名与 ZIP 对齐。
默认依据 minSdk 选择 v1，并生成 v2/v3 签名。当前不生成 v4 idsig 或配置签名轮换。
能否覆盖安装原应用取决于签名身份等 Android 安装规则。

所有写入使用临时文件完成后再发布到输出路径，禁止直接覆盖输入。
默认不覆盖已有输出，确需替换时加 `--force`。

## 技术栈与验证

- Java 11+ 运行时、JDK 21 构建工具链、picocli、Gradle Shadow；不使用 Android Gradle Plugin。
- Zipflinger：原压缩数据复制、APK 条目对齐。
- Google smali / dexlib2 / baksmali：Smali 汇编、类导出和 DEX 写回。
- ARSCLib：编译资源与二进制 Manifest。
- apksig：签名及验证；D8：JVM 字节码转 DEX。
- Jackson：严格解析批量补丁 JSON。

`./gradlew test` 构造包含两个 DEX、资源配置和文件条目的 APK，验证替换、新增、
未修改条目的压缩数据保持不变、输出失败保护、资源配置保留和签名。
批处理测试覆盖新增辅助类并由已有类调用、删除、顺序覆盖、错误定位、整批回滚和可选签名。
测试还会在移除 SDK/classpath 环境配置后，通过单独的 `java -jar` 进程执行操作。
删除保护另有合成 APK 测试，覆盖跨 DEX 引用、各种引用位置、Manifest、同批修复/互相引用类一起删除、
整 DEX 覆盖、原子失败及独立 JAR 命令。设备测试独立运行，不由 `gradlew test` 自动连接设备。

### 设备运行测试

`device-test/run.py` 会生成含双 DEX、资源表、assets 和 JNI so 的独立测试 APK，先安装运行原始版本，
再通过发布的 fat JAR 执行 10 条补丁、签名、覆盖安装并运行。应用自身检查新增类调用（跨 DEX）、
删除类不可加载、assets 增改删、资源文件与字符串、Manifest、so 的真实 JNI 返回值。
脚本将报告、命令日志、应用进程日志和截图保存到 `build/device-test/<时间>/`。

```sh
python3 device-test/run.py \
  --serial 192.168.9.127:10000 \
  --android-jar /path/to/android-sdk/platforms/android-34/android.jar \
  --clang /path/to/ndk/toolchains/llvm/prebuilt/HOST/bin/aarch64-linux-android21-clang
```

设备测试额外需要 Python 3、ADB、JDK 21+、Android API 34+ 的 `android.jar` 和 NDK arm64 编译器，
仅用于生成及部署测试样本，**不改变 AQE 本身无 SDK 运行依赖的性质**。
测试使用临时自签密钥；运行 AQE 子进程时会移除 SDK/classpath 环境配置。
当前 JNI 测试样本支持 `arm64-v8a`。脚本要求显式设备序列号，并拒绝覆盖已存在的
`dev.aqe.smoketest`；默认测试后卸载本次安装的测试应用，可用 `--keep-installed` 保留。

2026-09-20 已在 `192.168.9.127:10000` 的 `P1_EVB`（Android 14 / API 34，arm64，4 KiB 页）
通过原始版本 13 项、补丁版本 14 项运行检查，以及 v1/v2/v3 签名验证、覆盖安装。
补丁后的 JNI 返回值由 `7` 变为 `42`；`extractNativeLibs=false` 下直接从 APK 加载 so 成功。
报告和截图见 [设备验证记录](docs/validation/2026-09-20/report.json)。
尚未覆盖其他 Android 版本、16 KiB 页设备、真实大型/加固/拆分 APK。

第三方依赖说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
