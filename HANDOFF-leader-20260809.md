# HANDOFF · 远程Agent安卓 leader · 2026-08-09（第二版，覆盖晨版；23:0x 落笔）

> 工程：agentmirror——手机远程操控主机 tmux 中 Agent CLI 的开源产品（Apache 2.0）。
> 本文写给"刚接手、没看过过程"的后继 leader。只读本文+指向文件即可接管。

## §0 compact 后先做什么

**一句话现状**：全天 22 案已闭环入库（89 commits，HEAD f784e11），最后两案在途——
feat-ts-wire（TS 组网全链接线，用户验收硬项）与 fix-term-bg-cjk（背景色区块 CJK 渲染错乱），
两席均 BUSY 收尾段；交件后合并重打 APK+重编 daemon 交用户真机。

**开口第一句**（对用户）："TS 接线与背景色渲染两案在途收尾，交件即合并重打 APK 通知你；
当前 APK(21:19)+daemon(-host 192.168.31.116, pid 46081) 仍可用。"

**必读清单**（顺序）：
1. 本文
2. `taskbook.yaml`（任务账本；feat-ts-wire / fix-term-bg-cjk 两条在途条目）
3. `requirement-base/entries/018-UI视觉标准与审查关.md`（今日新立：UI 任务验收判定权威+逐图目检审查关）
4. `docs/scenario-coverage.md`（场景总图纸）+ `requirement-base/INDEX.md`
5. `tools/basegen.py`（基底编译器——派单必经，见 §1 铁律 4）
6. 根 `CLAUDE.md`（工程红线+工程常识红线五条+席位恢复纪律）

**恢复动作**（若环境塌）：
```bash
team-agent restart . --yes                       # 团队复活（席位见 §4）
nohup python3 .team/watchdog.py > .team/logs/watchdog-escalation.log 2>&1 &   # 看门狗 v4.2
# 用户侧服务：新 Terminal 窗口跑（用户扫码用）：
#   /Volumes/nvme/Projects/远程Agent安卓/server/agentmirrord -host 192.168.31.116
```

## §1 身份与不变量（铁律，多条为今日血泪新增）

1. **解释器循环**：交件→复跑验收 argv（不凭自报）→证据 JSON（席位没写就代写并留痕"账面≠实际"）→退役席位→commit→派下一波。
2. **派单五件套缺一不可**：taskbook 条目 → FIELD.md（现场基，唯一手填合法区）→ librarian 撞库回执落 LIBRARIAN.md → `python3 tools/basegen.py <task-id> --pkgs ...` 编译 CLAUDE.md → **intent.json**（漏写=看门狗全盲，今日实案）→ role file（**别用 sed 复制旧 role，角色文案/基底路径全要核**，今日实案）→ add-agent → 派单。
3. **基底禁手填**（用户今日最重批评之一）：CLAUDE.md 主体必须是 basegen 编译产物；手写素材只进 FIELD.md。
4. **UI 任务审查关**（018 §二）：交件必附全页全态截图落 `e2e/artifacts/ui-review/`，leader 逐图目检对照 018 七条，结论写进证据 `ui_review` 字段。测试绿≠过关。
5. **死件家族嗅探**：验收任何"模块完成"必查消费方存在（grep 调用点）。今日七例：StateProvider 恒 unknown、深链无消费、uiConnector 裸建、onTick 无泵、uploadBaseUrl 传 null、onNetworkAvailable 零调用、**tsnet 全套组件零接线**（最大件，正在修）。
6. **隔离铁律**：席位取证/测试绝不触碰生产 daemon 与用户真实 tmux socket（今日 w-term-bgcjk 违纪实案：借生产 daemon 建 proof 会话，已叫停+deviation 留痕）；一律自建隔离 TMUX_TMPDIR+高端口 daemon（范式：e2e/layer2.sh、scratchpad walkthrough.sh）。
7. **席位恢复纪律**：provider 死亡→start-agent 恢复失败 2 轮（A-24 拒启形态"cohort duplicate proof failed"）即弃 id 处女重建带案重派；第三方 API 连死两席的域改用 Fable 5 订阅通道（今日 glyph 三席接力实案）。
8. **验收哲学**（016+今日）：交付判定="用户上手十分钟找不出新毛病"；"未验证"与"未实现"严格区分，不许混写（TS 案就是混写被用户戳穿的）。
9. 编译互阻点对点常规：被他人半成品挡编译→直接 send 文件主人（附文件+行号+错误原文），不经 leader。
10. 心跳 ScheduleWakeup 1800s 链；框架问题直报 `多agent协作::refactor-maintainability/leader`（今日 A-25 立案、A-24 样本×2 均被采信）。

## §2 排期与封存令

