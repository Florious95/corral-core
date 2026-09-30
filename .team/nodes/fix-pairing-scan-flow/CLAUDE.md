# 知识基底 · fix-pairing-scan-flow（真机验收缺陷 B 修复）

## 0. 任务（taskbook.yaml#fix-pairing-scan-flow）
- 目标/验收/写范围见任务书。红测先行：ViewModel 状态机断言修前红。
- 红线：不动 conn/service 实现；token 不进日志。

## 1. 现场基（真机截图实证，2026-08-09）
- 现象：扫码后屏幕显示"已识别: {原始 JSON 全文}"+提示语，随后**无任何后续**——无连接中状态、无失败报错、手填表单仍空白。用户等待无果（QR 地址恰好不可达=缺陷 A，但即使可达，无进度态也不合格；即使失败，无报错+无回填更不合格）。
- 整改口径（三点全做）：①识别成功→立即自动发起配对，显示"连接中…"（含目标地址）；②失败→显式错误（区分超时/拒绝/解析失败）+ 重试按钮；③**识别值自动回填手填表单**（url+token 落输入框，用户可改地址重试——这是缺陷 A 场景的自救通路）。坏 payload（缺字段/坏版本）也要显式提示而非静默。
- 顺带：屏上不要再展示原始 JSON 全文（含 token 的裸 JSON 上屏超出"QR 是 token 唯一合法出口"的必要范围）——改为"已识别 · 正在连接 <地址>"摘要，token 不上屏。
- 相关代码：pairing/PairingViewModel.kt（状态机）、PairingScreen.kt（扫码回调与展示）、QrPayloadParser.kt。既有 13 测形状可扩展；试配对状态机已存在（手填路径在用），扫码路径接上同一状态机即可。
- pairing-ui 沉淀必读：.team/nodes/pairing-ui/CLAUDE.md §5（试配对用独立 ConnectionManager、VM 重置陷阱④）。

## 2. 需求基（指针）
1. requirement-base/entries/003-对话体验四标准.md（静默失败最高罪——本缺陷的判据）
2. requirement-base/entries/007-联网模型-tsnet与扫码.md（扫码即连承诺）

## 3. 经验基
- 最小修复；注释红线；净化前缀；落盘保持 :app 可编译；交件前全量门自查。

## 4. 沉淀区（唯一允许你追加写入的区域）
