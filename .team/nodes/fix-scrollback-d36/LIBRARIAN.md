# 需求基 · fix-scrollback-d36（撞库回执）

> 检索者：leader（w-librarian 席位 18:26 后无应答，leader 直接查需求权威 `requirement-wiki/`）
> 需求权威：`requirement-wiki/`；不可变真相源：`requirement-wiki/raw/`
> 本回执只摘原文，不含检索者观点。

## 命中一：D-36 原文（raw/047，IMMUTABLE）

`requirement-wiki/raw/047-向上滑动无法查看历史.md` 全文要点：

```
# 047 向上滑动无法查看历史消息
- 状态：已裁定（本轮缺陷，需修复）
- 类型：缺陷 D-36
- 出处：用户 2026-08-12 真机实测

## 缺陷现象
在会话页向上滑动无法查看历史消息。

## 原因推测
滑动手势未转成 scrollback 请求，或本地滚动逻辑未接上。

## 关联
[[006]] 秒开与本地滚动（scrollback 本地化）、protocol.md scrollback 帧。
```

维基命题页 `wiki/claims/向上滑动查看历史.md`：confidence 0.75 ——
「现象确凿，根因推测（手势未转成请求）」。

**注意「原因推测」三字**：raw 把根因明确标为推测而非裁定。
开发席不得把它当既定根因，必须自己在代码与实测上定位。

## 命中二：母概念（raw/006）

`wiki/concepts/秒开与本地滚动.md` + `wiki/techniques/scrollback本地化.md`：
D-36 属 scrollback 本地化的**手势接入缺陷**。协议侧 scrollback 帧定义见 `docs/protocol.md`
（二进制流帧 kind=3，payload 头部 12 字节元数据：req_id / from_line / line_count，均大端）。

## 命中三：UI 视觉标准（raw/018）中与本任务相关项

- 第 7 项终端页专项：**滚动 60fps**
- 审查关：UI 任务交件必附模拟器全态截图落 `e2e/artifacts/ui-review/`，
  leader 逐图目检写进证据 JSON `ui_review` 字段，**测试绿但目检不过 = 不合格打回**

## 未命中

- D-36 未见对「向上滑动」具体交互形态（惯性滚动 / 分页 / 拉取阈值）的用户裁定，
  即**交互细节无既定约束**，但不得自行发明新交互——超出既有 scrollback 语义前先问 leader。
