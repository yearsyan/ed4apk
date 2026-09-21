# files：文件增改删

前置条件：app.apk 内已有 assets/config.json、assets/obsolete.txt，且没有 assets/new.txt。
请按真实 APK 调整 path；不需要删除时直接删去 file.delete 那一项。
source 相对于 patch.json 所在目录。下面在同一目录保存三个输入文件与补丁：

```sh
java -jar ed4apk.jar examples files --file patch.json > patch.json
java -jar ed4apk.jar examples files --file config.json > config.json
java -jar ed4apk.jar examples files --file new.txt > new.txt
java -jar ed4apk.jar apply app.apk --patch patch.json -o edited.apk
```

预期：config.json 内容替换，new.txt 新增，obsolete.txt 消失。edited.apk 未签名。
给 so 或资源文件替换时，将 path 改为实际 APK 内部路径，source 改为正确 ABI 的 so 或编译后资源。
最简单的非批处理写法为 `java -jar ed4apk.jar replace app.apk assets/config.json=config.json -o edited.apk`；
该命令对不存在的条目执行新增，不支持删除。
