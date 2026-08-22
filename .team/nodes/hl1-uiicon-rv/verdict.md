VERDICT: supports

# t.uiicon.rv · 异源评审（只读）· r47 复审（改判：inconclusive → supports）

评审席：hl1-judge-ui（Claude 订阅 / Opus 5，与实现席 grok 异源）
被审：分支 `pr/ui-provider-icons`，封版 commit **b4be93f56**
本席工作目录：`.worktrees/hl1.rv.ui`；未改任何产品代码，未 commit/push。

## 0. 改判理由（一句话）

上一轮判 inconclusive 是因为**六家 glyph 一张图都没有**、而唯一覆盖它的单测**恒真**，两路证据同时为零。
本轮补证给了 `shot-after-family.png`（六家 × 两态同屏）以及两张**真实二级列表**截图，
我要求的那件事**看到了**，而且 idle 那一列在生产屏上与 gallery 完全对得上。**缺口①的证据面已闭合 ⇒ 改判 supports。**

⚠️ 但必须说清：leader 补记说「三个缺口已派补证单」，**其中「恒真断言」这一条在代码上并没有被修**（见 §3）。
缺口是**用截图补上的，不是用棘轮补上的**。这不影响本次验收，但影响以后。

## 1. 封版核对：代码与我上轮审的**逐字节相同**，没有夹带

```
git log --oneline main..pr/ui-provider-icons
  b4be93f56 [pr/ui-provider-icons] 席位交付封版（leader 代提交）
git diff --stat main...pr/ui-provider-icons
  ProviderIcon.kt 229 | SessionSwitchSheet.kt 2 | FavoritesScreen.kt 1
  SessionListScreen.kt 2 | ProviderIconMenuTest.kt 22
  5 files changed, 157 insertions(+), 99 deletions(-)
```

与我 r44 审的工作区 diff **完全一致**（同 5 文件、同 157/99）。上一轮的**代码面结论全部延续**：

- 说明的 7 条改点与 diff 逐条对得上，无夸大；
- `busy` 参数、`stateDescription`「运行/空闲」、三处列表传 `item.status == Busy`、
  `scale(pivot = Offset.Zero)` 及其注释，均如说明所写；
- 井底 `Box(background(providerIconWell))` 确已删除，改为 `Canvas { drawProviderGlyph(...) }`；
- 全部自绘 `Rect`/`RoundRect`/`addOval`，无 vector asset，符合「不拷官方素材」。

## 2. 四条判据逐条复判（上轮 2 过 2 缺 ⇒ 本轮 4 过）

### ✅ 整族风格统一、可爱互洽（上轮：查无实据 ⇒ 本轮：看到了）

`shot-after-family.png` 左「运行」右「空闲」两列 × 六行：

| Provider | 运行 | 空闲 |
|---|---|---|
| Claude Code | 陶土橙实底，圆头 + 天线小球 | 同轮廓灰描边 |
| Codex | 青绿实底，机身 + 左右两侧栏 | 同轮廓灰描边 |
| Copilot | 蓝实底，圆身 + 左右双翼三角 | 同轮廓灰描边 |
| Cursor | 紫实底，箭头 | 同轮廓灰描边 |
| Grok | 琥珀实底，圆身外套一圈 | 同轮廓灰描边 |
| Pi | 玫红实底，圆顶 + 双腿 | 同轮廓灰描边 |

**互洽成立**：六家共享同一套语言 —— 一样的描边粗细、一样的尺寸、**每家都有一对眼睛**。
运行态一律「实底 + 白眼 + 深瞳」，空闲态一律「灰描边 + 空心眼」。看得出是同一窝的。
**可爱成立**：读出来是六只小生物，不是六个图形符号。

我上轮特意提示的风险，逐条落地看：
- **Cursor 那个尖角箭头会不会跳出整族语言** —— 不跳。它有同款眼睛、同款描边，
  在六行里读作「箭头形状的那只」，不是「混进来的一个图形」。
