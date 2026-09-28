# 知识基底 · fix-cold-start-reconnect（冷启动自动重连缺失——P0）

## 0. 任务（taskbook.yaml#fix-cold-start-reconnect）
- 立案来源：层2 真旅程末步（force-stop→重开→断言列表恢复）leader 亲跑抓获。现场证据：e2e/artifacts/layer2.restore-fail.xml——重开后 UI 仅「连接中…」，20s 无列表。
- 缺陷：`ServiceWire.manager(...).start()` 全仓唯一调用点 = PairingRoute.kt:57（onPaired 配对成功一刻）。冷启动（有配对配置，showPairing=false 直接进工作区页）**无任何路径启动连接**——顶栏永远「连接中…」，列表空白。004「客户端无状态，被杀即无所谓」的核心承诺被破坏：被杀确实无所谓，但重开必须自动重连恢复。
- 修复：冷启动检测已有配对配置即启动常驻连接，与 PairingRoute.onPaired 的启动序列**同构**（fg-service 启动/uploadBaseUrl 配置等一并对齐——照抄 onPaired 内的完整序列，勿只抄 start()）。必须**幂等**：配对刚成功切屏后不可二次 start（防双连接——D10 多订阅替换语义的坑）。
- 红测先行：Robolectric 冷启动（config 非空）断言连接被启动、工作区接线收到状态推进；对照（config 为空）断言不启动、停配对页。
- 验收：①全量 :app:testDebugUnitTest；②`bash e2e/run.sh --layer 2`——末步 force-stop 恢复断言即端到端验收（脚本已就绪无需改；改脚本=红线违反）。

## 1. 现场基
- 启动序列范本：PairingRoute.kt:48-62 onPaired 内完整序列（setConfig/uploadBaseUrl/ServiceWire.manager(NoopConnListener).start()/onPaired()）。
- 冷启动路径：MainActivity.onCreate → navState(initialShowPairing = load()==null) → AgentMirrorApp 工作区分支（fix-workspace-wiring 刚落的 DisposableEffect 接线在此，你的启动点与其协同——接线只转投回调，不负责 start）。
- ServiceWire：service/ 包；manager() 的创建/复用语义先读（可能已有幂等保障，核实后决定守卫写在哪层）。
- 层2 脚本恢复断言：e2e/layer2.sh §8（leader 2026-08-09 裁定语义：恢复=自动重连回列表+隔离会话仍在；不要求回会话页）。
- **工作区当前无他席施工**（十席已全部退役），无编译互阻风险。

## 2. 需求基（指针）
1. requirement-base/entries/004（无状态免疫——本缺陷破坏其核心承诺）
2. requirement-base/entries/003（标准四：需要时被唤醒——连接是通知数据源，冷启不连=通知也死）
3. docs/scenario-coverage.md E 矩阵（断连重连四层——冷启动重连是其未覆盖角落）

## 3. 经验基
- 红测先行；最小修复不重构；幂等守卫必须有锁定测试（双 start 场景）；代码必须有注释；交件前全量 + 层2 亲跑；净化前缀 env -u TEAM_AGENT_*。
