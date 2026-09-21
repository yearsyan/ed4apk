# Manifest 组件与精细节点编辑

支持 activity、service、receiver、provider，以及 activity-alias。
先 `manifest show` 确认目标，再修改模板。声明操作不会新增、重命名或删除 DEX 类。

activity.json 是一个完整节点，包含 deep link intent-filter 和 meta-data。
前置条件：Manifest 中不存在 ExtraActivity，且对应的 Activity 实现已在 APK 中（也可用 apply 的 dex.add 加入）。

```sh
java -jar ed4apk.jar manifest show app.apk
java -jar ed4apk.jar examples manifest --file activity.json > activity.json
java -jar ed4apk.jar manifest add app.apk --node activity.json -o added.apk
java -jar ed4apk.jar manifest update added.apk --component activity --name .ExtraActivity --attribute android:enabled=false -o updated.apk
java -jar ed4apk.jar manifest show updated.apk --component activity --name .ExtraActivity
java -jar ed4apk.jar manifest replace updated.apk --component activity --name .ExtraActivity --node activity.json -o replaced.apk
java -jar ed4apk.jar manifest delete replaced.apk --component activity --name .ExtraActivity -o deleted.apk
```

patch.json 展示四种操作，不依赖 activity.json。运行前修改其中的示例名称：
MainActivity 必须存在，且 android:name 原始值是 `.MainActivity`，其下有名为 mode 的 meta-data；
LegacyReceiver 必须存在；SyncService 声明必须不存在，其实现应已在 APK 内。
provider 新增时需提供实际的 android:authorities；activity-alias 应提供 android:targetActivity。

```sh
java -jar ed4apk.jar examples manifest --file patch.json > patch.json
java -jar ed4apk.jar apply app.apk --patch patch.json -o edited.apk
```

update 仅修改列出的属性，removeAttributes 删除列出的属性；replace 替换整棵子树。
XPath 按原始属性值匹配，支持位置谓词；组件名称选择器则会展开 `.Main` / `Main` / `包名.Main`。
修改必须唯一命中；show 返回数组，允许零个或多个结果。show 的单个元素对象可保存为 --node。
字符串通常按 Android XML 规则编码；要强制字符串，用 `{"type":"STRING","value":"true"}`。
详见 `--help-all`；输出未签名，安装前需签名。
