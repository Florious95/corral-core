# 知识基底 · fg-service（系统编译产物）

## 0. 任务（taskbook.yaml#fg-service）
- 目标：安卓前台服务（004/011）：有活跃订阅时保持连接、blocked/done 状态变化发系统通知、断连静默重连（conn 层已管，服务只反映）；零 Google 服务依赖。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*Service*"'`
- 写范围：`app/app/src/main/java/**/service/`、`app/app/src/test/`、AndroidManifest.xml（仅 service/权限声明）。红线：不动 conn/workspace/session/termview 包实现。

## 1. 架构基
- 分层：`StateWatcher`（纯 JVM 可测：消费 conn 层 listing/list_delta 流，检测会话状态**沿变化**（→blocked、→done 两种沿触发通知；同状态重复推送抑制；unknown 不通知）+ `MirrorForegroundService`（薄 Android 层：startForeground + 通知渠道 + 生命周期绑定 ConnectionManager）+ `NotificationHelper`（渠道：状态通知/常驻通知两条）。单测全部打在 StateWatcher（验收 --tests "*Service*"）。
- 生命周期策略（004 电量裁定）：仅在"有活跃订阅或用户开启后台守望"时运行前台服务；服务被系统杀→冷启动重连即恢复（无状态，没有丢失可言）。
- 通知点按→深链到对应会话页（PendingIntent 路由）。
- Android 14+ 前台服务类型声明：`dataSync`（manifest 属性），POST_NOTIFICATIONS 运行时权限请求放服务启动入口（UI 侧接线归后续，留 TODO 接口即可）。

## 2. 现场基
- conn 层 API 已定稿（80 测）；workspace-ui 的 WorkspaceViewModel 已示范如何消费 listing/delta 流（可参考但不依赖）。
- **共享编译单元纪律**：session-ui 席位并行施工 :app——每次落盘保持整模块可编译，落盘后跑 `:app:compileDebugKotlin` 自检。
- 构建 `bash -lc`；compileSdk 36。

## 3. 需求基（指针）
1. requirement-base/entries/004-后台策略-无状态免疫.md（本任务的裁定原文：前台服务+推送双轨里的安卓路线）
2. requirement-base/entries/003-对话体验四标准.md（第四标准"需要时被唤醒"——通知语义）
3. requirement-base/entries/012-工作区聚合状态规则.md（注意力优先级——通知重要性排序同源）

## 4. 经验基
- 红测先行：→blocked 沿触发一次且仅一次、同状态不重复、unknown 永不通知、会话消失清除其通知，各一条。
- 静默失效猎杀：通知发送失败要落日志可判定，不静默吞。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
