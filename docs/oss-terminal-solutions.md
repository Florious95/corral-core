# 开源终端方案调研 · 四类缺陷的落地方案

> 任务：`research-oss-terminal-solutions`（不改产品代码，写盘仅 `docs/`）
> 调研方式：`git clone` 到 `/tmp/oss-research/` 只读研读，许可证逐一核验。
> 结论格式：每个开源实现 → 它解决了哪条 → 具体怎么做（数据结构/算法/关键函数）→ 出处 → 许可证 → 能否采用 → **落到我们改哪个函数的什么行为**。

---

## 调研对象与许可证总览

| 仓库 | 版本/commit | 许可证 | 可否采用 |
|---|---|---|---|
| herdrdev/herdr | main@今天 | **Apache-2.0** | ✅ 可引用 |
| dcolinmorgan/herdr-remote | main@今天 | **AGPL-3.0** | ⚠️ 只借鉴模型，**不复制代码** |
| nikok6/herdr-mirror | main@今天 | MIT | ✅ 可引用 |
| smarzban/herdr-file-viewer | main@今天 | MIT | ✅ 可引用 |
| ghostty (herdr 内嵌 `vendor/libghostty-vt`) | 1.3.2-HEAD | MIT | ✅ 可引用（算法，不是 C ABI） |
| xterm.js | main@今天 | MIT | ✅ 可引用（web/ 已在用） |
| alacritty (`alacritty_terminal`) | main@今天 | Apache-2.0 | ✅ 可引用 |
| wezterm (`term` crate) | main@今天 | MIT | ✅ 可引用 |
| libvterm | main@今天 | MIT | ✅ 可引用（模型） |
| mosh | main@今天 | GPLv3 | ⚠️ 只借鉴**算法**（diff 渲染），不复制代码 |

> 红线核验：终端内核必须 Apache-2.0 兼容。Termux 系 GPLv3 不可用。
> 上表中只有 mosh 是 GPL——我们只借鉴它的 **diff_from 逐格 diff** 算法思想（算法本身不受版权保护），不复制任何 C++ 代码。

---

## 问题 1：最右一列文字跑出屏幕、只显示半个字

### 1A. herdrdev/herdr —— 移动宽度专属降级 + 主动截断

**解决了什么**：窄屏下 UI 元素不是被硬塞进屏，而是**换一套移动专属布局**，文字主动 `truncate_end`，绝不让字符半截露出。

**具体做法**：
- `src/ui/mobile.rs:50` `is_mobile_width(area, threshold)` —— 宽度 ≤ threshold 判定为移动宽度，整个 UI 切到移动视图（`render_mobile_panel`）。
- `src/ui/mobile.rs:344` `truncate_end(&name, name_w.saturating_sub(4) as usize)` —— 所有文本在渲染前按 `area.width` 预算截断。
- `src/ui/mobile.rs:124` `mobile_switcher_max_scroll` —— 移动视图用**虚拟滚动**（`doc_y` 行号 + `visible_y` 裁剪），不依赖终端网格 cols。

**出处**：`herdrdev/herdr/src/ui/mobile.rs` 的 `is_mobile_width` / `truncate_end` / `mobile_switcher_max_scroll`。

**许可证**：Apache-2.0 ✅ 可引用。

**落到我们**：这是 UI 层策略，与我们的内核关系小。可借鉴「窄屏降级视图」思路到 `SessionScreen.kt`（Compose 屏），但**我们四条问题的真正根源在内核/渲染层**，1A 只是参考。

### 1B. alacritty —— 宽字符 reflow 的 LEADING_WIDE_CHAR_SPACER

**解决了什么**：窄屏 cols 收敛时，宽字符（CJK）跨越行尾不会「劈开」，而是**用前置空格符占位**，字符整体移到下一行。

