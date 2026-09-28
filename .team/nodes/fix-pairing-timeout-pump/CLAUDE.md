# 知识基底 · fix-pairing-timeout-pump（配对超时永不触发——时钟泵修复）

## 0. 任务（taskbook.yaml#fix-pairing-timeout-pump）
- 立案来源：e2e-layer2-harden 席位取证实锤（工程常识红线5"失败可见"违反）。
- 缺陷：PairingViewModel.onTick（pairing/PairingViewModel.kt:147）生产**无人调用**——全仓唯一 onTick 调用在 SessionScreen.kt:85（那是 SessionViewModel 的）。配对页无时钟泵 → PAIR_TIMEOUT_MS=15s 永不触发 → 地址不可达/握手静默失败时无限"连接中…"；fix-pairing-scan-flow 交付的超时分类报错（TIMEOUT 文案+重试按钮）在生产是死代码。
- 修复：PairingScreen 加 LaunchedEffect 时钟泵调 viewModel.onTick（与 SessionScreen.kt:85 **同构**，含生命周期正确性：离屏停泵）。最小修复不重构。
- 红测先行：JVM 假时钟断言超时后 Failed(TIMEOUT) 上屏可见（修前红）。PairingViewModelTest 18 测是形状参考（其中超时用例走的是直接调 onTick 的测试通路——正因如此单测绿而生产死，把这个"测试假泵掩盖生产无泵"教训写进交件注释）。
- 验收：`bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest --tests "*Pairing*"'`。

## 1. 现场基
- 同构范本：SessionScreen.kt:85（LaunchedEffect 时钟泵既有正确做法）。
- PairingViewModel/PairingScreen：fix-pairing-scan-flow 刚改过（自动连+五分类+回填），基于当前 main 施工勿回退。
- **并行环境**：w-test-appseams 同期在 app/src/test 写接缝测试（含 SharedPreferencesPairingConfigStore）；每次落盘保持 :app 可编译。

## 2. 需求基（指针）
1. 工程常识红线5（根 CLAUDE.md：用户任何动作有限时间内必得可见结果）
2. requirement-base/entries/003（对话体验——发送必达/失败显式）
3. .team/evidence/fix-pairing-scan-flow.json（五分类语义与既有测试形状）

## 3. 经验基
- 红测先行；Compose 层逻辑薄、可测逻辑留 VM；代码必须有注释；交件前全量 :app:testDebugUnitTest 自查；净化前缀 env -u TEAM_AGENT_*。
