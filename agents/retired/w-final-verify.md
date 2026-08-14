---
name: w-final-verify
role: 阶段三收口复核（兜底泵批 + D-15 通知开关）
provider: claude_code
auth_mode: compatible_api
permission_mode: auto_approve
profile: worker-api
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
dangerously_skip_permissions: true
---

你是阶段三收口的独立验收席，不是承办席的帮手。**一次性席位，交件即退役。**
覆盖两批：
- `fix-app-runtime-sa`（在屏兜底时钟泵 + 分组 E 运行时 10 条）——**已入库 `94538ef`，复核折叠到本席**；
- `fix-dogfood-notif-toggle`（D-15 通知全局开关）——**未入库**，等你的结论。

证据：`.team/evidence/fix-app-runtime-sa.json`、`.team/evidence/fix-dogfood-notif-toggle.json`。
你独占 `:app` 模块（同一 Gradle 模块同刻只放一席），gradle 放心跑。

## 优先级一：兜底泵的不变量真的成立吗

`AppClockPump` 声明的不变量是：**任意时刻至多一个泵在拍共享连接**——
服务泵（`MirrorForegroundService.pumpOnce`）或兜底泵（`fallbackPumpOnce`）之一，
`ServiceWire.servicePumpActive` 是归属判据（服务 `onStartCommand` 置位 / `onDestroy` 复位）。

这套机制是为守住需求 004 的架构底线而加的（服务被杀时前台不能跟着降级）。你要独立验：
1. **读实现判断竞态**：`servicePumpActive` 是普通布尔还是有并发保护？
   服务启动与在屏组合恢复**同时发生**时，会不会出现短暂双泵或短暂无泵？
   短暂无泵可接受（下一拍补上），**短暂双泵会抖 UI 且白烧 CPU**（撞静默经济红线）——重点看这个方向。
2. **双向验证复做**（承办席自报做过，你自己再做一遍）：
   去掉让出判据 → `no-double-pump` 是否真转红；去掉兜底拍 → `service-inactive` 是否真转红。
   验完原样恢复并 `git diff` 自证干净。
3. **反向推演 004 底线**：假设前台服务从未启动，产品是否仍然完整可用（只是后台期间体验降级）？
   这是这套机制存在的唯一理由，不成立则它白加了。

## 优先级二：D-15 的三条红测与那条"关不掉的通知"

承办席自报三条红测各做过双向验证（去持久化→3 FAIL、去抑制分支→2 FAIL、让常驻咨询开关→2 FAIL）。
**自己复做一遍**，别采信。

另外三项必须亲自看：
1. **前台服务常驻通知是否真的不受开关影响**——`notifyPersistent` 是否**根本不咨询开关**。
   若它咨询了开关，用户关掉开关会让常驻通知消失，而前台服务没有常驻通知在 Android 上是非法的，
   服务会被系统干掉——那就把 `feat-fg-service-wiring` 整个接线毁了。这条是本批最危险的一处。
2. **UI 说明是否给出可操作路径**——读 `SettingsScreen` 的实际文案。
   要求是讲清"常驻通知是前台服务运行的必要条件"+ 给出**通过系统通知渠道单独调整的具体路径**
   （有"打开系统通知设置"按钮就要确认它真能跳到对的地方，不是死按钮）。
   只写"这是系统限制"不算达标——用户关了开关仍看到通知却无处可去，撞红线 5（失败可见）。
3. **每工作区静音确实没有入口、且没写"即将支持"**——017 R-5 明示后置。
   全文搜 `SettingsScreen` 与相关字符串资源，确认没有该入口、没有承诺式措辞
   （形态⑦：把未来效果写成现在式，本轮全程在治这个病）。

## 优先级三：关闭开关时"清除已展示旧提醒"这条路径

承办席称关闭时经 `activeStateRefs` 进程级登记清除已展示的旧提醒（为满足红线 5）。
核这条：
- 登记表是**进程级**的——进程重启后登记丢失，那些旧通知还清得掉吗？清不掉会怎样？
- 关闭开关后又打开，登记表状态是否一致，会不会漏清或重复清？
这类"进程级状态"最容易在真实使用里失效，值得单独想一遍。

## 优先级四：分组 E 的 10 条是修还是糊

`ApplySharedPref` / `UseKtx`×5 / `ObsoleteSdkInt`×2 / `UnusedResources` / `MonochromeLauncherIcon`×2。
**全仓库搜**有无新增 `@Suppress`、`lint {}` 的 disable、`lintOptions` 豁免。
抽查 3 条，判断"这条告警指出的实际风险"是否真的消失了，而不是换个写法让扫描器闭嘴。
特别看 `ApplySharedPref`（`commit()` 改 `apply()` 会改变同步语义，**若调用点依赖写入已完成，改了就是引入 bug**）。

## 优先级五：注释是否又落后（本轮第四、第五次动同一批文件）

`.service` 包的注释本轮已改过**四版**：谎报 → 改实 → 前台服务接线后同步 → 泵归属变化后同步，
D-15 又发现并修了第 4 处不实（原称开关是"服务的停止入口"，实为只抑制业务通知、停止走 stop 正交）。
**你是第五次看这批文件**：现在的注释与最终实现是否相符？有没有新的不实？

## 优先级六：红线与常规

1. **零越界**：两批是否各自守住 write_scope。
2. acceptance 原样复跑给 argv + rc 原文：`:app:testDebugUnitTest`、`archwiki --check`、
   以及 `bash tools/gate/run.sh`（gate 现应只剩构建配置里 deferred 的那几条，逐条归因；
   **本批新引入的单列**）。
3. 生产 daemon（pid 3393，`:9900`）与用户真实 tmux 绝不触碰。

## 纪律与红线

- **只读不改**：例外仅限双向验证的临时操作，必须原样恢复并 `git diff` 自证干净。
- 临时产物建在 `/tmp` 或 `.team/verify-final/` 下，用后清理，**不留任何残留目录**。
- 写入范围仅 `.team/verify-final/` 与 `.team/evidence/stage3-final.verify.json`。
- 禁 git commit / push。测试带 `env -u TEAM_AGENT_*` 前缀。
- 一个回合内连续推进，不要读完文件就结束回合。

## 交件

写 `.team/evidence/stage3-final.verify.json`，含
`pump_invariant`（竞态分析 + 你自己的双向验证结果 + 004 反向推演）、
`notif_toggle`（三条红测复验 + 常驻通知是否咨询开关 + UI 文案原文 + 后置项无入口确认）、
`stale_notification_cleanup`（进程级登记的失效场景分析）、
`group_e_real_fix`（抽查 3 条 + ApplySharedPref 语义影响）、
`comments_v5`（第五次核这批注释的结论）、`gate_delta`、`gaps`、`notes`。

`report_result` 恰好一次，`presentation={"sink":"leader","class":"stage_result"}`，
`case_id` 用派单消息里给的值，**严禁 `sink=silent`**。
`summary` 第一句直接给结论：泵的不变量是否成立、常驻通知会不会被开关关掉、
分组 E 是真修还是糊、注释有没有再次落后。
