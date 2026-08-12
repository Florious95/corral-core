# 现场基 · fix-back-gesture（D-23/D-32 侧滑与返回跳级）

## 硬事实：这是回退造成的倒退，不是原始缺陷（A/B/C 实测，2026-08-12）

证据：`e2e/artifacts/abc-regression/REPORT.md`（同一模拟器、同一隔离真实 claude.exe 会话、
每组卸载全新安装、每次从已核验的第三级重新出发）

| 组 | 包 | 第三级左缘右滑 / 右缘左滑 |
|---|---|---|
| A | v2 基线（git HEAD 7c56353） | **退到桌面**（mCurrentFocus 变 NexusLauncher） |
| B | d35fix（A + D-35 修复） | **退到桌面**（与 A 完全一致） |
| C | **v4（2026-08-12 13:40 构建）** | **留在 App，落到第二级会话列表**（含 `‹ 工作区` + `claude.exe`） |

**结论：v4 的边缘侧滑返回是好的；我们退回的 git 基线是坏的。**
D-35 与此无关（A/B 完全一致）。用户报告「现在侧滑直接退桌面」属实，
是 leader 回退到 git HEAD 时丢掉了 v4 已有的导航成果。

## 归档里有什么（可能可回收）

分支 `v5-failed`（commit `2874c54`）封存了全部未提交改动，其中导航相关：

- `app/app/src/main/java/dev/agentmirror/app/MainActivity.kt`（+9：`onBackPressedDispatcher.addCallback`）
- `app/app/src/main/java/dev/agentmirror/app/MainNavState.kt`（+38：`onSystemBack()` 逐级裁决 + `selectedWorkspaceCwd`）
- `app/app/src/main/java/dev/agentmirror/app/AgentMirrorApp.kt`（+19：根 `BackHandler` 接线）
- `app/app/src/main/java/dev/agentmirror/app/workspace/WorkspaceScreen.kt`（+43：二级选择态上提到导航壳）
- `app/app/src/test/java/dev/agentmirror/app/BackGestureNavTest.kt`（+193，测试）

## ⚠️ 必须只挑导航，不许连带这两个文件

同一归档提交里还有**引入 v5 输入框闪烁回归的元凶**，**绝对不要一起捞**：

- ❌ `app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt`
- ❌ `app/app/src/main/java/dev/agentmirror/app/termview/CellSizeStore.kt`
- ❌ `app/app/src/test/kotlin/dev/agentmirror/app/termview/CellSizeStoreTest.kt`
- ❌ `app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceResumeTest.kt`

根因已立账（`.team/evidence/rootcause-flicker-v5.json`）：
D-31 在新 View 未布局时用 retained presenter 的旧 viewport 沿 `termview → session`
反向边发错误 resize。守门探针
`app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt`
就是防这个的，**捞回来后必须仍然绿**——它绿就证明毒没跟着进来。

## ⚠️ 归档的导航代码未必就能修好侧滑（不要假设）

时间线上有矛盾，必须实测而不是推理：

- v4 构建于 **13:40**，而归档里 `MainActivity.kt` 的 mtime 是 **15:00**、
  其余导航文件是 **17:33** —— **都在 v4 之后**。
- 说明 v4 当时好用的导航实现，与归档里这套**可能不是同一份**，v4 那份或已被覆盖丢失。
- 上届交接文档还称「v5 只拦了返回键，侧滑手势未拦截」「D-32 QA FAIL」，
  但今晚已多次证明该文档的判断不可靠，**不要据此预判**。

所以本任务是**实验**，不是照搬：把导航子集应用上去，**在模拟器上实测侧滑**，
用事实回答「归档这套能不能修好」。修不好就如实说，我们再重写。

## 收工判据（眼见为实）

复用 A/B/C 的同一实验方法，可直接比对：

1. 从**已核验的第三级**（会话页，UI 含 `claude.exe` 顶栏与输入框，
   `mCurrentFocus` 为 `dev.agentmirror.app`）出发
2. 左缘右滑 `5,1200 → 500,1200`，300ms；右缘左滑 `1075,1200 → 580,1200`，300ms
3. 每次滑动前重新进入第三级，避免上一次滑动污染下一次
4. 判据（uiautomator dump + mCurrentFocus）：
   - **及格线**：不得退到桌面
   - **目标**：落到第二级会话列表（与 C 组 v4 同等），而非跳到第一级工作区列表
5. 逐级验证 D-32：第三级 → 第二级 → 第一级 → 配对根，**每次只退一级**

## 不得破坏

- 强制回归门：`TermSurfaceSessionBindingRegressionTest`、`TermSurfacePinchGestureTest` 必须绿
- 主干含 D-35 形近等价映射修复（`termview/` 五文件，未提交），**不要碰**
- D-3（旋转/进程回收后导航态恢复）已在 HEAD，`writeTo`/`restoreFrom` 往返不得破坏
