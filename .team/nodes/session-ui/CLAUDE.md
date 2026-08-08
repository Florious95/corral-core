# 知识基底 · session-ui（系统编译产物）

## 0. 任务（taskbook.yaml#session-ui）
- 目标：会话页（003 四标准的落地面）：终端视图 + 底部本地输入条 + 发送 + 左侧加号（相册/拍照→multipart 上传→返回路径注入输入）。发送回执可见；失败明确报错。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*Session*"'`
- 写范围：`app/app/src/main/java/**/session/`、`app/app/src/test/`、MainActivity 接线处（仅路由挂载）。红线：不动 conn/termview/workspace 包实现（只消费其公开 API）。

## 1. 架构基（把三个已交付组件拼成会话页）
- 分层：`SessionViewModel`（纯 JVM 可测，单测全部打在这）+ Compose 屏（TermSurfaceView 嵌入 AndroidView + 输入条）。
- 接线图（先读三方公开 API 与 KDoc 再设计）：
  1. 进入页面：`ConnectionManager.subscribe(ref, rows, cols)` → snapshot 二进制帧 → `TerminalEmulator.replaySnapshot`；delta 帧 → `feed`（conn 层 80 测已绿；termview 的 Presenter 管视口）。
  2. 滚动到历史边界：Presenter 需页信号 → `scrollback` 请求（12 字节区间头含实际 from_line/line_count，按实际区间 `prependHistory`）。
  3. 输入条：**本地编辑零网络**（003），发送→`input` 帧→等 `input_ack`：ok 清输入框；fail/超时（5s）→输入框保留内容+错误提示（发送必达，杜绝"发了没反应"）。
  4. 捏合 resize：termview 的 `onResizeRequest(rows, cols)` → `resize` 帧。
  5. 加号附件：Photo Picker（ActivityResultContracts.PickVisualMedia，无权限弹窗）→ `POST /upload` multipart（OkHttp，协议 §8）→ 返回 `{"path": ...}` → path 文本插入输入框光标处（不自动发送，用户可补文字）。
- 断连恢复：conn 层自动重连+重订阅（快照重放）；ViewModel 只需把连接状态映射为顶部条提示。

## 2. 现场基
- 三个依赖组件全部已交付：conn（80 测）、:terminal（61 测）、termview（10 测）；其公开 API 是事实契约，实现前先读。
- **共享编译单元纪律（必须遵守）**：workspace-ui 席位与你并行施工 :app——在途代码每次落盘保持整模块可编译（宁可 stub），落盘后跑 `:app:compileDebugKotlin` 自检。
- 构建 `bash -lc`；协议权威 docs/protocol.md。

## 3. 需求基（指针）
1. requirement-base/entries/003-对话体验四标准.md（本任务的验收哲学：四条全在这页兑现）
2. requirement-base/entries/006-秒开与本地滚动.md（首帧/补页语义）
3. requirement-base/entries/005-自适应-让CLI自己重画.md（resize 链路）

## 4. 经验基
- 红测先行：input_ack 超时→输入保留+报错、ack ok→清框、snapshot 重放走 replaySnapshot 而非 feed、scrollback 按实际区间头插、附件 path 插入光标处，各一条。
- 静默失效猎杀：每条用户动作必须有可见结果（成功或明确失败），无声无息是最高罪。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
