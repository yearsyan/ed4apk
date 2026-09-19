# batch：混合操作与签名

这是全部八种操作的格式模板，不是适用于任意 APK 的补丁。先按目标 APK 调整：

- Helper.smali 必须是不存在的新类；Main.smali 必须来自目标已有类的完整导出并已编辑。
  可先运行 `java -jar aqe.jar examples dex-call` 获取完整准备流程及 Helper 文件。
- com.example.Legacy、assets/config.json、assets/obsolete.txt 必须存在；assets/new.txt 必须不存在。
  删除 Legacy 前先在 Main.smali 或其他补丁类中移除相关引用。
- 自行准备 config.json、new.txt。它们相对于 patch.json 所在目录。
- 0x7f010000 只是示例资源 ID；核实它确实是简单字符串且有 en 配置，不需要时移除该操作。
- classes.dex 必须存在。AQE 不会自动新增 DEX 或拆分超限 DEX。

```sh
java -jar aqe.jar examples batch --file patch.json > patch.json
```

修改好模板、准备所有输入后执行。密码变量 AQE_KS_PASS 必须已由调用环境设置：

```sh
java -jar aqe.jar apply app.apk --patch patch.json --ks release.p12 --alias release -o signed.apk
java -jar aqe.jar verify signed.apk
java -jar aqe.jar info signed.apk
java -jar aqe.jar dex list signed.apk
```

省略 --ks/--alias 时输出未签名 APK。某项操作或签名失败，整批不发布，不会留下部分修改。
同一 DEX 的类修改只在最后写回一次。若加入整文件 DEX / resources.arsc / Manifest 替换，
它会覆盖此前对该文件的结构化修改；请明确安排执行顺序。