**具体做法**（`grow_columns` reflow 时）：
```rust
// 上一行末尾是宽字符的续格 → 删掉续格，把宽字符主格腾回上一行
if last_len >= 1 && last_row[last_len-1].flags().contains(LEADING_WIDE_CHAR_SPACER) {
    last_row.shrink(last_len - 1);
    last_len -= 1;
}
// 下一行若以宽字符开头但放不下 → 留一个 LEADING_WIDE_CHAR_SPACER 占位
let mut cells = if row[Column(len-1)].flags().contains(WIDE_CHAR) {
    num_wrapped -= 1;
    let mut cells = row.front_split_off(len - 1);
    let mut spacer = T::default();
    spacer.flags_mut().insert(LEADING_WIDE_CHAR_SPACER);
    cells.push(spacer);
    cells
} else { row.front_split_off(len) };
```
**核心**：宽字符的两种状态（`WIDE_CHAR` 主格 / `LEADING_WIDE_CHAR_SPACER` 续格）是**行尾 reflow 的硬边界**，reflow 时先检测续格、删除、再决定占位。

**出处**：`alacritty/alacritty_terminal/src/grid/resize.rs` `grow_columns`（101-244 行）。

**许可证**：Apache-2.0 ✅ 可引用。

**落到我们**：我们的 `TerminalGrid.write` 第 54-61 行已经处理了「宽字符放不进行尾→末格留空白，整字符落到下一行行首」，**这块已与 alacritty 一致**。但我们的 `resize`（`TerminalGrid.resize` 263 行）是「只换尺寸不 reflow」——**没有 reflow 就没有行尾宽字符再处理**。问题 1 的"半个字"真正的根源在**渲染层的 clip**，看 1D。

### 1C. xterm.js —— BufferReflow 宽字符 reflow 边界

**解决了什么**：缩列 reflow 时宽字符不得劈开行尾。

**具体做法**（`reflowSmallerGetNewLineLengths`）：
```js
const endsWithWide = wrappedLines[srcLine].getWidth(srcCol - 1) === 2;
if (endsWithWide) srcCol--;
const lineLength = endsWithWide ? newCols - 1 : newCols;
```
宽字符占 2 列，若新列宽在宽字符中间，就把换行点回退 1 列，让宽字符整体落到行尾之后（下一行）。

**出处**：`xterm.js/src/common/buffer/BufferReflow.ts:203-207`。

**许可证**：MIT ✅ 可引用。

**落到我们**：同 1B——内核的 reflow 边界处理已有雏形，但**我们根本没有 reflow**（`TerminalGrid.resize` 不重排）。这条与 1B 指向同一个改进点：**给 `TerminalGrid.resize` 加 reflow（用 pendingWrap 时加 WRAPLINE 标记 → 缩列时合并重排）**。

### 1D. 根因定位：最右列半字的真正来源（渲染层 clip）

结合我方代码，问题 1 的**直接根因**不是 reflow，是 **`TermSurfaceView` 渲染时对超出可视区的列没有裁剪，直接 drawText 导致字形被 Canvas 切半**。

- `TermSurfaceView.drawLine`（222 行）按 `cells` 逐格铺背景，`x` 一直推进到 `cols-1`；最右列若宽字符主格正好落在 `cols-1`，`drawCentered` 里 `x + cellPx` 超出 `width`，字形画在画布外被硬件裁成半字。
- 对比 xterm.js `Viewport._sync`：它的 scrollHeight = `cell.height * buffer.lines.length`，**整个 buffer（含 scrollback）都是可滚动高度，DOM 滚动天然裁剪**，不存在「把某列画出去」。

**落到我们**：`TermSurfaceView.drawLine`（222 行）在铺格前先算 `clipRight = width - cellW`，`drawCentered`（308 行）在 `x + cellPx > width` 时把字形收进 `x = width - cellW`（右对齐塞回可视区），或整字符让位到下一行（1B/1C 的 reflow 语义）。**内核 `TerminalGrid.write` 的 54-61 行已经保证宽字符不占 `cols-1` 列位**（放不下就移到下一行），所以渲染层只要**保证不把 `cols-1` 的字符画出去**即可。

---

## 问题 2：发消息后整屏自上而下重刷，底部最新消息看不到

### 2A. herdrdev/herdr —— ghostty 行脏区 patch 渲染

**解决了什么**：只重画脏行，不整屏重刷。