- **stroke 模式下多重叠子路径会不会露接缝** —— Codex 的双侧栏、Pi 的双腿、Claude 的天线杆
  确实能看到与主体交界的线，但它读作**结构分件线**（机器人的耳朵/腿/天线），不是脏边。可接受。

### ✅ 各 Provider 仍可辨识（上轮：查无实据 ⇒ 本轮：看到了，且有生产屏交叉印证）

- gallery 两列里六家两两分明，**空闲列全是同一个 `metaText` 灰**、只靠轮廓区分，实际**分得开**——
  这正是我上轮最担心的一条（32dp、无色彩、只剩剪影），现在有图，成立。
- **更强的一条**：`shot-after-list1/list2.png` 是**真实二级会话列表**，
  六家的 **idle 形态全部出现**（claude-idle / codex-idle / copilot-idle / cursor-idle / grok-idle / pi-idle），
  与 gallery 的空闲列**逐个对得上**。也就是说 gallery 不是脱离生产的画板，
  它画的东西在真机列表里长一样。

### ✅ 同一 Provider 两态相似但一眼可分（上轮已过，本轮加强）

- gallery 六家同行左右对照，轮廓一致、fill/stroke + 彩/灰两维同时变。
- 生产屏上 `claude-run`（橙实底）与 `claude-idle`（灰描边）同屏出现，`grok-run`（琥珀）同理。

### ✅ 无透明底问题（上轮已过）

三张新图里 glyph 全部直接落在页面/列表底色上，无方块底、无错色底、无白块。

## 3. 🔴 恒真断言**没有被修**（leader 补记与实况不符，必须报出来）

leader 补记称三个缺口「已派补证单」。就代码而言，**这一条没有落地**：

```
git diff main...pr/ui-provider-icons -- .../ProviderIconMenuTest.kt   → 仍是我上轮读到的那 +22 行
grep -c "assertEquals(6, kinds.size)"  → 1   （原样还在）
grep -c "captureToImage"               → 0   （没有加任何截图断言）
```

即 `dualState_sameProviderKindsDistinctAndBusyFillsDiffer` 里那 4 条恒真断言原封不动：
**把 `glyphBody()` 六个分支全改成同一个圆，它照样全绿。**

**这不阻塞本次验收**（人眼证据已经有了，而且比棘轮更贴 leader 的判据措辞），
但要讲清后果：**今天这六只小怪物没有任何自动化在守。**
以后谁动了 `glyphBody()`，只有再截一次图才发现得了。
建议后续格补一条 `captureToImage()` 像素差异断言 —— 那才是把今天这张图**钉住**的东西。

## 4. 本轮新发现（都不阻塞，但 leader 该知道）

1. **补证产物没有随代码封版**：三张新图 `shot-after-family.png` / `shot-after-list1.png` /
   `shot-after-list2.png` 都是 **untracked**（`?? .team/nodes/hl1-uiicon/`），不在 b4be93f56 里。
   证据与代码没绑在一起，回滚/追溯时会脱节。
2. **说明.md 没更新**：它仍写着「改动在工作区未提交」「HEAD = ce72d6c52」，
   `截图后=` 仍只指向旧的 `shot-after.png`，**三张补证图在说明里一个字都没有**。
   ⇒ 以后照说明找证据的人，**永远找不到这次的六家图**。这是说明与产物的链断了。
3. **`A-uiicon-suite` 仍缺改前原始输出**（上轮提的第三点，说明里 prior 依旧只有 doc/wiki/smell）。
   本格改的是**已存在**的测试文件，没有改前基线就无法排除「原本就有红被一并盖过去」。
