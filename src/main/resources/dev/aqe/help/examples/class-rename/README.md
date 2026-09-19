# class-rename：查询、预览、改名

前置条件：APK 中存在 com.example.Main，com.example.Home 未定义且未被静态引用。
替换为实际类名，保留原包名。无需提供 Java 或 Smali 文件。

```sh
java -jar aqe.jar dex refs app.apk com.example.Main --json
java -jar aqe.jar dex rename app.apk com.example.Main com.example.Home --dry-run --json
java -jar aqe.jar dex rename app.apk com.example.Main com.example.Home -o renamed.apk
java -jar aqe.jar dex refs renamed.apk com.example.Main --json
java -jar aqe.jar dex refs renamed.apk com.example.Home --json
```

也可导出下方补丁模板，调整后与其他操作一起执行：

```sh
java -jar aqe.jar examples class-rename --file patch.json > patch.json
java -jar aqe.jar apply app.apk --patch patch.json -o renamed.apk
```

两种写法择一。输出均未签名，安装前用 sign 或 apply 的签名选项。
跨 DEX typed references、Manifest 与已知资源 XML 类名槽位会更新；
反射/JNI、字符串元数据、自定义 XML 约定及 style/theme 继承不处理，完整边界见 --help-all。
内部类需单独改名；连续 A→B→C 按顺序写两条操作。
