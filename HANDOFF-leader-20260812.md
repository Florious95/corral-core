# HANDOFF · leader 退役交接 · 2026-08-12

> 本届 leader 退役。本文是写给接任者的完整现状交接。

---

## §0 compact 后先做什么

**现状**：v5 改动失败——引入输入框闪烁回归、多个缺陷未真正修复、流程全面跳过。app 侧有 695 行未提交改动需要决策（回退或挑拣）。Web 端三端合一开发已完成交付。

**开口第一句**：「v5 app 侧改动失败需回退，Web 端已交付。先读本文 §3 了解 v5 失败详情，再决定 app 缺陷修复策略。」

**必读清单**：
1. 本文（全景交接）
2. `/Volumes/nvme/Projects/远程Agent安卓/docs/defects-v5-status.md`（**缺陷清单真实状态**：7 待修 / 3 需重做 / 1 未生效 / 1 回归 / 7 已验证，含修复流程）
3. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md`（方法论铁律 + 眼见为实铁律，本届未执行导致 v5 失败）
4. `/Volumes/nvme/Projects/远程Agent安卓/taskbook.yaml`（任务书权威，结构化任务定义）
5. `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/librarian-intake/draft-20260812.md`（用户原始反馈 D-20~D-38 全文，缺陷清单的真相源）
6. `/Volumes/nvme/Projects/远程Agent安卓/e2e/artifacts/qa-v5/REPORT.md`（v5 QA 实测报告：T1 FAIL / T2-T4 PASS / T5 BLOCKED）
7. `/Volumes/nvme/Projects/远程Agent安卓/web/GOAL-production-web.md`（Web 端三端合一目标文档）
8. `/Volumes/nvme/Projects/远程Agent安卓/web/ARCHITECTURE.md`（Web 端架构说明）

**文档关系**：本文 §3 P0 → `docs/defects-v5-status.md` 逐条状态 → `draft-20260812.md` 用户原话 → `CLAUDE.md` 修复流程 → `taskbook.yaml` 结构化任务定义。

**恢复动作**：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
.team/ta claim-leader --confirm --workspace .
# 看门狗已在运行（pid 8486/22326/42282）
# daemon 已在运行（pid 39489，:9900，运行 4.5h）
```

---

## §1 身份与不变量

- **leader 只编排不亲做**。本届严重违反：亲自跑 e2e、亲自查代码、跳过三席并行。
- **派单铁律**（CLAUDE.md，本届未执行）：
  1. basegen 编闭包 — **本届从未执行**
  2. 三席并行（审查+测试+开发）— **本届从未执行，每次只派一个开发席**
  3. archwiki --strict-t3 PASS — **本届仅最后补注释时才跑**
  4. 一次只改一个缺陷 — 执行了
  5. **模拟器实测验证不倒退后才打包** — **本届是用户提醒后才做的**
- **回炉流程**：修复未生效→回退→审查 diff→根因探针→三席并行。本届未触发。
- **用户新增标准（2026-08-12）**：所有缺陷必须先在模拟器上复现（看到问题），才能开始改代码。修复后必须在模拟器上看到问题消失。**眼见为实，两头都要看到。**

---

## §2 排期与封存令

**用户裁定**：只修缺陷零新功能（F-01~F-06 只记录不施工）。

**已闭环**：
- Web 端三端合一（w-web-prod 交付：46/46 测试、Tauri v2 骨架、Mac DMG 3.2MB、深浅主题）— **自报完成，用户未手测验收**
- Web 端 JS 外骨骼注释（7 文件 21 个导出函数 @contract 补全）
- archwiki 扩展 JS 扫描（26 模块全覆盖，--strict-t3 PASS）
- 全仓注释补全（--strict-t3 四项零违规）— 但注释是在 v5 未提交代码上的，回退后需重新评估

**封存**：F-01~F-06 新功能

---

## §3 P0：v5 app 改动失败

### 现象
用户实测 v5 APK（`~/Desktop/agentmirror-v5-20260812-1530.apk`）：
1. **输入框闪烁回归**：点开输入框重绘、发消息增加一行时界面疯狂闪烁。v2/v4 无此问题。
2. **D-23 侧滑返回未修**：侧滑仍直接退出 App
3. **D-32 返回跳级未修**：QA T1 FAIL，会话页返回直接跳到工作区列表
4. **bypass 符号透明红框（D-35）从未修复**：本届错误标记为"已修"
5. **向上滑看历史（D-36）从未修复**：本届错误标记为"已修"

### 根因
TermSurfaceView.kt 三处改动叠加（D-28 clipRect + D-31 CellSizeStore + D-38 onWindowVisibilityChanged）引入了重绘回归。本届跳过了"模拟器实测不倒退"的门禁。