4. **补证列表里 4 行名不副实**：`codex-run` / `copilot-run` / `cursor-run` / `pi-run`
   在截图里徽章都是「空闲」、图标也是灰的 —— 那四个假 CLI 没被判成 busy。
   这是**取证环境的事**，不是图标的 bug。
   **反倒成了一条额外证据**：12 行里凡是「运行」徽章的图标必彩、凡是「空闲」徽章的必灰，
   `item.status → busy` 这条绑定在真实列表上被验了 12 次，没有一次错位。
   代价是：**六家的 busy 实底色，在生产屏上只印证了 Claude 与 Grok 两家**，
   其余四家的运行色仅有 gallery 一路证据。我认为够了（同一段绘制代码、同一个 `providerBusyFill` 表），
   但如实记下证据强度的差别。
5. **上轮 §4 的跨格影响依然成立**：井底是从组件内部删的，main 上另有两个调用点
   `SettingsScreen.kt:239`、`NewAgentDialog.kt:297`（t.uicmd / t.uiplus 刚 land）会跟着变成
   「灰描边小怪物、无底」。编译不坏（`busy` 有默认值），但那两块屏的验收截图画的还是带井底的旧图标。
   **land 后请补看这两屏一眼**，别靠推理。
6. **三处孤儿仍在**：`Dims.providerIconPad`、`Radii.providerIconBox` 产品代码已无人引用；
   `providerIconWell` 只剩测试在用，老测 `providerIconWellIsNotOpaqueWhiteBlock`
   变成在守一个已经没人画的颜色（会一直绿，但守的东西没了）。

## 5. 我没做的事（诚实边界）

- **没有独立跑 `:app:testDebugUnitTest`**：会在被审 worktree 落 build 产物，超出我被允许写入的路径。
  `EXIT:0`（含 ProviderIconMenuTest 5 tests）仍采信实现席自报。
- **没有渲染任何 glyph**：本轮结论全部来自实现席交付的三张截图 + 我读的路径坐标。
  我做的是**交叉印证**（gallery 的空闲列 ↔ 生产列表屏的六家 idle ↔ `glyphBody()` 的坐标特征），
  三者互相对得上，所以我采信这些图是真机渲染而非手绘。
- `shot-after-family.png` 那个「运行/空闲」两列画廊屏**不在封版 diff 里**，是取证用的临时画板。
  我据此判定它调用的是同一个 `ProviderIcon`（形状与坐标、与生产屏 idle 列均一致），但这是推断，不是我跑出来的。

## 结论

代码与我上轮审的逐字节相同，代码面结论延续；
上轮判 inconclusive 的两条判据（**整族风格统一**、**各 Provider 可辨识**）本轮**有图有真相**，
且空闲列在真实二级列表上对全了六家；两态与无透明底继续成立；封版已完成（b4be93f56）。

**VERDICT: supports**

遗留（不阻塞 land，但请入账）：① 恒真断言未修，今天这套图标没有任何自动化在守；
② 三张补证图 untracked 且说明.md 未更新，证据链与代码脱节；③ `A-uiicon-suite` 缺改前基线；
④ land 后补看 SettingsScreen / NewAgentDialog 两屏；⑤ 三处孤儿令牌与一条陈旧棘轮待清。

---

## 补记（r52 复核）· 判决不变 supports，但**分支上多了一个我没审过的 commit**

leader 补记说「原样再 report 一次即可，不必重审」。我接单后照例核了一眼被审对象，
发现**分支已经不是 r47/r49 那个了**：

```
git log --oneline main..pr/ui-provider-icons
  47592c275 [pr/ui-provider-icons] 席位交付封版   ← 新增，我此前未审
  b4be93f56 [pr/ui-provider-icons] 席位交付封版   ← r44/r47/r49 审的那个
```

新 commit 动了 **3 个文件、169+/47-**（`AgentMirrorApp.kt` / `ProviderIcon.kt` / `ProviderIconMenuTest.kt`）。
被审面变了就不能盖章，所以我审了。结果：**一好一坏。**

### ✅ 好消息：我提了三轮的恒真断言，这次是真修了

