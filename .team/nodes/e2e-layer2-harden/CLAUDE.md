# 知识基底 · e2e-layer2-harden（e2e 账实落差修正案）

## 0. 任务（taskbook.yaml#e2e-layer2-harden）
- 总图纸：docs/scenario-coverage.md §0.3-2（账实落差原文）与 §12 P0-7 行——先读。
- 目标：层2 从"弱文本判定+假杀假断"升级为真旅程：uiautomator 语义定位（替换硬编码坐标）；断言列表含真实会话→点开→断言快照文本出现在终端视图→输入→断言回显→`adb shell am force-stop` 真杀 App→重开→断言恢复原会话画面（004 真验证）。步骤与断言逐项写入 layer2.json。
- 验收：`bash -lc 'bash e2e/run.sh --layer 2'`。写范围：e2e/。
- 红线：产品代码只读，发现缺陷立案报 leader（你是验收方）；模拟器/AVD 复用既有；trap 清理含 daemon（缺陷 C 教训，fix-idlecpu 也在改 layer2.sh 的 trap——先 git status 看其是否已落，避免撞行，撞了报 leader 排序）。

## 1. 现场基
- 既有 e2e/layer2.sh 与 harness 结构先盘点；app 侧 fix-app-nav 并行施工中（深链/rememberSaveable），你的"杀 App 恢复"断言在其落地后会更强——若时序上它未交件，恢复断言按当前行为写并注明依赖。
- API 26 双变体（审计建议）本轮可选：先把 35 上的真旅程做扎实，26 变体列 TODO 报告。

## 2. 需求基（指针）
requirement-base/entries/016（账实一致是本任务存在的理由）、004、013。

## 3. 经验基
- 每步等待带超时；失败留现场 e2e/artifacts/；阳性对照（故意错断言必须红）；bash -n 语法自查。
