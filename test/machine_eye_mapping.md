# 用户 11 条缺陷 → 机器眼算子 + 考卷 映射清单

> 目标：每条用户报过的屏幕上现象，都有对应的机器眼算子输出「可重跑、可证伪」的数字判据。
> 判据只有变成数字才能被重跑、被证伪（机器眼 Layer 0 的立身之本）。
> 状态：D-38 已闭环（首条完整范例）；其余标注覆盖等级。
> 覆盖等级：`DONE`（算子+考卷已跑通）/ `PARTIAL`（算子可输出，判据待标定）/ `TODO`（Layer 1）。

## 范例（全工程第一条「用户现象 → 可重跑数字」完整闭环）

### D-38：内容只占屏幕上部、下方大片空黑

- **用户原话**：终端内容仅占屏幕顶部约 1/4，中间大片空黑。
- **算子**：空间算子 `space.analyzeFrame` → `bottomMarginPx`。
- **考卷**：`docs/d38-user-evidence-metrics.md` 实测——健康 ≈6（0.25% 屏高）、失败 ≈1123（40.1% 屏高），差 160 倍。
- **判据**：`bottomMarginPx / height > 0.1`（底部余量 >10% 屏高）→ 判为 D-38 失败态。
- **可重跑**：`node -e "require('./framework/machine_eye/space').analyzeFrame('<png>')"` 即出。
- **归因**：emit 缺失（App 报 84 行后视口涨到 140 未再上报），非栅格不收敛。

---

## 缺陷 → 算子映射

### 空间类（内容边界 / 右缘 / 底缘）

| # | 用户现象 | 算子 | 判据数字 | 覆盖 |
|---|---|---|---|---|
| 1 | **最右列文字被截断**（fix-cols-grid-convergence，第4次报） | space | 内容右缘 vs 期望列宽差（Layer 1 补「满宽正常 vs 截断」区分） | PARTIAL |
| 2 | **D-38 内容只占上部空黑** | space | `bottomMarginPx/height > 0.1` | **DONE** |
| 3 | **IME 弹起末行被遮**（D-20） | space + symbol | 末行底边 vs 输入框上沿 | PARTIAL |
| 4 | **捏合放大后底部留白**（纵向潜伏账） | space | 捏合前后 `bottomMarginPx` 变化 | PARTIAL |

### 时间类（差分分布形态）

| # | 用户现象 | 算子 | 判据数字 | 覆盖 |
|---|---|---|---|---|
| 5 | **发消息整屏从上往下刷**（fix-input-send） | time | `reflowSignal=true`（整屏重排）→ 判缺陷 | PARTIAL |
| 6 | **IME 挤压触发重排/闪烁**（fix-ime-no-resize） | time | `nonZeroDiffFrames` 异常多 + `reflowSignal=true` | **DONE**（T1 已复现 254/3） |
| 7 | **捏合闪烁** | time | 捏合期间差分形态非 SCROLL_DOWN（有 FULL_REFLOW） | TODO |
| 8 | **上滑看历史失效**（scrollback） | time/space | 本地滚动后内容区形态（Layer 1 需配 scroll 事件） | TODO |

### 符号类（UI dump）

| # | 用户现象 | 算子 | 判据数字 | 覆盖 |
|---|---|---|---|---|
| 9 | **字形缺省/兜底槽**（D-35） | symbol | `fallbackChars` 含预期兜底码点 | PARTIAL |
| 10 | **会话列表陈旧** | symbol | `textSet` vs 期望会话名集 | PARTIAL |
| 11 | **输入框位置异常** | symbol | `inputField.top` 随档位单调性 | **DONE**（N1 已跑通） |

---

## 各算子覆盖的判据汇总

| 算子 | 已 DONE 的考卷 | PARTIAL（需标定） | TODO（Layer 1） |
|---|---|---|---|
| space | S1（末行可见）、S2（捏合对比）、D-38 bottomMargin | 右列截断（需「满宽正常 vs 截断」） | — |
| time | T1（254/3 纯滚动）、T2（发消息无整屏重绘） | 整屏刷判据（reflowSignal 阈值标定） | 捏合闪烁、上滑历史 |
| symbol | N1（输入框 top 单调）、N2（终端 View 收缩） | 字形兜底槽（fallbackChars 判据） | 会话列表陈旧 |

## Layer 1 必须补的（leader 已批注）

1. **空间算子 rightmostNonBgX「恰好满宽正常 vs 被截」区分**：需内容区右缘 vs 期望列宽
   （不是 vs 屏幕右缘）——右列截断判据的关键。
2. **时间算子性能**：T1 跑 32s（254 帧 × ffmpeg 进程开销），可批处理 ffmpeg 优化。
3. **上滑历史 / 捏合闪烁**：需事件驱动（scroll/scale），非静态帧可判。

## 使用方式

```bash
cd test && node runner.js --tag=machine-eye   # 跑全部考卷（localOnly，不起 daemon）
```
每个算子输出 `{status, ...}`；判不出返回 `INDETERMINATE`（不猜）。
