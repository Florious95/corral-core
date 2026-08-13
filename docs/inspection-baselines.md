# 巡检基线集（既有语料实测）

> 任务：fix-inspection-baselines（leader 2026-08-13 派单）——把 e2e/artifacts/ 累积的真实语料
> 跑一遍算子，建立基线集。**「什么算正常」第一次有具体数字，而不是形容词。**
> 数据源：test/baselines/corpus-metrics.json（脚本 test/tools/generate_baselines.js 生成）。
> 指标：variance（存活判据，主题无关）/ contentRatio / bottomMarginPx / rightMarginPx。

## 一、对照表（指标 × 已知状态）

| 语料 | 产地设备 | 分级 | variance | contentRatio | bottomMarginPx | rightMarginPx |
|---|---|---|---|---|---|---|
| 25-app-baseline-realcc | geo_1260x2800 | **HEALTHY** | 9655 | 0.0094 | **6** | 0 |
| ime-normal-dark | avd_1080x2400 | HEALTHY | 1078 | 0.0395 | 26 | 34 |
| ime-normal-light | avd_1080x2400 | HEALTHY | 9863 | 0.044 | 669 | 394 |
| d35-normal-dark | avd_1080x2400 | HEALTHY | **600** | 0.0215 | 26 | 0 |
| d35-normal-light | avd_1080x2400 | HEALTHY | 9768 | 0.0181 | **1366** | 0 |
| 02-baseline (P0) | avd_1080x2400 | **KNOWN_BAD** | **586** | INDET | - | - |
| 02b-retry (P0) | avd_1080x2400 | KNOWN_BAD | 585 | INDET | - | - |
| 03-clean-head (P0) | avd_1080x2400 | KNOWN_BAD | 586 | INDET | - | - |
| 06-render-check (P0) | avd_1080x2400 | KNOWN_BAD | 586 | INDET | - | - |
| 151812-2637 (用户D-38) | geo_1260x2800 | **KNOWN_BAD** | 682 | 0.0292 | **1123** | 56 |
| 11-final-screenshot | avd_1080x2400 | KNOWN_BAD | 10890 | 0.0367 | 669 | 621 |

## 二、关键洞察（基线教会我们的）

### 1. bottomMarginPx 的「正常阈值」不能跨主题/设备硬套
- 深色 HEALTHY：bottom=6-26（内容占满）
- 浅色 HEALTHY（ime-normal-light）：bottom=**669**、d35-normal-light：**1366**——**浅色主题的正常态 bottom 与 D-38 失败态的 1123 同量级！**
- **结论：bottomMarginPx 必须分主题建基线**，不能用单一阈值判「底部空黑」。
  浅色主题「内容占满」bottom 就大（背景浅，contentRatio 低）。D-38 失败态 1123 与浅色正常 669/1366 重叠，
  **单看 bottom 无法区分**——需结合 contentRatio/variance 联合判。

### 2. variance 判据对「空但正常」的界面会误判
- d35-normal-dark（空终端健康态）variance=600，**接近死屏阈值 1000**。
- P0 死屏 variance=586。
- **两者方差几乎相同（600 vs 586）——方差判据无法区分「空终端」和「死屏」！**
- 需补充：死屏 avgGray=245（全亮），空终端 avgGray=25（深底）。**联合 avgGray 才能区分**：
  方差低 + avgGray 高 = 死屏（亮色空白）；方差低 + avgGray 低 = 空终端（深底无内容，可能正常）。

### 3. contentRatio 对所有已知状态都低（0.01-0.04）
- 无论健康/失败、深/浅，contentRatio 都 ≤4%。**contentRatio 单独不是有效判据**（终端内容本来就稀疏）。
- 它只能作辅助（配合方差 + avgGray）。

### 4. rightMarginPx 的正常范围 0-56
- HEALTHY right=0-34；KNOWN_BAD 用户 right=56。**right 也不是强判据**（无右列截断现场时都小）。

## 三、产地与分级

| 组 | 产地 | 分级 | 说明 |
|---|---|---|---|
| d38-baseline-healthy | geo_1260x2800, 0ms, realcc | HEALTHY | 已知健康（bottom=6） |
| ime-normal-healthy | avd_1080x2400, 0ms, ime | HEALTHY | 深/浅主题正常态 |
| p0-blank-dead | avd_1080x2400, 0ms, p0-accident | KNOWN_BAD | 必须改善目标（死屏） |
| user-d38-fail | geo_1260x2800, tailscale, user-upload | KNOWN_BAD | 必须改善目标（bottom=1123） |
| ime-4line-pinched | avd_1080x2400, 0ms, ime-4line | KNOWN_BAD | 4 行输入框挤压态 |

**产地不明的语料（如 qa-v5/abc-regression 无设备记录）标 UNKNOWN_PROVENANCE，不得作基线**——只当考卷语料。

## 四、基线用途

1. **S1 场景的棘轮基线**：首次巡检时用本表 HEALTHY 值作初始基线（如 bottomMarginPx 深色=6，
   浅色=需分主题）。
2. **判据标定**：variance + avgGray 联合判死屏（低方差 + 高 avgGray）；bottom 分主题。
3. **考卷语料**：KNOWN_BAD 是「必须被改善」的目标，不是「不许变差」的下限。

## 五、待补（w-base-v2 模拟器实测）

- 浅色主题的健康 bottomMarginPx 需更多样本（本表只有 2 张浅色）。
- 死屏/空终端的 avgGray 联合判据需在真实场景验证。
