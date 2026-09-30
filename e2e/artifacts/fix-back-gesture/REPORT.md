# D-23 / D-32 导航回收实验

侧滑第三级落点：**第二级会话列表**（与 A/B/C 实验 C 组 v4 同等，不退桌面、不跳级）。

## 结论

从 `v5-failed`（`2874c54`）精确回收的 5 个导航文件（`MainActivity.kt` / `MainNavState.kt` /
`AgentMirrorApp.kt` / `workspace/WorkspaceScreen.kt` / `BackGestureNavTest.kt`）**能修好侧滑退桌面**：

| 动作 | 落点 | mCurrentFocus |
|---|---|---|
| 第三级左缘右滑 `5,1200→500,1200` | 第二级会话列表（`‹ 工作区` + `claude.exe` 行） | `dev.agentmirror.app` |
| 第三级右缘左滑 `1075,1200→580,1200` | 第二级会话列表 | `dev.agentmirror.app` |
| D-32 逐级：第三级→（滑）→第二级→（滑）→第一级→（滑）→配对根 | 每次只退一级，未跳级 | 全程 `dev.agentmirror.app`，未落桌面 |

全程 `mCurrentFocus` 未出现 `NexusLauncher`，与 A/B（git 基线 / d35fix，均退桌面）形成对照，
与 C 组（v4 13:40 构建）同形。

## 已完成

- 从 `v5-failed`（`2874c54`）精确回收 5 个导航文件；逐文件 blob hash 与归档一致（本轮重建后复核仍一致）。
- `BackGestureNavTest`（14 测试）、`MainActivityNavTest`（5 测试）、
  `TermSurfaceSessionBindingRegressionTest`、`TermSurfacePinchGestureTest` 定向同跑全绿。
- 禁区四文件（`termview/TermSurfaceView.kt`、`CellSizeStore.kt` 及两道守门测试）未被本任务改动，
  与 `v5-failed` 归档版本 hash 不同（确认不是从归档误捞的），当前内容属主干 D-35 在途改动。
- `:app:assembleDebug` 成功，APK 反映当前已回收的导航源码。
- 隔离 daemon 用 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄到本次 `TMUX_TMPDIR`，
  全程 UI 只出现本席临时 `cwd`（`/private/tmp/fix-back-gesture.xGVuYi/cwd`），
  未枚举/未触碰宿主真实 tmux socket。
- 目标设备 `emulator-5556`（独占），未碰 `emulator-5554`。

## 早前两次运行阻断（已解决，供参考）

1. 第一次运行的新 daemon 仍按生产默认枚举宿主 tmux socket，UI dump 出现真实工作区/会话名称，
   脚本在进入第三级前中止；已即时上报 leader。
2. 第二次加 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 后因 macOS `/tmp`→`/private/tmp` 解析
   导致路径精确匹配失败，脚本仍在第三级前中止。
3. 本次（第三次）在 `run-nav-evidence.sh` 里对 `RUN_TMP` 做 `cd "$RUN_TMP" && pwd -P` 归一化后，
   完整跑通到底，产出下方全部证据。

## 证据

- 侧滑：`left-before.{xml,png}` `left-after.{xml,png}` `left-after-focus.txt`；
  `right-before.{xml,png}` `right-after.{xml,png}` `right-after-focus.txt`
- D-32 逐级：`d32-level3.{xml,png,focus.txt}` → `d32-level2.{xml,png,focus.txt}` →
  `d32-level1.{xml,png,focus.txt}` → `d32-pairing-root.{xml,png,focus.txt}`
- APK：`apk.sha256`（本次构建，反映当前已回收源码）
- 结果摘要：`result.txt`
- 定向测试日志：`.team/evidence/fix-back-gesture-logs/focused-tests.log`
- APK 构建日志：`.team/evidence/fix-back-gesture-logs/assemble-debug.log`