### v5 未提交改动（695 行，14 文件）
```
app/app/src/main/java/dev/agentmirror/app/AgentMirrorApp.kt      +19
app/app/src/main/java/dev/agentmirror/app/MainActivity.kt         +9
app/app/src/main/java/dev/agentmirror/app/MainNavState.kt         +38/-
app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt  +66/-
app/app/src/main/java/dev/agentmirror/app/workspace/WorkspaceScreen.kt +43/-
server/internal/api/ws_conn.go                                    +13/-
server/internal/api/ws_handler.go                                 +122/-
server/internal/api/api_tmux_test.go                              +110（新测试）
server/internal/api/state_wiring.go / state_wiring_test.go        变更
tools/archwiki/build_wiki.py                                      +263/-
tools/archwiki/test_check.py                                      +40
```

### 决策建议
1. **app 侧**：`git checkout -- app/app/src/main/` 回退全部 v5 app 改动，回到已提交基线
2. **server 侧**：D-21（restoreSize）代码正确且有测试，可以保留提交
3. **tools/archwiki**：JS 扫描扩展代码正确且有测试，可以保留提交
4. 回退后重新按流程（复现→三席并行→模拟器实测）逐个修缺陷

### 本届教训
- 提示词写了流程不等于执行了流程
- "测试绿"不等于"问题修了"——必须模拟器/真机眼见为实
- 派一个开发席不是三席并行
- 不要把之前的修复成果算作当前工作的完成度

---

## §4 在途未收尾任务

### A. 19 个 App 缺陷（D-20~D-38）真实状态

**之前提交已修（在 git HEAD 中，非 v5 改动）**：
- D-20 键盘遮挡 — ui-redesign commit "IME 空洞根治"，**但 v5 可能破坏了它，回退后需重新验证**
- D-24 窗口名 — server-side WindowName（已提交已验证）
- D-25 TS authkey — feat-ts-wire（已提交已验证）
- D-27 终端刷新 — server no-op resize skip（已提交已验证）
- D-30 上传填输入框 — 已提交，代码确认 upload 不触碰 textFieldValue
- D-34 字体堆叠 — fix-term-glyph-render commit GlyphFallbackPolicy（已提交已验证）
- D-37 键条连按 — feat-input-keys-app commit KeyBar+InputKey（已提交已验证）

**v5 改了但 QA PASS（回退后丢失，需重做）**：
- D-28 捏合溢出 — canvas clipRect，QA PASS
- D-31 缩放持久化 — CellSizeStore 集成，QA PASS
- D-38 后台半截 — onWindowVisibilityChanged，QA PASS

**从未修复（本届错误标记或未触碰）**：
- D-22 图片上传 401 — 未验证
- D-23 侧滑返回 — v5 只拦了返回键，侧滑未拦截
- D-26 状态检测 — 用户报三次失败，服务端修了但效果不足
- D-29 捏合闪烁 — 未验证
- D-32 返回跳级 — v5 加了二级导航但 QA FAIL
- D-35 bypass 符号透明红框 — **从未修复，本届错误标记为已修**
- D-36 向上滑看历史 — **从未修复，本届错误标记为已修**

**v5 改了但未生效**：
- D-21 退出恢复尺寸 — server 代码正确有测试，daemon 未重编

### B. w-web-prod 席位（仍在运行）
- provider: codex, model: gpt-5.6-sol
- 状态：running，正在执行 archwiki JS 扫描扩展任务
- 最近 report_result: res_0544aadc4539（全仓注释补全，success）
- **注意**：上下文已丢失（被错误 remove 后重建），当前上下文只有 archwiki 任务

### C. w-librarian 席位（仍在运行）
- provider: claude_code
- 状态：running，待命

### D. daemon 重编
D-21 server 侧代码（ws_handler.go restoreSize + ws_conn.go subscription.restoreSize）已写且测试通过，但 daemon pid 39489 是 13:40 编译的旧二进制。需重编重启：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓/server && go build -o agentmirrord ./cmd/agentmirrord
kill 39489 && bash .team/prod-daemon-launch.sh -host 192.168.31.116
```

---

## §5 运维

- **Daemon**：pid 39489，:9900，运行 4.5h。重启用 `.team/prod-daemon-launch.sh -host 192.168.31.116`
- **Watchdog**：supervisor pid 14260，watchdog pid 8486/22326，正常运行
- **模拟器**：emulator-5554 运行中，ADB `/Users/alauda/Library/Android/sdk/platform-tools/adb`
- **tmux socket 已清理**：本届清理了 1314 个残留 socket（1317→3）
- **APK 在桌面**：`~/Desktop/agentmirror-v5-20260812-1530.apk`（有回归，不可用）
- **DMG 在**：`/Volumes/nvme/cargo-target/release/bundle/dmg/AgentMirror_0.1.0_aarch64.dmg`（Web 端，3.2MB，自报完成未手测）
- **Pairing token**：`/Users/alauda/Library/Application Support/agentmirror/token`

---

## §6 安全约束

- 密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文
- 配对 token 不落日志、不上屏明文、QR 是唯一合法出口
- TS authkey 与 token 同级——不落日志、不上屏明文、不入截图，传入只经 `TS_AUTHKEY` 环境变量
- 席位禁止 git push
- GPL 隔离：终端内核自研，依赖须 Apache-2.0 兼容
- 测试净化前缀 `env -u TEAM_AGENT_*`
- 绝不触碰生产 daemon 与用户真实 tmux，测试一律自建隔离环境
