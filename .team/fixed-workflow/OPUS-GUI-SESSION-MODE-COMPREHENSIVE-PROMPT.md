# Comprehensive Mission Directive: End-to-End Native GUI Mode Evolution (Issue #50)

> **Role Assignment**: Claude Opus 5.5 (`claude-opus-5-5`, `provider: claude`)  
> **Mandate**: End-to-End High-Fidelity UI/UX Architecture, Visual Design & Engineering across Native GUI and TUI Enhancements  
> **Target Standard**: Museum-grade AI Native Experience rivaling OpenAI Codex App & Meta Muse  
> **Baseline State**: Tag `baseline-20261005-native-gui-v1` (HEAD `7eead93de`, PR #53)  
> **Daemon State**: Production Daemon PID 22168 listening on `*:9900` (incorporating `conversation_v1` and socket symlink canonicalization)  
> **Authoritative Technical Blueprint**: `.team/fixed-workflow/GUI-OPTIMIZATION-CODE-BLUEPRINT.md` (543 lines of code pre-reading and architectural mapping across 30 source files)

---

## 1. Executive Summary & Core Philosophy

You are commissioned with full architectural and creative authority to lead the complete evolution of Corral's Native GUI Conversation Mode and its bidirectional interaction with the classic TUI mode.

The user has explicitly tested the active baseline (`baseline-20261005-native-gui-v1`) on real devices and confirmed:
**"虽然还有优化点，但是它依然是可用的" (Although there are optimization points, it is already usable).**

Your mission is to take this working foundation and elevate it into a world-class, seamless product:
1. **Performance Far Exceeding TUI**: Zero ANSI sweeping, zero viewport reflow lag. Structured delta streaming, reverse-layout transcript anchoring, and buttery smooth 60/120fps motion.
2. **Dual Theme Suite Support**: Full, native adaptation to both of Corral's design systems: **Liquid Glass (液态玻璃)** and **Modernism (现代主义)**, across all 30 terminal color palettes.
3. **Ergonomic Two-Stage Input Docks**: Both GUI and TUI docks must maximize screen space when idle and reveal rich tools upon focus.
4. **Resilient Session Swapping & Safety**: Seamless switching between Native GUI and genuine interactive Ink TUI within the same tmux pane using Pi's native session resumption (`pi --session <id>`), protected by in-flight task guards.

---

## 2. Preserved Invariants & Architectural Anchors (Strict Rules)

1. **Single Connection Invariant (No Second Socket)**:
   - All conversation frames (`conversation_*`) are multiplexed over the existing persistent `/ws` connection via the `ConversationTransport` decorator (`ConversationCenter.kt:142–248`).
   - Inherits `ConnectionManager` resilience (15s WebSocket Ping keepalive, foreground resume self-healing, multi-path Tailscale `100.x.y.z` / LAN routing).
   - Never open a secondary WebSocket (e.g. `/gui`), which previously caused 404 handshake crashes.
2. **State Retention Guard (`guiReadyOnce`)**:
   - `SessionRoute.kt:118–144`: Once a session enters `gui_ready`, any transient disconnect must remain in the GUI error/reconnect state.
   - Never trigger an unprompted cold switch to TUI during user touches or disconnects, which previously blocked the main thread on `TermSurfaceView` glyph drawing and triggered a 21,209ms ANR.
3. **Socket Canonicalization Invariant**:
   - `server/internal/guirpc/worker.go:59–69`: Private IPC keys must canonicalize socket paths (`filepath.EvalSymlinks`) to prevent macOS `/tmp` vs `/private/tmp` alias hash splitting. Public session refs remain intact.
4. **Classic TUI Zero Regression**:
   - When viewing in TUI mode or on sessions without structured capabilities, the classic terminal canvas (`TermSurfaceView`) and fixed dock remain 100% untouched and functional.

---

## 3. The 8 Core Deliverables (Code Maps & Implementation Blueprints)

### Deliverable 1: Collapsible Skill Invocation Cards (Skill 折叠卡片化)
- **Code Reference**: `ConversationState.kt:387–459` (Reducer), `ConversationItems.kt:101–266` (Card components), `ConversationScreen.kt:398–442`.
- **Current Defect**: Invoking a skill via `/skill <name>` or injecting a skill prompt dumps a giant block of raw text directly into the conversation stream, overwhelming the viewport.
- **Specification**:
  - Automatically identify skill invocations (e.g. user messages starting with standard skill XML wrappers or explicit skill slash invocations) and encapsulate them into a compact, elegant `SkillCard`.
  - **Collapsed State (Default)**: Displays an expressive icon (e.g. Sparkle / Wrench), skill name badge, concise summary, and line-count tag.
  - **Expanded State**: Spring-animated expansion revealing the formatted prompt content, with a quick "Copy" action and a collapse toggle.

### Deliverable 2: Atomic Multiline Prompt Submission (多行输入原子发送)
- **Code Reference**: `ConversationDock.kt:142–213`, `ConversationCenter.kt:208–226` (`sendPrompt`), `server/internal/guirpc/worker.go:210–232`.
- **Current Defect**: Pasting or typing multiline text with newlines (`\n`) splits the input into multiple discrete messages upon sending. The agent receives and processes each line sequentially, breaking structured instructions and code snippets.
- **Specification**:
  - All newlines (`\n`) inside the input field must be preserved as formatting whitespace.
  - Submitting input must transmit the entire text atomically as a **single user prompt payload / single turn** (`conversation_command` with type `prompt`) to the agent loop.
  - The resulting user message bubble renders the multi-line layout intact.

### Deliverable 3: Bottom Scroll Jitter & Flash Elimination (滑到底部/按钮回底防抖防闪)
- **Code Reference**: `ConversationScreen.kt:222` (`rememberLazyListState`), `:328` (`scrollToLatest`), `:409–411` (`following` offset check), `:419–442` (`items.asReversed` with `reverseLayout = true` and `spacedBy(10.dp, Alignment.Bottom)`).
- **Current Defect**:
  1. Tapping the downward floating action button in the bottom right jumps to the latest message, but upon landing, the viewport stutters/jitters and flashes.
  2. **Finger Swiping**: Swiping/scrolling down with fingers to the very bottom exhibits the exact same sudden jump/jitter at the bottom edge (`offset = 0`).
- **Specification**:
  - Decouple list clearance measurement from floating downward arrow visibility to eliminate measurement feedback loops.
  - Audit reverse-layout anchoring, overscroll physics, and `LazyListState` measurement at bottom offset `0`.
  - Eliminate layout re-measuring glitches, snapping artifacts, and redraw flashing. Both button-initiated scrolling and manual finger swiping must come to a buttery smooth, solid halt at the bottom.

### Deliverable 4: Top Floating Bar Model & Thinking Effort Switcher (顶部切模型与思维强度)
- **Code Reference**: `ConversationScreen.kt:649–755` (`ConversationHeader`), `ConversationState.kt:63–87`, `server/internal/guirpc/worker.go:680–720`.
- **Specification**:
  - Tapping the top floating navigation bar triggers a frosted glass popover / sheet.
  - **Model Selector**: Displays available models (e.g. Luna `gpt-6-luna`, Sol `gpt-6.1-sol`, Sonnet, etc.) with clean selection chips.
  - **Thinking / Effort Selector**: Provides an intuitive segmented control for thinking intensity (Low, Medium, High, Max).
  - Tapping an option immediately updates the session's model/effort configuration via structured protocol frames.

### Deliverable 5: In-Pane TUI <-> GUI Session Process Swap with In-Flight Guard (双向无损进程置换与安全拦截)
- **Code Reference**: `server/internal/guirpc/worker.go:120–165`, `server/internal/bridge/`, `SessionRoute.kt:118–144`, `ConversationState.kt:63–110`.
- **Specification**:
  - **Seamless Process Swap**: Utilizing Pi's native session persistence (`--session <id>`), allow switching between:
    * **GUI Mode**: Running `pi --mode rpc --session <id>` inside the tmux pane, driving the native Compose conversation stream.
    * **TUI Mode**: Running interactive `pi --session <id>` inside the same tmux pane, restoring the authentic Ink terminal with live cursor, colors, and key navigation.
  - **In-Flight Task Guard (运行中告警拦截)**:
    * Check real active work: `state.running || state.compacting || state.queued > 0 || hasUnfinishedTools`.
    * If active work is present, attempting to switch MUST trigger a clear confirmation dialog:
      `"当前任务正在运行中，切换模式将中断并丢弃当前未完成任务，是否确认切换？"`
    * "取消" (Cancel) keeps the session in its current mode without disruption.
    * "确认" (Confirm) gracefully aborts in-flight turns and performs the process swap.

### Deliverable 6: Dual Theme Suite Adaptation: Liquid Glass vs Modernism (双主题体系全量适配)
- **Code Reference**: `theme/ThemeSuite.kt:189–215`, `ModernistThemeSuite.kt:36–69`, `ui/components/LiquidGlass.kt:301–387` (`glassControl` vs `ruledSurface`), `ConversationPalette.kt:63–137`, `ConversationScreen.kt:149–159`.
- **Specification**:
  - Read `LocalThemeSuite` in GUI components:
    * **Liquid Glass (液态玻璃)**: Airy, floating translucency (Meta Muse style); frosted glass blur (15-20dp), specular rim highlights (0.5dp), continuous curvature squircles (20-24dp).
    * **Modernism (现代主义)**: Precision engineering (Bauhaus / Codex App style); brushed titanium / matte aluminum metallic sheen, sharp geometric grid lines (`ruledSurface`), high-contrast typographic hierarchy, compact technical badges.
  - Zero glass blur/backdrop sampling in Modernism mode to eliminate redundant overhead.
  - Both theme suites dynamically adapt across all 30 terminal themes via `ConversationPalette`, strictly enforcing WCAG AA contrast: Body text $\ge 7:1$, secondary/caption text $\ge 4.5:1$.

### Deliverable 7: Two-Stage Dynamic Input Dock (输入框仿 TUI 动态二段式展开)
- **Code Reference**: `ConversationDock.kt:142–213`, `ConversationScreen.kt:387–435`, compared against `CommandInputBar.kt:180–350`.
- **Current Defect**: Currently, `⚡` (shortcuts) and `+` (attachments) are placed side-by-side on the left of the input field in the single main Row, occupying 80dp+ of horizontal space and severely squeezing the text field on mobile screens.
- **Specification**:
  - **Collapsed / Unfocused State (未聚焦收起态)**:
    * Single compact row.
    * Left side shows **ONLY ONE button: `+` (Plus)** for attachments/photos (reusing `DockIcons.kt:DockIconPlus`).
    * Text field occupies the maximum available horizontal width. `⚡` is completely hidden.
  - **Expanded / Focused State (聚焦展开态)**:
    * When the user taps the input field to focus, the dock smoothly expands upwards into 2 rows (or 3 rows for multiline text), replicating the mature ergonomics of `CommandInputBar.kt`.
    * **The `⚡` (shortcuts) button now appears on an upper auxiliary action row** (`Row { ⚡ Shortcuts; Spacer(weight=1); ... }`).
    * The expansion follows a smooth 250ms tween motion (`SessionDockMotion.Standard`), with `⚡` revealing smoothly via alpha/offset transition.
  - **Dismiss / Collapse State**:
    * When the IME is hidden or focus is lost, the dock smoothly collapses back to the single-line row, hiding `⚡` and restoring maximum horizontal width with only the `+` button.

### Deliverable 8: TUI Mode Slash (`/`) Autocomplete in Decoupled Mode (TUI 模式斜杠自动弹出快捷命令)
- **Code Reference**: `CommandInputBar.kt:255–284`, `SessionScreen.kt:292–336` (`mirror` text & `applyShortcut`), `SessionViewModel.kt:655–672` (decoupled/unsynced input handling).
- **Current Defect**: In TUI mode—especially when input sync is disabled (decoupled local input mode)—typing `/` in the bottom input bar does not show any available command suggestions. Users have to remember commands manually.
- **Specification**:
  - Bring the mature slash completion experience from GUI mode back into TUI mode!
  - When in TUI mode (specifically in the local input bar when input sync is disabled), observe `mirror.text`.
  - When the text starts with `/` and is editing a command query, automatically pop up an elegant suggestion overlay above the dock showing available slash commands (unified from system built-ins like `/compact`, `/clear`, `/help` and configured user `ShortcutCommand` items for the current provider).
  - Tapping a suggestion auto-fills the command text into the local input bar and dismisses the overlay, ready for submission.
  - The suggestion overlay floats as a non-modal overlay anchored above `CommandInputBar` without disturbing the terminal canvas (`TermSurfaceView`) layout or line geometry.

---

## 4. Visual Aesthetics & Creative Freedom

You have complete creative autonomy over:
- Card squircle curvature and elevation hierarchy.
- Spring animation physics (stiffness and damping ratios) for tool cards and sheets.
- Typographic scale, line-height rhythm (recommended 1.5x for readability), and code block formatting.
- Dual theme visual expression: ensuring Modernism feels like high-precision titanium architecture while Liquid Glass feels like weightless floating crystal.

---

## 5. Verification & Acceptance Criteria

1. **Gradle Build & Unit Tests**:
   - `:app:compileDebugKotlin` BUILD SUCCESSFUL.
   - `:app:testDebugUnitTest` conversation suite 100% green with `--rerun-tasks`.
2. **Server Go Verification**:
   - `cd server && go test -count=1 ./...` all packages pass.
3. **Emulator / Real Route Acceptance**:
   - Deliverable 1: Skill card compact collapsed state and spring-expanded state.
   - Deliverable 2: Multiline prompt single-bubble atomic submission.
   - Deliverable 3: Smooth bottom scroll landing with zero jitter (both downward button and manual finger swipe).
   - Deliverable 4: Top bar model & thinking effort popover.
   - Deliverable 5: In-flight task warning dialog and successful process swap to authentic interactive TUI.
   - Deliverable 6: Side-by-side verification of Liquid Glass vs Modernism styling across themes.
   - Deliverable 7: Two-stage input dock: collapsed single-line with only `+`, expanding to 2 rows revealing `⚡` upon focus.
   - Deliverable 8: TUI mode typing `/` in decoupled input bar popping up command suggestions and auto-filling on select.
