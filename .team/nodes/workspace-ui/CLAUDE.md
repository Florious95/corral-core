# 知识基底 · workspace-ui（系统编译产物）

## 0. 任务（taskbook.yaml#workspace-ui）
- 目标：两级导航 UI：一级工作目录列表（会话数徽章 + 聚合状态徽章），二级会话列表（状态徽章，unknown 灰显不阻塞）。点会话跳会话页（路由占位即可，会话页归 session-ui 任务）。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*Workspace*"'`
- 写范围：`app/app/src/main/java/**/workspace/`、`app/app/src/test/`、MainActivity 接线处（仅路由挂载）。红线：不动 conn/termview/session 包实现。

## 1. 架构基
- 分层：`WorkspaceViewModel`（纯 JVM 可测：消费 conn 层 listing/list_delta 流 → UI 状态；seq 跳变已由 ConnectionManager 自动重拉，你只渲染）+ Compose 屏（薄）。单测全部打在 ViewModel（验收 --tests "*Workspace*"）。
- conn 层接口：`ConnectionManager`（`app/app/src/main/java/dev/agentmirror/app/conn/`，80 测已绿）——先读其公开 API 与 KDoc 再设计，不要猜。
- 数据语义（协议 §5，docs/protocol.md 权威）：一级=cwd 聚合（session_count + aggregate_state 服务端已算好，**客户端只渲染不重算**，012 裁定）；二级=sessions[]（name 是展示标签，ref 是寻址键）。
- 状态徽章五值（008）：blocked=醒目（需要人）、done=完成色、working=活跃色、idle=中性、unknown=灰显——unknown 是一等公民不是错误，绝不阻塞列表渲染。
- 空态/断连态：无工作区时给引导文案；断连时显示重连中条（conn 层自动重连，UI 只反映状态）。

## 2. 现场基
- Compose BOM 2025.12.01 / Material3 已在 :app；构建 `bash -lc`。
- **共享编译单元纪律（必须遵守）**：session-ui 席位与你并行施工 :app——你的在途代码每次落盘必须保持整模块可编译（宁可 stub 占位），落盘后跑 `:app:compileDebugKotlin` 自检。

## 3. 需求基（指针）
1. requirement-base/entries/002-两级分组模型.md（两级导航的裁定原文）
2. requirement-base/entries/012-工作区聚合状态规则.md（徽章语义）
3. requirement-base/entries/001-产品命题-tmux镜像范式.md（舰队视角：首页是聚合面板）

## 4. 经验基
- 红测先行：listing→两级渲染、delta 增删改会话、全 unknown 工作区灰显、断连态各一条。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
