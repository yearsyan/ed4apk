# java-class：Java 编译后导入

此流程的 javac 步骤需要 JDK；AQE 不接受 .java。示例是纯 Java，不依赖 Android API。
前置条件：app.apk 内有 classes.dex、com.example.Main，没有 com.example.aqe.Helper。
按真实 APK 修改调用方类名，所有路径在同一工作目录下。

```sh
java -jar aqe.jar examples java-class --file Helper.java > Helper.java
javac --release 8 -d classes Helper.java
java -jar aqe.jar dex export app.apk com.example.Main -o Main.smali
java -jar aqe.jar examples java-class --file patch.json > patch.json
```

编辑 Main.smali，把 call-site.txt 的调用放入已确认会执行的方法中。
这个片段使用 v0 接收 int 结果。务必先分析寄存器是否可用，必要时增加/调整 locals，
保持原方法的数据流正确，并决定如何使用返回值；不要盲目覆盖 v0。

```sh
java -jar aqe.jar apply app.apk --patch patch.json -o edited.apk
java -jar aqe.jar dex export edited.apk com.example.aqe.Helper -o Helper.after.smali
```

预期：Helper.value() 返回 42，调用方引用它。`.class` 路径指具体文件；目录输入会被当成 Smali。
多个 class 可逐个传入，也可先打 JAR：

```sh
jar --create --file helper.jar -C classes .
java -jar aqe.jar dex add app.apk helper.jar -o with-helper.apk
```

上述 JAR 命令是独立的新增类示例：输入仍是原 app.apk；仅新增类，不会修改调用方。
Java 源码若引用 Android API 或其他依赖，javac 需要相应 classpath，D8 可用 --lib / libraries 提供库声明。
AQE 不下载 SDK/依赖，不把库声明一起打包。输出均未签名。
