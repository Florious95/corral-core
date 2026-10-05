# Comprehensive Mission Directive: End-to-End Native GUI Mode Evolution (Issue #50)

> **Role Assignment**: Claude Opus 5.5 (`claude-opus-5-5`, `provider: claude`)  
> **Mandate**: End-to-End High-Fidelity UI/UX Architecture, Visual Design & Engineering  
> **Target Standard**: Museum-grade AI Native Experience rivaling OpenAI Codex App & Meta Muse  
> **Baseline State**: Branch `feat/issue50-native-gui-session-mode` (HEAD `d67ee4fbb`, PR #53)  
> **Daemon State**: Production Daemon PID 22168 listening on `*:9900` (incorporating `conversation_v1` and socket symlink canonicalization)

---

## 1. Executive Summary & Core Philosophy

You are commissioned with full architectural and creative authority to lead the complete evolution of Corral's Native GUI Conversation Mode.

The core objective is to deliver a **blazingly fast, visually breathtaking, and industrially robust** native AI client experience across mobile devices:
1. **Performance Far Exceeding TUI**: Zero ANSI sweeping, zero viewport reflow lag. Using structured delta streaming and efficient Compose layout, scrolling through vast conversation histories and expanding deep tool calls must achieve smooth 60/120fps motion with zero layout snapping.
2. **Dual Theme Suite Support**: The GUI must natively adapt to both of Corral's design languages: **Liquid Glass (液态玻璃)** and **Modernism (现代主义)**, across all 30 terminal color palettes.
3. **Resilient Session Swapping & Safety**: Seamless switching between Native GUI and genuine interactive Ink TUI within the same tmux pane using Pi's native session resumption (`pi --session <id>`), protected by in-flight task guards.

---

## 2. Preserved Invariants & Architectural Anchors (Strict Rules)

1. **Single Connection Invariant (No Second Socket)**:
   - All conversation frames (`conversation_*`) are multiplexed over the existing persistent `/ws` connection via the `ConversationTransport` decorator.
   - Inherits `ConnectionManager` resilience (15s WebSocket Ping keepalive, foreground resume self-healing, multi-path Tailscale `100.x.y.z` / LAN routing).
   - Never open a secondary WebSocket (e.g. `/gui`), which previously caused 404 handshake crashes.
2. **State Retention Guard (`guiReadyOnce`)**:
   - Once a session enters `gui_ready`, any transient disconnect must remain in the GUI error/reconnect state.
   - Never trigger an unprompted cold switch to TUI during user touches or disconnects, which previously blocked the main thread on `TermSurfaceView` glyph drawing and triggered a 21,209ms ANR.
3. **Socket Canonicalization Invariant**:
   - Private IPC keys must canonicalize socket paths (`filepath.EvalSymlinks`) to prevent macOS `/tmp` vs `/private/tmp` alias hash splitting. Public session refs remain intact.
4. **Classic TUI Zero Regression**:
   - When viewing in TUI mode or on sessions without structured capabilities, the classic terminal canvas (`TermSurfaceView`) and fixed dock remain 100% untouched and functional.

---

## 3. The 6 Core Deliverables (Detailed Specifications)

### Deliverable 1: Collapsible Skill Invocation Cards (Skill 折叠卡片化)
- **Current Defect**: Invoking a skill via `/skill <name>` or injecting a skill prompt dumps a giant block of raw text directly into the conversation stream, overwhelming the viewport.
- **Specification**:
  - Automatically identify or encapsulate skill invocations into a compact, elegant `SkillCard`.
  - **Collapsed State (Default)**: Displays an expressive icon (e.g. Sparkle / Wrench), skill name badge, concise summary, and line-count tag.
  - **Expanded State**: Spring-animated expansion revealing the formatted prompt content, with a quick "Copy" action and a collapse toggle.

### Deliverable 2: Atomic Multiline Prompt Submission (多行输入原子发送)
- **Current Defect**: Pasting or typing multiline text with newlines (`\n`) splits the input into multiple discrete messages upon sending. The agent receives and processes each line sequentially, breaking structured instructions and code snippets.
- **Specification**:
  - All newlines (`\n`) inside the input field must be preserved as formatting whitespace.
  - Submitting input must transmit the entire text atomically as a **single user prompt payload / single turn** to the agent loop.
  - The resulting user message bubble renders the multi-line layout intact.

### Deliverable 3: Bottom Scroll Jitter & Flash Elimination (滑到底部/按钮回底防抖防闪)
- **Current Defect**:
  1. Tapping the downward floating action button in the bottom right jumps to the latest message, but upon landing, the viewport stutters/jitters and flashes.
  2. **Finger Swiping**: Swiping/scrolling down with fingers to the very bottom exhibits the exact same sudden jump/jitter at the bottom edge.
- **Specification**:
  - Audit the reverse-layout anchoring, overscroll physics, and `LazyListState` measurement at bottom offset `0`.
  - Eliminate layout re-measuring glitches, snapping artifacts, and redraw flashing. Both button-initiated scrolling and manual finger swiping must come to a buttery smooth, solid halt at the bottom.

### Deliverable 4: Top Floating Bar Model & Thinking Effort Switcher (顶部切模型与思维强度)
- **Specification**:
  - Tapping the top floating navigation bar triggers a frosted glass popover / sheet.
  - **Model Selector**: Displays available models (e.g. Luna `gpt-6-luna`, Sol `gpt-6.1-sol`, Sonnet, etc.) with clean selection chips.
  - **Thinking / Effort Selector**: Provides an intuitive segmented control for thinking intensity (Low, Medium, High, Max).
  - Tapping an option immediately updates the session's model/effort configuration via structured protocol frames.

### Deliverable 5: In-Pane TUI <-> GUI Session Process Swap with In-Flight Guard (双向无损进程置换与安全拦截)
- **Specification**:
  - **Seamless Process Swap**: Utilizing Pi's native session persistence (`--session <id>`), allow switching between:
    * **GUI Mode**: Running `pi --mode rpc --session <id>` inside the tmux pane, driving the native Compose conversation stream.
    * **TUI Mode**: Running interactive `pi --session <id>` inside the same tmux pane, restoring the authentic Ink terminal with live cursor, colors, and key navigation.
  - **In-Flight Task Guard (运行中告警拦截)**:
    * If the agent is currently generating tokens (`isGenerating`) or executing a tool call, attempting to switch MUST trigger a clear confirmation dialog:
      `"当前任务正在运行中，切换模式将中断并丢弃当前未完成任务，是否确认切换？"`
    * "取消" (Cancel) keeps the session in its current mode without disruption.
    * "确认" (Confirm) aborts the in-flight turn and performs the process swap.

### Deliverable 6: Dual Theme Suite Adaptation: Liquid Glass vs Modernism (双主题体系全量适配)
- **Specification**:
  - **Liquid Glass (液态玻璃)**:
    * Airy, floating translucency inspired by Meta Muse.
    * Frosted glass blur (15-20dp), subtle specular rim highlights (0.5dp), continuous curvature squircles (20-24dp).
  - **Modernism (现代主义)**:
    * Precision engineering inspired by Bauhaus / Swiss design and OpenAI Codex App.
    * Subtle brushed titanium / matte aluminum metallic sheen, sharp geometric grid lines (0.5dp architectural borders), high-contrast typographic hierarchy, compact technical badges.
  - **Chromatic Contrast Invariant**:
    * Both theme suites must dynamically compute color tokens via `ConversationPalette` across all 30 terminal themes.
    * Strictly enforce WCAG AA contrast: Body text $\ge 7:1$, secondary/caption text $\ge 4.5:1$ (eliminating white-on-white text washout and dark-mode murky grays).

---

## 4. Visual Aesthetics & Creative Freedom

You have complete creative autonomy over:
- Card squircle curvature and elevation hierarchy.
- Spring animation physics (stiffness and damping ratios) for tool cards and sheets.
- Typographic scale, line-height rhythm (recommended 1.5x for readability), and code block formatting.
- Floating capsule input dock styling, ensuring physical terminal key rows (Esc/Tab) remain cleanly hidden in GUI mode.

---

## 5. Verification & Acceptance Criteria

1. **Gradle Build & Unit Tests**:
   - `:app:compileDebugKotlin` BUILD SUCCESSFUL.
   - `:app:testDebugUnitTest` conversation suite 100% green with `--rerun-tasks`.
2. **Server Go Verification**:
   - `cd server && go test -count=1 ./...` all packages pass.
3. **Emulator / Real Route Acceptance**:
   - Skill card collapsed/expanded state.
   - Multiline prompt single-bubble submission.
   - Smooth bottom scroll landing with zero jitter (both button and manual swipe).
   - Top bar model & effort popover.
   - In-flight switch confirmation dialog and successful process swap to authentic TUI.
   - Side-by-side verification of Liquid Glass vs Modernism styling.
