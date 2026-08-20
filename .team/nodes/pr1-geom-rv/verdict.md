VERDICT: supports

# t.geom.rv · 异源评审（pr/geom-resume，commit ae8fa38e5）

## 一、先验红核查（🔴 关卡）——通过

说明.md（分支内 `.team/nodes/pr1-geom/说明.md`）§「先验红」贴有**改产品码之前**的原始 gradle 输出：
- `aGeomResume_backgroundThenForeground_geometryMatchesRealViewport_n20 FAILED`，
  `AssertionError: N=20 次必须全部等于真实视口几何，错了 20 次`，并逐项列出 i=0…i=19 的
  view/cell/want/got 原始操作数（说明.md L44-45）。
- `aGeomLog_threeCallbacksEachRecordBothOperands FAILED`，
  `IllegalStateException: 导出里读不到 viewportOutgrewEmulator:`（L47-51），
  并贴出当时导出里只有 size/real 两条、无守卫操作数——与 CLAUDE.md 记载的
  2026-08-14「TermViewPresenter 仅一处 DiagLog.record」的旧状一致，可信。

N=20、20/20 全错 ⇒ 判据抓的是真缺陷，且概率性判据按 090 §2.5 跑满了 N 次。

## 二、判据非恒真核查（🔴 关卡）——通过

`GeomResumeForegroundTest.kt`（新文件，153 行）**先制造触发条件再断言**：
- A-geom-resume（L58-95）：每轮先 `seedCellMetrics(10,20)` + `onViewportSizeChanged` 建立几何，
  再 `seedCellMetrics(12+i%5, 24+i%4)` 模拟回前台字格漂大（像素视口不变），然后
  `onRealViewportChanged(viewW, viewH)`，断言 emulator.rows/cols 等于新字格推导值。
  这正是「candidate 变小 ⇒ outgrew=false ⇒ 旧实现不重算」的路径，先验红 20/20 证明其非恒真。
- A-geom-log（L101-135）：先走一遍 outgrew 必为 false 的同尺寸事件（L110-112 注释明说），
  再断言导出里 `viewportOutgrewEmulator:` 行含 viewport_rows/emulator_rows/viewport_cols/
  emulator_cols 与 `→ false|true` 结论——即「守卫拦下也要记两边操作数」，
  直指诊断日志纪律那条实发教训。

## 三、说明与 diff 逐条核对——一致

1. TermViewPresenter.kt L429-455：`viewportOutgrewEmulator(source:)` 无条件
   `DiagLog.record` 两边原始数值+operandsReady+结论，早期 return 路径也不再沉默。✔ 与说明§修法「仪表」一致。
2. TermViewPresenter.kt L317-318 + L415-417：`seedCellMetrics` 在宽高变化时置
   `cellMetricsDirty`，`onRealViewportChanged` 消费；L346-351：
   `outgrew || (cellsDirty && mismatch)` 才 `recomputeGeometry`。
   IME 挤压（字格不变）不走第二条 ⇒ 说明声称的「D-38 零多余 resize 不倒退」在逻辑上成立，
   且分支内 A-geom-tests-green.log 尾部 `> Task :app:testDebugUnitTest` + `BUILD SUCCESSFUL`
   证明含 D38 套件的全量单测绿。✔
3. TermViewPresenter.kt L332-338：首帧路径原先硬编码 `outgrewGuard=true` 且不调用守卫，
   现改为真算——只改仪表真值，不改首帧行为（seed 后仍 recompute）。✔
4. TermSurfaceView.kt L344-347：`onWindowVisibilityChanged` VISIBLE 时
   `applyFontMetrics(forcePresenter=true)`；L810-815：1px 退化实测不覆盖已有非退化字格
   （keepSeed 守卫），与说明第 2 条完全对应。✔
5. 未改 SessionRoute.kt / 未改任何棘轮脚本 / 未 --freeze：diff 中无此类文件。✔
6. 判据未被改绿：两条判据都是新文件，既有测试零改动（diff 里无其他 test 文件）。✔

## 四、验绿证据

- A-geom-tests-green.log（102 行原始 gradle 输出）尾部 `BUILD SUCCESSFUL in 13s`，
  任务含 `:app:testDebugUnitTest`；diag-geom.log 里可见
  `viewportOutgrewEmulator: source=… operandsReady=true → false` 的实记录行。
- A-geom-wiki.log：`基线=2 本次=2 新增=0 消掉=0`；A-geom-smell.log：`基线=16 本次=16`。

## 五、瑕疵（不构成否决，leader 应知）

1. 提交里混入 `app/.kotlin/errors/errors-1787246159037.log`（Kotlin daemon 崩溃日志，
   构建垃圾），不属于本格交付物，建议合入前剔除。
2. GeomResumeForegroundTest.kt L146 硬编码绝对路径
   `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr1-geom/tmp`——换机器/CI 必挂，
   且单测写的是仓根路径而非本 worktree。建议后续改相对路径或临时目录。
3. A-geom-smell.log 首行带 `⚠️ 有真单测失败 x gradle test (exit 1)`——smell 棘轮自跑的
   gradle 当时 exit 1，与说明「A-geom-suite exit 0」时序上矛盾（可能 smell 跑在修复中途）。
   套件绿以 A-geom-tests-green.log 的 BUILD SUCCESSFUL 为准，但两份证据的先后未标时间，留痕。
4. 说明自报的额外风险（真机漂移触发率查不清、INITIAL_COLS=120 未动）已显式声明，
   属「查不清」栏合规披露；本格判据不含真机验收，最终仍需用户真机截图闭环。

综合：先验红原始输出在、判据先制造条件再断言且 20/20 抓到、diff 与说明逐条对应、
无越格改动、判据未被改绿 ⇒ supports。
