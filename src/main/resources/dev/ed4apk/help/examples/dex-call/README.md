# dex-call：新增类并在已有类中调用

前置条件：app.apk 中有 classes.dex、com.example.Main，没有 com.example.ed4apk.Helper。
使用 info / dex list 获取真实名称并替换示例中的名字。输入都放在 patch.json 同一目录。

```sh
java -jar ed4apk.jar info app.apk
java -jar ed4apk.jar dex list app.apk
java -jar ed4apk.jar dex export app.apk com.example.Main -o Main.smali
java -jar ed4apk.jar examples dex-call --file Helper.smali > Helper.smali
java -jar ed4apk.jar examples dex-call --file patch.json > patch.json
```

先编辑 Main.smali：将下方 call-site.txt 的一行代码插入确定会执行的普通方法中，
例如 onCreate 的 invoke-super 之后。它没有参数/返回值，不增加调用方寄存器需求。
保留原类描述符及所有其他成员，不能用片段替换整个类，也不要向 abstract/native 方法插入指令。
call-site.txt 是片段说明，不可作为 dex.add/replace 的类输入。

```sh
java -jar ed4apk.jar apply app.apk --patch patch.json -o edited.apk
java -jar ed4apk.jar dex export edited.apk com.example.Main -o Main.after.smali
java -jar ed4apk.jar dex export edited.apk com.example.ed4apk.Helper -o Helper.after.smali
```

预期：新 Helper 类存在，Main.after.smali 保留原逻辑并包含新增调用。签名、安装并触发该方法后，
Logcat 中出现 tag=ed4apk、message=Helper called。ed4apk 不会自动选择或注入调用点。
签名命令：`java -jar ed4apk.jar sign edited.apk --ks release.p12 --alias release -o signed.apk`，
密码从 ed4apk_KS_PASS 环境变量或交互终端读取。