**具体做法**（`ghostty_collect_dirty_patch`）：
```rust
while y < area_height && rows.next() {
    let dirty = rows.dirty()?;           // 每行的 dirty bit
    if dirty {
        // 只收集 dirty 行的 cells
        patch_rows.push((y, patch_cells));
    }
    y += 1;
}
```
ghostty 内核维护**每行 dirty bit**，renderer 只把 dirty 行收进 patch。渲染循环：`render_state.update(terminal)` → `dirty()` 三态（Clean/Partial/Full）→ Partial 时逐行收集 → `set_dirty(Clean)`。

**出处**：`herdrdev/herdr/src/pane/terminal.rs:2270` `ghostty_collect_dirty_patch`。

**许可证**：Apache-2.0 ✅ 可引用。

**落到我们**：我们的内核**已经**有 `TerminalGrid.takeDirty()`（294 行）返回脏行区间，但**渲染层是整帧全窗口重绘**（`TermSurfaceView.onDraw` 186 行「铺可见窗口全部行」+ `frameCallback` 127 行「takeDamage 只作排空信号」）。这是有意设计（避免局部重绘 bug），但**副作用是发消息时整屏重画**。
**改法**：`TermSurfaceView.onDraw` 改为只重画 `takeDamage()` 返回的脏逻辑行（转成窗口内行号），其余行保留上帧画布内容。这直接对应 ghostty 的 patch 模式。同时把 `frameCallback` 的「takeDamage 只排空」改为「takeDamage 结果直接作为本帧重画清单」。

### 2B. mosh —— 逐 cell diff 增量传输

**解决了什么**：网络侧只传变化的格子，避免整屏重刷。

**具体做法**：
```cpp
string Complete::diff_from( const Complete& existing ) const {
  if ( !( existing.get_fb() == get_fb() ) ) {
    if (尺寸变化) { 发 resize }
    string update = display.new_frame( true, existing.get_fb(), terminal.get_fb() );
    if ( !update.empty() ) { 发 hostbytes }
  }
}
```
`TerminalDisplay::new_frame` 逐格比较新旧 framebuffer，只把差异格编码成 `hostbytes`。

**出处**：`mosh/src/statesync/completeterminal.cc:69` `diff_from`；`src/terminal/terminaldisplay.cc`。

**许可证**：GPLv3 ⚠️ 只借鉴算法，不复制代码。

**落到我们**：我们的 delta 已经是「服务端 pipe-pane 增量字节流」喂给内核，**网络侧已经是增量的**。问题 2 的重刷发生在**客户端渲染层**（2A 已覆盖）。mosh 这层我们不需要改——只是佐证「增量是对的，整屏重刷是渲染层 bug」。

### 2C. 根因：整屏重刷的另一个来源——`emulator.snapshot()` 全量快照

**补充**：`TermViewPresenter.beginFrame`（269 行）`frameSnapshot = emulator.snapshot()` 每次全量深拷贝整屏，配合渲染层整帧重绘，发消息一次 `feed` → `flushDamage` → 整屏 dirty → 整帧重画。改 2A 后快照开销也可顺带降低（只深拷贝脏行）。**但** snapshot 是内核线程序列化屏障，保留无害。

---

## 问题 3：向上滑完全失效——App 只加载一屏，滑不动，用户要「像 CLI 滚鼠标滚轮翻到最开始」

### 3A. herdr-remote —— 移动端用原生文本滚动，不做虚拟网格

**解决了什么**：手机端**根本不渲染终端网格**，而是**把 pane 输出当纯文本推进原生 ScrollView**，滚动手势由系统处理，天然可滚到全部历史。

**具体做法**（iOS）：
```swift
ScrollView {
    Text(relay.paneHistory[agent.id] ?? ...)
        .font(.system(.caption, design: .monospaced))
        .frame(maxWidth: .infinity, alignment: .leading)
}
```
`paneHistory` 是**累计追加的文本**，ScrollView 高度 = 全部文本高度，上滑即滚动到任意历史。

**按需拉取**（`relay/herdr_relay.py:208`）：
```python
def read_pane(pane_id, remote=None):
    raw = run_herdr("pane", "read", pane_id, "--lines", "50", "--source", "recent", remote=remote)
    ...
    return "\n".join(lines[-20:])
```
只拉最近 N 行文本，**不是全量网格快照**。

