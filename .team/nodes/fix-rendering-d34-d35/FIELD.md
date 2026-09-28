# 现场基 · fix-rendering-d34-d35（leader 手填取证素材）

## 复现已成立（眼见为实，2026-08-12 模拟器实测）

**证据**：`e2e/artifacts/baseline-v2/D35-bypass-actual-rendering.png`
（v2 基线包 `~/Desktop/agentmirror-v2baseline-7c56353.apk`，emulator-5554，1080×2400）

**leader 已亲自目检该图**，所见：终端输出

```
D35 STATUS BAR FIXTURE
□□ bypass permissions on  ·   1 shell
```

前导符号渲染为**两个空方框（豆腐块）**，其后 `bypass permissions on · 1 shell` 文本正常。
**这就是缺陷本体：该画的符号画不出来。**

夹具构造方式（取证席原样命令）：
```
printf '\nD35 STATUS BAR FIXTURE\n\342\217\265\342\217\265 bypass permissions on ·  1 shell\n'
```
`\342\217\265` = UTF-8 E2 8F B5 = **U+23F5**。

## 码位已确证：U+23F5（leader 独立复核）

真实 Claude Code 状态行前缀原始字节：`20 20 e2 8f b5 e2 8f b5 20`
`e2 8f b5` 按 UTF-8 解码 = **U+23F5 BLACK MEDIUM RIGHT-POINTING TRIANGLE CENTRED（⏵）**。

**leader 已独立复核该解码**（字节→码位是算术，不依赖任何席位的取证行为）。
上轮夹具自选的 U+23F5 与本机实际码位一致；
leader 此前怀疑是 U+25B8 —— **怀疑错了**，用户参考图里 `▸` 的形态只是
「有该字形的字体把 U+23F5 画出来的样子」，**字形形状不能反推码位**。

> 取证方式的合规问题（有席位越界读了用户真实 tmux）与本数据是否正确是两件事。
> 越界的后果是「以后不许再碰」，不是「把算对的数作废重算」。本结论有效。

## 用户要看到什么（真机参考图，2026-08-12 用户直接给出）

正常形态：`▸▸ bypass permissions on (shift+tab to cycle)` —— 两个小的右向实心三角，整行红色。
用户在手机上实际看到：**「两个透明的、小的、红色的矩形」** ——
即豆腐块空心方框被整行 ANSI 红色染红。

**注意：这与真相源 raw/046「符号显示为空」是同一个现象的两种描述，不是两种口径。**
leader 此前当作「口径冲突」处理，判断错误，已更正。
红色是正常的（整行本来就红），坏的是符号画不出来。

**因此 `?` 占位不构成通过**：用户要看见那两个三角，把 `□□` 换成 `??` 一样判不合格。
`VISIBLE_FALLBACK` 第四槽作为「真的无论如何画不出来」的最后安全网可以保留，
但 D-35 的验收标准是：**两个 U+23F5 要真正渲染成三角。**

## ⚠️ 本任务最大的坑：仍然不许按单一码位打补丁

码位虽已确证，**禁令不解除**。理由：
用户参考图里的字形与本机渲染形态不同，说明**不同 Claude Code 版本 / 字体环境下码位可能漂移**。
只证明 U+23F5 好了就宣布修复，正是上届「错误标记为已修」的复刻路径。

因此：

- **修复必须是通用的字形回退策略**（`GlyphFallbackPolicy` 层面：字体没有该码位时如何回退），
  使任意缺字形码位都能得到合理呈现。
- **禁止针对 U+23F5 硬编码**（禁止 `if (cp == 0x23F5)` 这类单点补丁）。
  这么改在夹具上会绿，在用户真机上照样是豆腐块——**这正是上届「错误标记为已修」的复刻路径。**
- 验收时若只证明 U+23F5 好了，不算修复。至少要覆盖一组代表性缺字形码位。

## 口径已裁决（真相源，不可推翻）

`requirement-wiki/raw/046`（IMMUTABLE）：**「状态栏 bypass permissions 前的符号显示为空。」**

- ✅ 「符号缺省 / 显示为空 / 豆腐块」= 字形回退问题 —— **正确方向**
- ❌ 「透明红框」= 配色 / 透明度问题 —— **错误描述**，出自交接文档与
  `docs/defects-v5-status.md`，已于 2026-08-12 更正。按此方向改即改错。

## 相关基线现状（改动不得让这些倒退）

来自同一轮取证 `e2e/artifacts/baseline-v2/`：

- **R3 `R3-cjk-powerline.png`**：CJK「中文终端渲染」与 Powerline 符号在 v2 上**有实际渲染**，
  前两个 Powerline 三角可见。**修字形回退不得把这些已经好的搞坏。**
- R1/R2：输入框聚焦、IME 弹起时终端区完整可见且不闪 —— v2 基线已确认。

## 强制回归门（改 termview 必过）

`app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt`

这是 v5 输入框闪烁回归的根因探针（任务 `rootcause-flicker-v5` 产出，已立账）。
根因是 D-31 在新 View 未布局时用 stale viewport 沿 `termview → session` 反向边发错误 resize。
**架构基现算的反向依赖正是 `dev.agentmirror.app.session`——改 termview 必然波及会话页。**
你的改动必须让这个探针保持绿。

## UI 审查关（需求基 raw/018 流程红线，非可选）

- 交件**必附模拟器截图**，全态（正常 / 空 / 错误 / **深色**）落 `e2e/artifacts/ui-review/`
- leader 逐图目检对照七项视觉标准，结论写进证据 JSON 的 `ui_review` 字段
- **测试绿但目检不过 = 不合格打回**
- 第 7 项终端页专项明确要求：**字形完整（无豆腐块）**、等宽对齐、滚动 60fps

## D-34 说明（本任务另一半）

D-34 缩放字体堆叠与 D-35 同任务。**但 D-34 依赖捏合缩放可用，而 v2 基线上捏合完全无反应**
（R5 实测：注入真实双指后前后截图 SHA-256 全等，且未生成 `cell_size.xml`）。
**D-34 因此暂不可复现，本轮挂起，只做 D-35。** 不要去改缩放相关代码。
