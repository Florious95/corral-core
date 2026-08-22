# :terminal — 终端模拟内核（纯 Kotlin/JVM）

自研最小 VT 引擎（选型裁定见 `docs/decisions/term-core.md`），零 Android 依赖，
被 `:app` 渲染层（term-view）消费；只做解析与网格状态，不做绘制。

## 职责与接口

- `TerminalEmulator` — 门面：`feed`（pipe-pane 增量流）、`replaySnapshot`（capture-pane -e
  整屏清屏重建）、`prependHistory`（capture-pane -S 历史头部插入）、`resize`（只换尺寸不
  reflow）、`snapshot`（不可变网格快照）、`damageListener`（脏行区间回调）。
- `AnsiParser` — ANSI/CSI/SGR/OSC 转义状态机（增量 UTF-8 解码）。
- `TerminalGrid` — 字符网格：游标、滚动区域、pending-wrap、宽字符占两格。
- `ScrollbackBuffer` — 本地滚回环形缓冲：容量可配、尾部追加、头部插历史分页。
- `CharWidth` — wcwidth：CJK/emoji 占 2 格、组合记号零宽。
- alternate screen 进入即 `historyAvailable = false`（需求 006 边界）。

## 测试

```
bash -lc 'cd app && ./gradlew -q :terminal:test'
```
