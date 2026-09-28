# 知识基底 · term-view（系统编译产物）

## 0. 任务（taskbook.yaml#term-view）
- 目标：Canvas 终端渲染视图：60fps 本地滚动、滚动时锁定视口 + "回到底部"按钮语义、捏合调字号→重算行列数→对外发 resize 请求（回调接口，conn 层接线归 session-ui 任务）。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*TermView*"'`
- 写范围：`app/app/src/main/java/**/termview/`、`app/app/src/test/`、`app/app/build.gradle.kts`（仅加 `:terminal` 依赖）。红线：不动 conn/、session/ 包；渲染逻辑与 Android View 分离（可测性）。

## 1. 架构基（内核接口已定，直接消费）
- 依赖 `:terminal`（已交付，61 测全绿）。接口形状（出自攻坚席沉淀，权威）：
  - `TerminalEmulator.feed(bytes)` 增量流；`replaySnapshot(bytes)` 清屏重建（scrollback 保留）；`prependHistory(bytes)`（alt 屏期间忽略）；`snapshot()` + `damageListener`（脏行区间；构造后初始脏区=整屏，**首帧必全绘**）。
  - 擦除走 BCE（空白格带背景色）——渲染空白格也要画背景。
- 结构分两层：`TermViewPresenter`（纯 JVM：视口状态机——跟随底部/锁定历史、可见行窗口计算、捏合字号→行列数换算、脏区合并、"回到底部"可见性）+ `TermSurfaceView`（薄 Android 层：Canvas 画格、手势接入、Choreographer 帧调度）。**单测全部打在 Presenter**（验收 --tests "*TermView*" 即它）。
- 渲染：等宽字体 Paint 测量格宽高；按行画 run（同色连续格合并成一次 drawText，性能关键）；宽字符占两格（内核已算好，渲染只按格宽画）。
- 滚动：手指拖动改视口偏移（本地 ScrollbackBuffer，零网络）；触底自动恢复跟随；新输出到达且处于锁定态时不动视口（006 锁定语义）。
- 捏合：字号变化→重算 rows/cols→回调 `onResizeRequest(rows, cols)`（由上层发协议 resize 帧，005 语义）。

## 2. 现场基
- `:app` Kotlin 2.2.0 / AGP 8.13.0 / compileSdk 36；构建一律 `bash -lc`。
- `:terminal` 是纯 JVM 模块，`:app` 加 `implementation(project(":terminal"))` 即可。
- 沉淀区必读：`.team/nodes/term-core-android/CLAUDE.md` 沉淀区（插件版本坑、转义写法坑）。

## 3. 需求基（指针）
1. requirement-base/entries/006-秒开与本地滚动.md（60fps/锁定视口/回到底部的原始裁定）
2. requirement-base/entries/005-自适应-让CLI自己重画.md（捏合→resize 的语义边界）

## 4. 经验基
- 红测先行：视口锁定态收到新输出不得位移、触底恢复跟随、捏合换算行列数、脏区合并正确性各一条先红。
- 60fps 的可测代理：Presenter 保证"每帧工作量 = 脏行数而非全屏"（断言脏区合并结果），真实帧率归 e2e 实机。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）

### term-view 任务沉淀（2026-08-09）
- **新纪律（leader 立）**：在途代码每次落盘必须保持整模块可编译——宁可先写 stub/TODO 占位，不许留编译破洞过夜（对 :app 内所有席位生效，因共享编译单元会反向阻塞他人验收）。
- **conn 侧历史问题（非我引入，已报）**：kotlinx-serialization 1.9.0 把 JsonDecodingException 移入 internal 包，公开替代是 kotlinx.serialization.SerializationException（conn owner 已改用）；另有 conn 测试 ConnCodecTest.kt:330 引用不存在的 ErrorCode.UNKNOWN 待 conn owner 修。
- **坑：验收命令 `:app:testDebugUnitTest --tests "*TermView*"` 会先编译整个 unit test source set**，conn 测试编译失败也会阻塞 term-view 验收；我的验收依赖 conn 测试源干净。
- **内核初始脏区语义坑**：TerminalEmulator 构造后整屏脏区不主动上抛（damageListener 那时未设），首次 feed/replay 的 flushDamage 才整屏上抛 ⇒ 首帧全绘由"首次 feed 的整屏脏区"承载，Present 不得重复标全屏，否则与 feed 的残留整屏脏区重复成 [0..4]（merge 后仍是全屏，破坏增量断言）。
- **验收结果**：TermViewPresenterTest 10 测全绿（2026-08-09，exit 0）。TermViewPresenter=纯 JVM 视口状态机（跟随/锁定/topLine 冻结、window 钳制、捏合换算、脏区合并、首帧全绘）；TermSurfaceView=薄层（Canvas run 合并画格、拖动/捏合手势、Choreographer 帧调度、回到底部按钮）。conn 层 SerializationException 修复 + ConnCodecTest ErrorCode.UNKNOWN 由 conn owner 落定，双阻塞解除。