**出处**：`dcolinmorgan/herdr-remote/herdi-ios/Sources/Views/AgentDetailView.swift:27-31`；`relay/herdr_relay.py:208`。

**许可证**：AGPL-3.0 ⚠️ 只借鉴模型，不复制代码。

**落到我们**：这是问题 3 的**最根本解法**：我们的 scrollback 历史**没有真正加载到本地**。当前 `SessionViewModel` 只在**滚动到顶（`window.first == 0`）**才 `requestOlderHistoryPage()` 补页，但：
1. **首屏打开时 `window` 底部贴的是服务端当前屏幕**，向上滑需要先滚动过**整屏高度**才触发补页——用户看到的就是「滑不动」；
2. 服务端 scrollback 历史量 > 本地 capacity 时，本地永远只看到最近一段。

**改法**：参照 herdr-remote —— `SessionViewModel.requestOlderHistoryPage` 从「滚动到顶才补」改为**滚动位置距顶部 < 1 屏即预取下一页**（`syncFromPresenter` 里 `atHistoryTop` 改为 `window.first < HISTORY_PAGE`），并在 `onBinary(SCROLLBACK)` 时**不丢弃头部**、把历史行持续 append。同时把 `HISTORY_PAGE` 从 400 加大，保证上滑流畅。

### 3B. libvterm —— screen 与 scrollback 的显式分离回调

**解决了什么**：scrollback 与屏幕用**回调边界**分离，scrollback 内容可任意回放。

**具体做法**：
```c
// 屏幕行滚出顶部 → 回调，由宿主存 scrollback
if (screen->callbacks->sb_pushline) sb_pushline_from_row(screen, row, lineinfo->continuation);
// resize 时用 sb_popline 把历史回填进屏幕
if (screen->callbacks->sb_popline) { ... }
```
`vterm_screen.c` 用 `sb_pushline`/`sb_popline` 把屏幕滚出/回填动作**交给宿主回调**，内核不持有 scrollback——宿主（如 tmux、libvterm 使用者）自己管理历史。

**出处**：`libvterm/src/screen.c:209` `sb_pushline_from_row`、`src/screen.c:681`。

**许可证**：MIT ✅ 可引用（模型）。

**落到我们**：我们的 `TerminalEmulator` 已经是「屏幕滚出 → `scrollback.appendTail`」的分离（`TerminalEmulator.init` 94 行），结构与 libvterm 一致。问题不在分离本身，而在**补页策略**（3A）。

### 3C. xterm.js —— scrollHeight = 全部 buffer 行，DOM 原生滚动

**解决了什么**：视口滚动条的高度 = **全部 buffer（含 scrollback）行数**，滚动位置直接映射到 buffer 行号，天然支持翻到最老历史。

**具体做法**：
```js
this._scrollableElement.setScrollDimensions({
  height: canvas.height,
  scrollHeight: this._renderService.dimensions.css.cell.height * this._bufferService.buffer.lines.length
});
```
`buffer.lines` 是**含 scrollback 的全部行**（`CircularList`），`ydisp` 是 buffer 内位移，`scrollLines` 把滚动手势换算成行位移。

**出处**：`xterm.js/src/browser/Viewport.ts:187-190` `_sync`、`src/common/buffer/Buffer.ts`。

**许可证**：MIT ✅ 可引用。

**落到我们**：我们的 `TermViewPresenter.window`（129 行）已经是「逻辑行坐标 = scrollback.size + 屏幕行」，与 xterm.js 的 `ydisp` 完全同构。**问题在于本地 scrollback 容量和补页触发**（3A 已覆盖）。视口坐标模型不需要改。

### 3D. herdr 主仓库 —— scroll offset from bottom 滚动模型

**解决了什么**：滚动状态用「距底部偏移」而非「绝对行号」，天然锚定最新输出。