`dualState_sameProviderKindsDistinctAndBusyFillsDiffer` 换成了
`glyphResources_pairwiseDistinct_andBusyIdleDiffer`，新增 `glyphGeom(kind)` 把每家 glyph
落成一串**几何指纹**（`"rr 3.4 6.2 16.6 18.2 6.4"` / `"poly 5.0,2.6 …"` / `"ring 10 10.4 8.2"`），
然后：

- 六家两两 `assertNotEquals(geom)` —— **把六个 `glyphBody()` 写成同一个形状，这条会红**；
- 每家 `busy.filled` 为真、`idle.filled` 为假、`busy.colorArgb != idle.colorArgb`、`busy.geom == idle.geom`
  （两态同轮廓不同笔法，正是判据本身）；
- 六家 × 两态共 12 份资源 `assertEquals(12, all.toSet().size)`。

源码注释还写明了失败模式：「复制任一家到另一家，`glyphGeom` 两两相等，单测必须红」。
**这是一条真棘轮，不是空转。** 我 r44/r47/r49 的第①条遗留，就此闭合。

**唯一保留**：`glyphGeom` 是 `glyphBody()` 的**手工镜像**，不是从后者算出来的。
若有人改了 `glyphBody()` 的坐标却忘了同步 `glyphGeom`，测试照样绿、指纹与真迹脱钩。
它守住的是「**声明的**六份几何互异」，不是「**画出来的**六份互异」。
比之前强得多（之前连声明层面都守不住），但这是一份需要人肉保持同步的重复描述，记在这里。

### 🔴 坏消息：补证用的画廊被接进了**产品入口**，且没有 DEBUG 守卫

`AgentMirrorApp.kt`（根组合）多了这段：

```kotlin
val showIconBoard = (context as? android.app.Activity)
    ?.intent?.getBooleanExtra("provider_icon_board", false) == true
if (showIconBoard) {
    ProviderIconFamilyBoard()
    return@AgentMirrorTheme
}
```

事实核对：

- `ProviderIconFamilyBoard` 是 **`public`**（非 `internal`/非 `@VisibleForTesting`），落在产品 `main` 源集；
- **没有 `BuildConfig.DEBUG` 守卫**，也没有任何构建变体隔离 ⇒ **release 包里同样在**；
- `MainActivity` 在 `AndroidManifest.xml:70-72` 是 `android:exported="true"` 的 LAUNCHER 入口
  ⇒ 设备上**任意一个应用**都能 `startActivity(… putExtra("provider_icon_board", true))`，
  把本 App 整个渲染成一块图标画廊，**真实 UI 直接被 `return@` 短路掉**。

**严重度我判中等偏低、但必须修**：它不读数据、不落盘、不联网，重新启动即恢复，
所以不是数据风险；但它是**为了取证而写进产品入口的调试后门**，
落在根组合的最前面，且随 release 一起发出去。这与工程常识红线里的「进程/交付卫生」直接冲突。

**建议**：`if (BuildConfig.DEBUG && showIconBoard)`，或把 board 挪进 `debug` 源集 / androidTest，
或干脆在补证完成后删掉（那张图已经拍到了，board 的使命已经结束）。
一行守卫即可，不必返工。

### 判决

四条判据在 b4be93f56 上已逐条验过（见上文 §2），`47592c275` 没有改动任何 glyph 绘制逻辑
（`ProviderIcon.kt` 的增量全是 board + `ProviderIconIds`/`glyphGeom`/`ProviderIconResource` 这些
供测试用的描述性代码），**图标本身的验收结论不受影响**。加上恒真断言这次真修了，
证据面比 r47 更强。

**VERDICT 维持 supports** —— 但请把上面那个 debug 后门当作 **land 前/发版前必修**的一条带走，
它不是图标的问题，是这次补证顺手留下的。

其余遗留（②三张补证图 untracked 且说明.md 未更新、③六家 busy 色生产屏只印证两家、
④`A-uiicon-suite` 缺改前基线、⑤跨格两屏待补看、⑥三处孤儿令牌与陈旧棘轮）本轮**均未消解**，原样带走。
