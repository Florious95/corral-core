# 知识基底 · e2e（系统编译产物）——收官验收任务

## 0. 任务（taskbook.yaml#e2e，对齐 010 最终验收标准）
- 目标：端到端验收：真实 tmux + 真实 Agent CLI + 服务端 + 安卓侧全链路，外加老化实验。产出 `e2e/run.sh`（一键全跑）+ `e2e/report.md`（结果+数字）。
- 验收（exit 0 = 过）：`bash -lc 'bash e2e/run.sh'`
- 写范围：`e2e/`。红线：**绝不触碰真实 team-agent tmux socket**（只用自建隔离 socket）；产品代码只读，发现缺陷报 leader 不自修（e2e 是验收方不是修理方）。

## 1. 架构基（三层验收，run.sh 顺序执行）
1. **协议链路层（主力，无模拟器）**：Go 测试 harness（e2e/harness/，独立 go module，import github.com/agentmirror/agentmirror 的 protocol 包走本地 replace）。真实链条：隔离 tmux（跑一个可脚本化 TUI；若本机 `claude` CLI 可无交互启动则加一条真 CLI 场景，不行就 shell+脚本输出）→ 起 agentmirrord（自动 token）→ WS 客户端走完：auth→list（两级模型断言）→subscribe→**首帧延迟断言 <200ms**（006）→input 注入→回显断言→input_ack→scrollback 分页（区间头断言）→resize→图片 multipart 上传→路径注入回显。
2. **安卓实机层（模拟器 smoke）**：本机 SDK 有 emulator/；查 AVD（avdmanager list avd），缺则创建（system-images 缺则 sdkmanager 装，参照 env-android 沉淀）。`emulator -no-window` + adb install app-debug.apk + 冷启动 smoke：进程活、配对页/工作区可达（uiautomator dump 断言关键节点）。模拟器与主机服务端联通走 `10.0.2.2` 映射。
3. **老化层（004/013）**：脚本化 20 轮循环：杀服务端进程→重启→客户端 harness 重连重放断言；20 轮 kill harness 连接→重连→快照一致断言。计数进 report（flaky 判定靠全过，任何一轮失败即红并留现场）。
- run.sh 支持 `--layer 1|2|3` 单跑（调试用），无参全跑；任何层红则整体非零退出，report.md 写实际数字（首帧 ms 分布、老化轮次）。

## 2. 现场基
- 全量门当前 594+ 用例全绿（tools/gate/gate-report.json 可读）；服务端一条命令起：`cd server && go run ./cmd/agentmirrord`（QR+token 自动打印，token 存 ~/.config/agentmirror/token）。
- 本机：tmux 3.6a、Go 1.26、SDK 齐（platform-tools/emulator/build-tools）、AVD 现状未知（自查）。APK：`cd app && ./gradlew -q :app:assembleDebug` → app/app/build/outputs/apk/debug/。
- 隔离 tmux 铁律与短 socket 路径坑：读 `.team/nodes/term-bridge/CLAUDE.md` §5（sun_path 104 字节）与 `.team/nodes/tmux-discovery/CLAUDE.md` §5。
- 服务端扫描默认目录，e2e 需让服务端只见隔离 socket：用 `TMUX_TMPDIR` 指向临时目录再起 tmux 与 agentmirrord（discovery 尊重 TMUX_TMPDIR，见其实现）——既隔离又不见真实舰队。

## 3. 需求基（指针）
1. requirement-base/entries/010-最终验收与运行方式.md（验收标准本体）
2. requirement-base/entries/003-对话体验四标准.md + 006（首帧/滚动/回执的数字来源）
3. requirement-base/entries/013-测试体系与回归门禁.md（E2E 层与老化层的裁定原文）

## 4. 经验基
- 阳性对照必配：每类断言配一条"故意破坏必须红"的自检（如错 token 必须被拒）。
- 端口/进程清理 trap 收尾；任何等待带超时；失败留现场（pane capture+服务端日志进 e2e/artifacts/）。
- 模拟器层失败时先归因（harness/env/product 四分类）再报——模拟器环境瑕疵不许伪装成产品缺陷。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