**具体做法**：
```rust
pub fn set_scroll_offset_from_bottom(&self, lines: usize) {
    ghostty_set_scroll_offset_from_bottom(&mut core.terminal, lines);
}
fn ghostty_set_scroll_offset_from_bottom(terminal, offset) {
    let max_offset = scrollbar.total.saturating_sub(scrollbar.len);
    let offset = offset.min(max_offset);
    if offset == 0 { terminal.scroll_viewport_bottom(); }
    else { terminal.scroll_viewport_row(max_offset - offset); }
}
```
`scroll_metrics()` 返回 `offset_from_bottom`（总行 - 视口顶），**新输出到达时 offset 不变 → 视口钉在历史**。

**出处**：`herdrdev/herdr/src/pane/terminal.rs:1638` `set_scroll_offset_from_bottom`、`:1646` `scroll_metrics`。

**许可证**：Apache-2.0 ✅ 可引用。

**落到我们**：我们的 `TermViewPresenter.topLine` 是**绝对逻辑行号**，锁定态冻结。对比 herdr 的 offset-from-bottom：当历史向前补页（`prependHead` 使 scrollback.size 增大）时，绝对行号会「漂移」（历史行前插 → 原行号内容变了）。**改法**：`TermViewPresenter.topLine` 从绝对行号改为**距底偏移**（`offsetFromBottom = logicalCount - topLine - 1`），补页/追加时偏移不变 → 视口稳定；跟随态 offset=0。这同时解决问题 4 的「补页后视口跳变」。

---

## 问题 4：捏合后整屏重绘闪烁，且字号不持久化

### 4A. herdrdev/herdr —— resize 锚定 offset + 底部留空重放

**解决了什么**：resize 后视口不跳、不闪。

**具体做法**（`GhosttyPaneCore::resize`）：
```rust
let offset_from_bottom = scrollbar.total.saturating_sub(scrollbar.offset + scrollbar.len);
// resize 前记录 offset
terminal.resize(cols, rows, cell_w, cell_h);
// resize 后底部若空白，回放最近 ANSI 并滚回底部
let bottom_is_blank = ghostty_detection_text(&mut core).map(|t| t.trim().is_empty())...;
if bottom_is_blank {
    if let Some(ansi) = replay_ansi { core.terminal.scroll_viewport_bottom(); core.terminal.write(ansi.as_bytes()); }
}
ghostty_set_scroll_offset_from_bottom(&mut core.terminal, offset_from_bottom);  // 恢复锚定
```

**出处**：`herdrdev/herdr/src/pane/terminal.rs:1550-1611`。

**许可证**：Apache-2.0 ✅ 可引用。

**落到我们**：我们的 `TermViewPresenter.onFontSizeChanged`（219 行）`recomputeGeometry` + `updateVisibleRows` 后直接 `onFrameRequested`——**resize 后没有锚定 offset，也没有回放**。`TerminalEmulator.resize` 直接 `main.resize`（左上角保留，不 reflow），随后 `flushDamage` 整屏标脏 → 整帧重绘 → 闪烁。
**改法**：`TermViewPresenter.onFontSizeChanged` 里，resize 前记录 `offsetFromBottom`，resize 后：
1. 若跟随态（offset=0）且 resize 后底部空白，`replaySnapshot` 重放（herdr 的 replay_ansi 逻辑）；
2. 恢复 `topLine` 到 offset 对应的行。
`TermSurfaceView` 的闪烁缓解：把 resize 引发的重绘与增量帧合并（`frameCallback` 里加「重绘 pending」标志，避免同一帧两次 invalidate）。

### 4B. 字号持久化 —— 我们完全没有

**确认**：`grep` 全仓库，字号只在 `TermViewPresenter.cellWidth/cellHeight`（内存态，`TermSurfaceView` 传入），**无 DataStore/SharedPreferences 持久化**。`SessionViewModel` 构造 `TermViewPresenter(emulator){...}` 时用默认 `DEFAULT_CELL_WIDTH/HEIGHT`（10x20）。

**对比开源**：
- herdr-remote 移动端：字号是**系统 Text 的 `font(.system(.caption))`**，跟随系统，天然稳定。
- xterm.js：`fontSize` 走 `theme`，可配置且**持久化**。
- ghostty：字号持久化在 `config`（`font_size`）。