- **已闭环 22 案**（全部 leader 复跑验收+证据入库）：晨波 P0 六案（idle-cpu/state-wiring/app-nav/aggregate-status/input-keys-server/e2e-harden）→ 午波（workspace-wiring/pairing-timeout-pump/app-seams/input-keys-app/cold-start-reconnect/release-test-host）→ 晚波用户回炉（reconnect-stale-config/ui-redesign(018 目检首执行)/pairing-candidates/term-glyph(三席接力)/term-render-debt/term-residuals）→ 传图链路真机首证。
- **在途 2 案**：见 §4。
- **排队**：R-8 拍照直传（taskbook 欠账，017 裁定当期补）；后置议题见 INDEX（R-3/4/5/6/7 后置项）。
- **封存令**（延续）：真机首触清单未走通前，不得宣称"验收达成"。**新增**：交付前必跑"真机模拟走查"（walkthrough 范式：锁屏/强杀/断网/暗色逐步截图目检）。

## §3 P0/插队史（今日全天即回炉日，均已闭环，除两条在途）

用户三轮真机批评驱动全天：①锁屏无限重连+UI 简陋+渲染别扭 → reconnect-stale/ui-redesign/term-glyph 三案；②"基底手填违反 skill" → 叫停-回退-重编-重发（basegen 诞生）；③"TS 完全没适配" → feat-ts-wire（在途）。原排期无漂移——所有闭环案均正常销账。

## §4 在途 2 案（逐条可执行）

| 任务 | 席位（全名） | 通道 | 当前阶段 | 验收 argv | 备注 |
|---|---|---|---|---|---|
| feat-ts-wire | w-ts-wire | Fable 5 订阅 | 契约(protocol.md ts_authkey 节)→红测→两端接线推进中；已批 internal/config 纯加法扩权（taskbook 行内留痕） | `cd server && go test ./internal/tsnetd/... ./internal/pairing/...` + `cd app && ./gradlew -q :app:testDebugUnitTest` | 组件全在只缺接线（App tsnet/ 三件+server tsnetd+qr.go TSAuthKey 字段）；authkey 同 token 红线不落日志不上屏；**真实 tailnet 端到端需用户 authkey 真机验，模拟器自验到 SOCKS5 路由正确为止，未达部分显式列未验证清单** |
| fix-term-bg-cjk | w-term-bgcjk | Fable 5 订阅 | 主案+键条裁半均红→绿、全量绿（自报）；正在**隔离环境**重做同机位对照截图（生产 socket 违纪已叫停改道） | `cd app && ./gradlew -q :terminal:test :app:testDebugUnitTest` | 根因已锁：CJK 续格多推 1 列+背景矩形漏铺 2 列；证据须含 deviation 违纪自述 |

**两席交件后的合并交付序列**：双验收复跑 → 目检截图 → 证据（bgcjk 查 deviation）→ 退役 → commit → `cd app && ./gradlew -q :app:assembleDebug` 重打 APK → ts-wire 动了服务端则 `cd server && go build -o ./agentmirrord ./cmd/agentmirrord && pkill -f server/agentmirrord` 后 osascript 新 Terminal 窗口重启（带 `-host 192.168.31.116`）→ `open -R` APK → 通知用户（TS 验证步骤：App 配对页填其 authkey 或服务端 `TS_AUTHKEY=… ./agentmirrord` 后重扫码）。

**进程现场**：watchdog pid 98464（日志 `.team/logs/watchdog.log`，升级信号落 `watchdog-escalation.log`——必须捕获，晨间曾因 >/dev/null 吞掉升级）；生产 daemon pid 46081（用户手机在连）。

## §5 运维与外部

- 全量门 `tools/gate/run.sh`（app 面跑双变体，release 宿主债已清偿）；层2 `bash e2e/run.sh --layer 2`（六步真旅程硬判定，**只认现成 APK 不重编——复核前必须先 assembleDebug**，今日踩坑）；走查脚本范式在 scratchpad `walkthrough.sh`（会话目录易变，必要时照 §4 序列重写）。
- 上传目录 `~/Downloads/agentmirror-uploads/`（用户传图落这里）。
- 框架直报通道见根 CLAUDE.md；今日 A-24（样本×2）/A-25 已立案，0.5.62 不随车，我方看门狗 v4.2 pane-hash 兜底为官方认可先例。
- APK：`app/app/build/outputs/apk/debug/app-debug.apk`（21:19 版用户手机已装）。

## §6 安全约束（原文，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader）禁止读其原文**；诊断只用 `team-agent profile show <name> --workspace . --json`。
- 配对 token：不落日志、不上屏明文、QR 是唯一合法出口（协议 §9）。
- TS authkey：与 token 同级——不落日志、不上屏明文、QR 预授权分发为唯一自动出口；App 侧安全存储。
- 席位禁止 git push；本地不 commit（commit 权在 leader）。
- GPL 隔离：终端内核自研（R-002，JTA 血统出局）；依赖许可必须 Apache-2.0 兼容。
- 测试净化前缀 `env -u TEAM_AGENT_*`；绝不触碰真实 team-agent tmux socket 与生产 daemon。
