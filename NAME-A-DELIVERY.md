# NAME-A App 消费交付

状态：`ci_verified_unaccepted`；作者未做最终组合/真机验收，等待独立验收席复核。未 merge、关闭 Issue、部署或产最终 APK。

## 冻结坐标与 PR

- 公开 C76 源：`corral-core#76`，head `596fe65179e73210699f3e84c40168c930bbbbca`；远端 `refs/heads/pr/favorites-online-filter` 与该 SHA 相同。
- C76 subtree：commit tree `4321eb271d2016154395f62b71e89630122489ca`，`app/app` subtree `1fa92c8316894477e52e8a1667e031a1ba84e4ad`。
- A0：C76 公开 core 源 + 精确 A73 活动差量（未整棵覆盖 PR73），commit `e42619b31803c28fec940f085d6897241ca7b9b5`，tree `732a4f2539be8d4426b785f05c7169df38c3ce99`。
- A0 公开冻结分支：`base/status-name-a0`，远端 SHA `e42619b31803c28fec940f085d6897241ca7b9b5`。
- NAME-A PR：[corral-core#89](https://github.com/Florious95/corral-core/pull/89)
- PR base：`base/status-name-a0` @ `e42619b31803c28fec940f085d6897241ca7b9b5`
- 已验证的产品/测试源 head：`pr/name-app` @ `3644d53b15ca871a0f78153a12d420c115ae3a72`
- 已验证源 tree：`a24f854550ff7a880145c4552b506201b4f53914`
- 交付文档随后作为 delivery-only commit 追加在同一 PR 分支；base 未漂移。报告时 PR head 以 GitHub 实时坐标为准。

A0 中只有 5 个精确 A73 活动文件差量；NAME-A 产品差量集中在 `L2Models.kt`、`FavoriteRecord.kt`、`FavoriteBook.kt`，测试/CI 为必要消费门。未将本地含 server 的历史树直接推送；源自公开 corral-core C76 commit。

## 名称契约实现

- `Session.toL2Entry()`：`name` 保持 server 显示投影；不再把 name 回填 `windowName`/`sessionName`，结构字段独立。
- `sessionDisplayName()`：Codex 直接显示 server `name`（完整 PaneTitle）；Pi 只显示非空 `session_name`；Claude 继续 title 去状态前缀；其他 provider 取 `window_name`、空时 tmux session；缺失显示 `名称未知`。
- `navigationName` 只取 `window_name`/`session_name`，不从显示 name 回填；ref/favorite key 不变。
- FavoriteRow 新增瞬时 live `name`；FavoriteBook 在线行所有结构/显示字段只取当前 live，不以旧收藏记录补缺失 Pi 名；离线行继续保留历史结构字段和 C76 显隐/灰置行为。
- 未改收藏键、排序、删除/显隐规则、图标、布局、节奏、网络订阅、T-N/serve。

## 具名红

Test-only head `2e473cddabf1b8768ced45a6769daa603f0ffa24` 在 A0 上执行，未改 NAME-A 产品：

- CI run [34093421646](https://github.com/Florious95/corral-core/actions/runs/34093421646)
- 命令：`cd app && ./gradlew --no-daemon --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'dev.agentmirror.app.ui.ExternalSessionStatusUiTest' --tests 'dev.agentmirror.app.ui.NameProjectionUiTest' --tests 'dev.agentmirror.app.workspace.L2UnknownStatusTest' --tests 'dev.agentmirror.app.workspace.PiWorkingDtoProjectionTest'`
- 装置/编译成功，产品命名断言失败：`NameProjectionUiTest > codexOfficialNameAndPiSessionNameReachRealComposeRow`（A0 仍把 `window_name=node-codex` 作为显示名）。
- Gradle `BUILD FAILED`；`41 actionable tasks: 41 executed`；JUnit `21` tests、`1` failure、`0` error、`0` skipped；退出码 `1`。
- 原始日志 SHA256：`eb68cba40ff77b43cd4b50e278b23a1d1fd0fa1c09df1f0bdcd9d5da3044e90d`
- run JSON SHA256：`a9f145ffb5aa7ae098ed420730b7daa273778fdf0f89cbf55ad0867f0c9dc89d`
- 先前 `34093096471` 仅为 test-only Compose import apparatus failure，未计作产品红；修正 import 后才得到上述具名产品红。

## 绿与回归门

产品修复提交 `6fd463979ad67f2eeaee25880d6743153dd1528a`，随后仅修测试 fixture 的 `3644d53b15ca871a0f78153a12d420c115ae3a72`。源代码验证 hosted run：

- CI run [34095323887](https://github.com/Florious95/corral-core/actions/runs/34095323887)，实际 checkout final head `3644d53b15ca871a0f78153a12d420c115ae3a72`。
- 命令固定使用 `./gradlew --no-daemon --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest`，并具名选择：
  `ExternalSessionStatusUiTest`、`NameProjectionUiTest`、`L2UnknownStatusTest`、`PiWorkingDtoProjectionTest`、`FavRowParityTest`、`FavoriteIdentityTest`、`L2NavigationIdentityTest`、`SessionTitleTest`、`ListRouteParityTest`、`WorkspaceOnlineProjectionTest`、`ForegroundResumeUiTest`、`L2PushOnlyNoPollTest`、`SessionLiveSubscribeTest`、`WorkspaceRefreshTest`、`TestRefreshOnOpen`、`SessionDockSourceTest`、`OverlaySubscribeCarriesCurrentSocketTest`、`TestThreePane`、`LandListTest`。
- 退出码 `0`；`BUILD SUCCESSFUL in 3m 14s`；`41 actionable tasks: 41 executed`；日志 `NAME_A_RESULT command_exit=0 executed_task_lines=67 up_to_date_task_lines=9 junit_tests=75 junit_failures=0 junit_errors=0 junit_skipped=0`。
- 绿日志 SHA256：`e9be970ba4961e590bae316e6ae96b8148c18e6697a6e8669559bb86a67c4461`
- 绿 run JSON SHA256：`8929cc98b1f63530bfa3bbd3e7eb12ab54a4a800650c3914ce3c2642fc5abb89`
- delivery-only 文档追加后的 PR head 检查：run `34096093704`，同一命名/活动测试命令，exit `0`，`BUILD SUCCESSFUL in 3m 10s`，`41 actionable tasks: 41 executed`，JUnit `75/0/0/0`；日志 SHA256 `8e90397d703271e8b311650e6bbdda2bfc329dc1068ffe0a099d35961ff824da`，JSON SHA256 `e39275dd30259c18dd2060fd38e0722d08704ed4164f3827270f1bfcf63a86c8`。

覆盖包含：T-A 20 门、三面离线收藏显隐、名称仅变化 ref/key 不变、在线 Pi 缺失名不回填旧记录、重订阅、回前台、push-only 活动态与 C76 UI 兼容测试。

## 基底与未执行项

- `python3 tools/basegen.py session-ui --pkgs dev.agentmirror.app` 已执行：`cards=2 fwd=5 rev=1 refs=[] field=no librarian=no`。
- 修改后 ArchWiki 目标 UI 包 `dev.agentmirror.app.ui` strict-T3 exit `0`；workspace 包已有基线违规（L2UiState 缺 KDoc、6 条 T3-3、4 条 T3-4），A0 对照与最终相同违规，未扩大写界。原始输出在 `tmp-archwiki-*`。
- 本机未执行 Gradle/Go/Rust；未启动 Grok Bot；未启动 AVD；未执行真实 Codex/Pi CLI 或最终组合链；未构建最终 APK。GitHub hosted JVM/Robolectric/Compose 绿不冒充最终真机/全链验收。
- 未读取凭据/profile、进程 argv、pane 正文、真实 socket/9900；无新增 Issue。完整红绿/矩阵/UI/架构/清理证据见本目录对应文件与 `tmp/`。
