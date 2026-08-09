# 现场基 · ui-redesign（leader 取证素材，2026-08-09）

## 真机实锤缺陷（用户截图三张）
- 图28 列表页：内容顶进系统状态栏（无 safe-area）；无标题栏；长 cwd 路径换四行撑爆；行距巨大；会话数数字与状态徽章悬空无对齐；无视觉层级。
- 图29 会话页：顶栏被完整会话名（team-agent-leader-claude-code-agent-…64 字符）撑爆两行，压住返回键与状态栏。
- 图31 会话页键盘弹出：终端区与键条/输入区之间出现整屏巨幅空白（IME insets/adjustResize 重排缺陷——应内容重排跟随，不是留洞）。

## 用户原话（回炉动因，标准感受基线）
「已经跟你说了用户体验要达到什么样的标准，结果就这么简陋的 UI，直接可以扔进垃圾桶。」

## leader 裁定
- 判定权威 requirement-base/entries/018 七条；交件必附全页全态截图落 e2e/artifacts/ui-review/（缺截图不受理）；不动业务逻辑层；termview 画布内部不进（豆腐块另案）。
- 深色切换：`adb shell cmd uimode night yes|no`；截图：`adb exec-out screencap -p > e2e/artifacts/ui-review/<page>-<state>-<theme>.png`。
