# 知识基底 · fix-ts-state-dir-e2e（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-ts-state-dir-e2e
    goal: >
      P0 收尾阻塞（feat-ts-wire 最终验证发现，2026-08-10 裁定席立案）：cmd 已解析有效
      -state-dir/AGENTMIRROR_STATE_DIR 并用于 pidfile，却未把隔离目录传入 tsnetd.Options.Dir；
      tailnet 启用时空 Dir 回落 os.UserConfigDir()，在禁止改写 HOME、禁止触碰用户真实 tsnet 的
      验收铁律下，自建 headscale + daemon + 模拟器实链只能记 BLOCKED。纯加法修复：把 cmd 已
      resolve 的有效 stateDir 下独立 tsnet 子目录显式接入 Options.Dir，默认用户行为不变，不新增
      第二套重叠 state-dir 配置；红测锁定消费方确实收到该目录、tailnet 未启用时不创建 tsnet
      状态。随后把 headscale 验收固化为可重复脚本：全程不得赋值/重定向 HOME，不碰生产 daemon/
      用户真实 tmux/真实 Tailscale；TS authkey 只经 TS_AUTHKEY 环境进入 daemon、禁止 argv flag、
      禁止日志与截图明文。**用户裁定 2026-08-10：模拟器扫不了码就不要测模拟器扫码**——本任务
      验收**不得**依赖模拟器摄像头/Extended Controls/任何前台鼠标或窗口寻址（此前因裸 qemu
      bundleID=NULL 无法后台寻址而卡死，见 .team/evidence/fix-ts-state-dir-e2e.json 的 attempts）。
      **用户裁定 2026-08-10（二次）：通过 API 测试**——即完全不经任何 UI 路径（不点界面、不填
      输入框、不扫码、不做窗口寻址），改用 instrumentation/程序接口**直调**配对入口与 tsnet 启动
      入口（App 侧走 androidTest instrumentation 直接调用 ConnectionManager/tsnet 绑定 API 并传入
      authkey；authkey 只经受测进程的环境/参数注入，不上屏、不落日志、不入截图）。已连续 8 代
      在「headscale_node_count=1 / expected_two_headscale_nodes」同一处失败，本轮**先定位再验证**：
      必须先给出 App 侧节点到底有没有发起注册的证据（tsnet 启动是否被调用、authkey 是否真送达、
      headscale 端有无收到该节点的注册请求、失败在哪一层），定位结论写进证据；在此基础上再实证
      App 节点入网、经 SOCKS5 拨 daemon 的 100.64/10 地址并进入工作区。
      真扫码（相机识别 QR）改由用户真机验收覆盖，不在本任务范围。
      脚本无论成败都精确清理自建 headscale/daemon/端口/状态目录，落 argv 密钥形状 presence-only
      与零残留证据。未跑成必须如实红交，不得再用 HOME 旁路，也不得回退鼠标/前台方案。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd server && go test ./cmd/agentmirrord/... ./internal/config/... ./internal/tsnetd/...\"'"
      - "bash -lc 'env -u TEAM_AGENT_* bash e2e/feat-ts-wire-headscale.sh'"
    deps: ["feat-ts-wire"]
    write_scope: ["server/cmd/agentmirrord/", "e2e/feat-ts-wire-headscale.sh", "e2e/artifacts/fix-ts-state-dir-e2e/"]
    evidence: ".team/evidence/fix-ts-state-dir-e2e.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：cmd/agentmirrord
- 正向依赖（你消费的契约，只读）：go_internal_api, go_internal_config, go_internal_pairing, go_internal_tsnetd
- **反向依赖（波及面=回归自查范围）**：无

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

## 3. 需求基
- goal 引用条目：（goal 无编号引用）
- librarian 撞库回执：.team/nodes/fix-ts-state-dir-e2e/LIBRARIAN.md（先完整读）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 派单通道净化：所有 Team Agent CLI 调用统一走仓库包装器 .team/ta，尤其 add-agent/start-agent/reset-agent；禁止手写 env -u 前缀或直接调用 team-agent，否则 Codex 托管代理会被快照进新席启动串，形成零 token 假 BUSY
- A-31 开工核真：Codex 新席必须在对应 ~/.codex/sessions 当日 JSONL 出现 reasoning 或 custom_tool_call；Working/BUSY、pane 存在、命令 exit 0 均不算真活性
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- .team/nodes/fix-ts-state-dir-e2e/FIELD.md（先完整读；含真机实证/失败现场/裁定）
