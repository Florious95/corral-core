# 现场基 · rootcause-flicker-v5（leader 手填取证素材）

## 回归现象（用户实测原话，2026-08-12）

v5 APK：**点开输入框重绘、发消息增加一行时界面疯狂闪烁。v2/v4 无此问题。**

## 失败 diff 在哪（不删，是信息不是垃圾）

```
git diff main..v5-failed -- app/app/src/main/java/dev/agentmirror/app/
git show v5-failed:app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt
git show v5-failed:app/app/src/main/java/dev/agentmirror/app/termview/CellSizeStore.kt
```

- `main` = `7c56353` = v2 基线（tag `v2-baseline`），干净、无闪烁（待 base-v2-gate 席位实测确认）
- `v5-failed` = `2874c54` = v5 全部改动封存

## 嫌疑（leader 初读 diff 所得，是线索不是结论——你要自己验）

`TermSurfaceView.kt` 三处改动叠加：

1. **D-28**：`onDraw` 内新增 `canvas.save()` / `clipRect(0,0,width,height)` / `restore()`
2. **D-31**：`presenter` setter 内新增 `CellSizeStore.load(context)?.let { value.onFontSizeChanged(...) }`；
   `onScaleEnd` 内新增 `CellSizeStore.save(...)`
3. **D-38**：新增 `onWindowVisibilityChanged` → `presenter?.onViewportSizeChanged(width, height)` + `postFrame()`

**重点怀疑方向**：`presenter` setter 里同步调 `onFontSizeChanged`，是否经 `onFrameRequested`
反向触发重组，形成「设 presenter → 改字号 → 请求帧 → 重组 → 再设 presenter」的回环。
IME 弹起与终端增行都会改变窗口/视口尺寸，正好是这个回环的触发条件。

**这只是线索。如果证据不支持，就推翻它，别迁就。**

## 与架构基的对应

架构基现算：`termview` 正向依赖 `dev.agentmirror.terminal`（内核，只读契约），
反向依赖 `dev.agentmirror.app.session`（会话页）。
**闪烁发生在 termview→session 这条反向边上**，这正是 v5 未做回归自查的范围。
探针应当守住这条边。

## 硬约束（工程红线，来自本工程 CLAUDE.md）

- **严禁在主仓库切分支或 `git stash`**：主干工作区有大量与本任务无关的未提交改动
  （`.team/`、`CLAUDE.md`、`taskbook.yaml`、`requirement-base/` 等），stash 会把它们一起卷走。
  取 v5 代码一律用隔离工作树：`git worktree add /tmp/v5-tree v5-failed`，用完 `git worktree remove`。
- 只加测试文件（`app/app/src/test/`），**不改一行产品代码**。
- 不 commit、不 push、**不删 `v5-failed` 分支**。

## 验收的本质

探针必须**双向成立**：

- 在 **v5 代码**上命中（失败）→ 证明诊断抓到了真东西
- 在 **v2 基线**上不命中（通过）→ 证明测的是这个回归本身，不是别的

单向通过的探针没有信息量。**若探针在 v5 上没命中，说明诊断是错的——
如实报「诊断未被证实」回头重推，禁止改探针去迁就结论。**
本工程已有实证教训：上届五个修复中三个「QA PASS」却引入回归，就是因为验收标准是单向的。