**落到我们**：在 `SessionViewModel` 或 `SessionScreen` 层，用 `DataStore`（工程已有 `PairingConfigStore` 先例）持久化 `cellWidth/cellHeight`，会话打开时读出注入 `presenter`，捏合时写回。这是**纯接线**，不动渲染内核。

### 4C. 闪烁的另一个来源：reflow 缺失

捏合 → `onResizeRequest(rows, cols)` → 服务端 resize → 服务端快照重放 → 客户端 `replaySnapshot` 清屏重建 → 整屏闪。**只要**不做 reflow，resize 必然清屏重放（`TerminalEmulator.replaySnapshot` 133 行「按 cols x rows 清屏重建」）。
对照：
- wezterm `term/src/screen.rs:193` `resize` **做 reflow**（`rewrap_lines` 缩列合并、`line.wrap` 扩容重排），内容连续不白屏。
- alacritty `grid/resize.rs` `grow_columns`/`shrink_columns` 也 reflow。
- 我们的 `TerminalGrid.resize`（263 行）明确注释「只换尺寸不 reflow（005：重排是 CLI 的事）」。

**落到我们**：这是**架构决策**——要么接受「resize 由服务端快照重放」（那闪烁要靠 4A 的锚定+合并帧缓解），要么给 `TerminalGrid.resize` 加 reflow（吸收 wezterm `rewrap_lines` 的 WRAPLINE 逻辑行折叠模型）。**推荐后者**：reflow 后本地无需等快照重放即连续，闪烁根除。`TerminalGrid.write` 已在 69-71 行维护 pendingWrap，只需在行对象上落一个 WRAPLINE 标记，resize 时仿 wezterm 合并/重排。

---

## 汇总：四条问题各自的推荐落地路径

| 问题 | 直接根因 | 开源模型 | 落到我们改的函数 |
|---|---|---|---|
| 1 右列半字 | 渲染层把宽字符画过画布右缘被裁 | alacritty LEADING_WIDE_CHAR_SPACER / xterm BufferReflow / herdr truncate | `TermSurfaceView.drawLine`/`drawCentered`（渲染裁剪）；`TerminalGrid.resize` 加 reflow（根治） |
| 2 整屏重刷 | 渲染层整帧重绘，无行级 patch | herdr `collect_dirty_patch` / mosh `diff_from` | `TermSurfaceView.onDraw` + `frameCallback` 改为只重画 `takeDamage()` 的脏行 |
| 3 上滑失效 | 补页只发生在「滚到顶」，历史没真正加载 | herdr-remote 原生文本滚动 / libvterm 回调分离 | `SessionViewModel.requestOlderHistoryPage`（提前补页）+ `TermViewPresenter.topLine` 改 offset-from-bottom |
| 4 闪烁+字号 | resize 无锚定/无 reflow，字号无持久化 | herdr resize 锚定 / wezterm reflow / ghostty config | `TermViewPresenter.onFontSizeChanged` 锚定 offset；`TerminalGrid.resize` reflow；`SessionViewModel` DataStore 持久化字号 |

---

## 附：本次 clone 的源码位置（临时，已/待清理）

```
/tmp/oss-research/herdr-herdr/            # herdrdev/herdr (Apache-2.0)
/tmp/oss-research/herdr-herdr-remote/     # dcolinmorgan/herdr-remote (AGPL-3.0)
/tmp/oss-research/herdr-herdr-mirror/     # nikok6/herdr-mirror (MIT)
/tmp/oss-research/herdr-herdr-file-viewer/# smarzban/herdr-file-viewer (MIT)
/tmp/oss-research/xterm.js/               # xterm.js (MIT)
/tmp/oss-research/alacritty/              # alacritty (Apache-2.0)
/tmp/oss-research/wezterm/                # wezterm (MIT)
/tmp/oss-research/libvterm/               # libvterm (MIT)
/tmp/oss-research/mosh/                   # mosh (GPLv3，只借鉴算法)
```

本工程目录 `docs/` 之外无写盘，外部仓库全部在 `/tmp`，任务完成后清理。
