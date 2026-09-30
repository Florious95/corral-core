# 知识基底 · fix-workspace-wiring（配对后列表永不显示——uiConnector 断线修复）

## 0. 任务（taskbook.yaml#fix-workspace-wiring）
- 立案来源：e2e-layer2-harden 席位亲手 trace 取证（协议/传输/认证全链健康，app 已收到 auth_ack ok 与含真实会话的 listing，但列表页渲染不出）。
- 缺陷：WorkspaceViewModel.onConnectionStateChanged/onFrame（workspace/WorkspaceViewModel.kt:109/117）**全仓无调用点**——WorkspaceScreen 的 VM 在 AgentMirrorApp.kt:53 remember 裸建，从未挂到 ServiceWire.uiConnector；配对成功 onPaired→showPairing=false 切工作区后，VM 收不到 READY/list_delta，永远默认"连接中…"空白。
- 修复：与 SessionRoute.kt:72 **同构**的 DisposableEffect 接线（挂载注册/卸载注销，防重复注册与泄漏）。最小装配不重构。
- 红测先行：断言工作区 VM 经 uiConnector 收到 READY+listing 后进入列表渲染状态（修前红——正是缺陷现场）。已有 WorkspaceViewModelTest 15 测是 VM 逻辑层形状参考；接线层用 Robolectric（基建已就位：robolectric:4.16.1，模板 MainActivityNavTest.kt @Config(sdk=[34])）。
- 验收：`bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest --tests "*Workspace*"'`。
- 红线：004 客户端无状态——接线只转投回调，不在 Activity/Compose 层缓存业务状态；断连重挂后由 READY+全量 listing 恢复（既有语义，勿改）。

## 1. 现场基
- 同构范本：SessionRoute.kt:72（uiConnector 挂载的既有正确做法，照抄结构）。
- 裸建点：AgentMirrorApp.kt:53（fix-app-nav 刚改过该文件引入 navState——基于当前 main 施工，勿回退其改动）。
- ServiceWire：service/ 包（fg-service 交付），uiConnector 是 UI 层唯一回调注册口。
- **并行环境**：w-test-appseams 同期在 app/src/test 写接缝测试、可能加法性触碰 workspace/StateBadge（R-7 contentDescription）——文件可能相邻但不同处；每次落盘保持 :app 可编译（共享编译单元纪律）。

## 2. 需求基（指针）
1. requirement-base/entries/004（客户端无状态——接线不引入缓存）
2. requirement-base/entries/002（两级分组——列表渲染语义）
3. requirement-base/entries/016（真机首触零阻断——本缺陷是 T1"3s 级进列表"的直接阻断点）

## 3. 经验基
- 红测先行；最小接线不重构；代码必须有注释；交件前全量 :app:testDebugUnitTest + tools/gate/run.sh 自查；净化前缀 env -u TEAM_AGENT_*。
