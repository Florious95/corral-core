# HANDOFF · leader · 2026-08-14（第二版，覆盖前一版）

> 前一版写于 08-14 凌晨 02:00 左右，内容是「链路优化收工、三个 App 缺陷待开工」。
> 本版覆盖它：**缺陷已变成五条**，团队已从 7 席扩到 12 席（其中 5 席已退役），
> 今晚（08-13 17:00 起）共 31 个提交。

---

## §0 compact 后先做什么

### 一句话现状

**五条 App 缺陷在并行推进，四条已有代码或方案落地，只剩一条真正等用户（缺陷① 要他在真机传一张图）。**
新增第六条任务「诊断日志 + 导出」，是用户裁定的通用出路，目前是关键路径。

### 开口第一句（对用户说）

> 「APK 在 `e2e/artifacts/apk-for-user/agentmirror-fix-upload-fb31674d2.apk`，
> 装上在**蜂窝 + Tailscale** 下传一张图就行 —— 不用跑「改前」，你那张 `after 10000ms` 的截图就是改前证据。
> 另外今晚查出一件影响整个工程的事：**这台模拟器结构上做不了任何 tailnet 验证**
> （Mac 在 tailnet 上，模拟器经 host NAT 直达 100.x，根本不需要隧道；
> 实证是没有修复的旧包上传 <1 秒就成功了）。历史上所有「模拟器验过 tailnet」的结论都要重新审视。」

### 必读清单（按优先级，全绝对路径）

1. 本文件
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— **今晚新增了一条凭据铁律，见 §6**
3. `/Volumes/nvme/Projects/远程Agent安卓/taskbook.yaml` 末尾三条新任务：
   `feat-remote-scroll-forward`、`fix-tsnet-resume-reconnect`、`feat-diagnostic-log-export`
4. `docs/upload-transport-vpn-bypass-probe.md` —— **顶部有加粗的模拟器局限发现**
5. `docs/d38-rootcause-probe.md` —— 缺陷③ 三次修不好的真正原因
6. `docs/cols-convergence-patch-triage.md` —— 缺陷② 的废补丁分类 + 最小修复面
7. `docs/remote-scroll-forward-design.md` —— 缺陷④ 协议方案 + tmux 实测
8. `docs/tsnet-resume-reconnect-rootcause.md` —— 缺陷⑤ 根因
9. `docs/diag-log-review.md` —— 既有代码里的四处凭据泄露路径
10. `docs/upload-transport-diff-review.md` —— 缺陷① 的 diff 审查

### 恢复动作

```bash
# 1. leader 绑定（pane 变了必须重认领，否则 add-agent/send 被 owner gate 拒）
team-agent claim-leader --confirm      # 当前 owner_epoch = 7

# 2. 生产 daemon —— pid 70317，监听 *:9900。核：lsof -nP -iTCP:9900 -sTCP:LISTEN
# 3. 广州 DERP —— 43.136.53.247，SSH 端口 52222（skill 里写的 22 是错的）
#    密钥 /Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem
# 4. 看门狗 —— 今晚修好了自匹配 bug（见 §1），按 cwd 核活：
#    for p in $(pgrep -f "supervisor\.sh|watchdog\.py"); do lsof -a -p $p -d cwd -Fn | grep ^n; done
# 5. 模拟器 —— emulator 包与 android-35 arm64 镜像今晚是我手工装的（sdkmanager 卡死，见 §1）
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools
```

---

## §1 身份与不变量

### 角色边界

leader **编排**，不亲手做：不跑测试、不改产品代码、不 push。
例外（今晚实际做过且认为正确）：**客观核对**（`git cat-file` 核 sha、亲跑一次 `go test` 验席位自报）、
**环境 ops**（装 SDK 包、起模拟器、修看门狗）、**任务书与证据的记账**。

### 客观核对，不凭自报（今晚的实证）

- `w-c1-dev` 报了 4 个 sha，我 `git cat-file -t` 逐个核过才认。
- `w-scroll-design` 报「9 包全绿」，我**自己跑了一遍** `go test ./...` 才提交。
- `w-cols-dev` 报「编译错误来自别人」，我**自己跑了** `compileDebugKotlin` 确认已通才放行。
- **席位报「已完成」时，先看它有没有贴实际输出。** 只给结论不给输出的，退回去要输出。

### 今晚新立的四条判据（比任何具体缺陷都重要）

1. **测试链路必须先抓到真实缺陷，抓不到就不许改代码**（用户 08-14 原话）。
   审计后发现：①③ 合格，**②⑤ 当时不合格**（断言的是结构事实/构造极端值，不是用户看到的现象），
   已分别退回补真复现，②的补上了（超出 5px ≈ 半字宽 5.5px），⑤ 仍在补。
2. **抓不到就加日志**（用户 08-14 原话）。→ 立 `feat-diagnostic-log-export`。
3. **审查结论也是要被验证的对象。** `w-rev-upload` 说「`OkHttpClient` 该调 `close()`」，
   我据此下令；`w-up-dev` 用 `javap` 证伪 —— 本仓库是 okhttp **4.12.0 JVM 版，根本没有 `close()`**。
   **不因为它来自审查席就免检，也不因为是 leader 下的令就闭眼执行。**
4. **探针跑得再漂亮，没执行到被测代码就不算验过**（纪律⑥）。
   今晚拦掉两次：Go 版 upload 探针（被测对象是 Kotlin）、
   `w-up-probe` 打算用 LAN 路径验 tailnet 修复（LAN 下改前改后同一条路，不可能命中）。

### 环境已知坑（会让人反复误判，务必先读）

1. **⚠️ 这台模拟器做不了任何 tailnet 验证。**
   Mac 在 tailnet 上，模拟器经 host NAT 直达 `100.x`。实证：无修复的旧包上传 **<1s 成功**。
   → 凡「tailnet 路径」的验收，**只能真机**，或走诊断日志。
2. **sdkmanager 会静默卡死，不是网络问题。**
   实测：`curl` 同一 CDN 大文件 **1.25 MB/s**，而 sdkmanager 跑 3 分 21 秒 **SDK 目录增长 0 KB**。
   老版 cmdline-tools（自报只认 SDK XML v3、实际遇到 v4）。
   → 绕法：直接 `curl` 拉包再解压。emulator 包与 `system-images/android-35/google_apis/arm64-v8a`
   今晚都是这么装的（原本 `emulator/` 是空目录、`system-images/` 根本不存在）。
3. **看门狗的单实例守卫会自匹配。**
   `pgrep -f "watchdog-supervisor.sh"` 会命中**命令行里提到该脚本名的任何进程**
   （包括拉起它的那条命令本身），cwd 又相同 ⇒ 守卫判「已在跑」直接 exit 0。
   **它从 08-11 起实际是死的，每次拉起都静默失败。** 今晚加了 `ps -o comm=` 只认 bash，已修并重启。
4. **共享工作区里，任何人一次编译不过就是全队停摆。** 今晚发生两次。
   → 已让 `w-diag-dev` 改用独立 worktree（`/tmp/am-diag`）。
   **在主工作区绝不许 `git stash` / `git checkout` / `git merge`** —— 会带走别人的未提交改动。
   要「改前」基线一律 `git worktree add /tmp/xxx <sha>`。
5. **Claude Code shell 偶发 `claude native binary not installed`**（输出到终端时）。
   → 重定向到文件再 Read 就正常。

---

## §2 排期与封存令

用户 2026-08-14 裁定（原文）：
> 「核心就是把这**四个**缺陷开发好。」（后又追加第五条）
> 「持续推进，不要停下来。」
> 「测试链路一定要先抓到真实的缺陷，抓不到就不能改代码。」
> 「你这些东西抓不到，你就加日志。」

**授权范围**：用户已明确授权 leader 代拍契约级议题（据此批准了缺陷④ 的实现）。
**不要再为已裁定过的事回去问他。**

---

## §3 五条缺陷 + 一条能力任务的逐条状态

| # | 缺陷 | 状态 | 卡在哪 |
|---|---|---|---|
| ① | 图片上传失败 | **代码已提交**（`fb31674d2` + `807c122f9`） | **等用户真机传一张图** |
| ② | 捏合后右列跑屏幕外 | 实现落地，5 条判别红测 4 绿 1 红 | 等 `w-cols-dev` 补字形侧护栏 |
| ③ | 重进 CLI 输入框跑中间 | 回炉步骤 2/3 完成，探针 5/5 命中 | **施工未开**，等 ② 让开 |
| ④ | 上滑投送到远端 | **服务端已提交**（`1511b50c7`，16 条红证过的测试） | App 侧手势接入等施工权 |
| ⑤ | 内嵌 tsnet 回前台连不上 | 根因锁死 + 探针 3/3 | **复现未成**，在设备上验，可能撞模拟器那堵墙 |
| ⑥ | 诊断日志 + 导出 | 三席在做，前置四修进行中 | 关键路径 |

### ① 图片上传（`fix-upload-transport-tsnet`）

- **根因**：`HttpUrlConnectionUploader.kt` 用 `URL(endpoint).openConnection()` 裸连，
  而 WS 走 `TsnetDial.socketFactoryFor` 的 SOCKS。**两条通道不是同一条路。**
- **改法**：tsnet Up 且目标是 tailnet host 时复用 `TsnetProxySocketFactory`，其余保持直连。
  照抄 `OkHttpWebSocketTransport.kt:161` 的同款选路。
- **最硬的证据**：测试席在未修复 HEAD 上拿到
  `AssertionError: 必须经 SOCKS 代理收到 CONNECT，实际=[]` **耗时 10.006s**
  —— 对上用户真机的 `after 10000ms`，**逐字复现**。修复版 5/5 绿，
  `DEBUG-SOCKS-CONNECT host=100.101.2.3` 实证 CONNECT 来自真实 uploader。
- **审查**：4 条发现无阻塞。F1 已修（`807c122f9`，`shutdown + evictAll()`，
  **不是** `close()` —— 见 §1 判据 3）。
- **待办**：用户真机验收。包已备在
  `e2e/artifacts/apk-for-user/agentmirror-fix-upload-fb31674d2.apk`（gitignore 内）。

### ② 捏合右列（`fix-cols-grid-convergence`）

- **根因**：`presenter.cellWidth` 恒为名义值 10（`measureCells()` 从不回写），绘制按实测 ≈11 步进。
- **真复现（用户真机参数）**：`viewportW=1260 名义10 实测11 → reportedCols=126 而 canvasCapacity=114`，
  末列字形右缘 1265 越过画布 1260，**超出 5px 而半字宽 5.5px** —— 正是「只能看到一半」。
- **修法**：X2（`measureCells` 回写 `setMeasuredCellWidth`，幂等）+ X1（`roundToInt→floor`）
  + X3（护栏，带**金丝雀计数** `clipGuardEngageCount()`）。
- **X3 的金丝雀语义（务必保住）**：**它一旦在正常路径 engage，就说明 X2 失效了。**
  计数恒 0 = 主修复在干活；计数涨 = 有路径绕过回写，是要查的 bug，**不是「护栏立功」**。
- **两条测试的分工（别搞混）**：
  `USER-REAL`（1260/10/11）= **正常路径真复现**，护栏不该 engage；
  `B-字形`（120 列画在 100px）= **异常路径护栏测试**，就该是构造值。
- **收工判据**：判别红测 5 全绿（`USER-REAL` 期望值必须是 **114**）+ 写回约束 5 全绿
  + 既有 63 条不掉 + BgCjk 对基线逐行对账 + `strict-t3` exit 0 + `USER-REAL` 上金丝雀 = 0。
- **⛔ 不许 `git apply docs/reverted-to-v6/horizontal-grid-convergence.patch`**：
  `--check` 零冲突是**陷阱**（基线与 HEAD 逐字节一致），3/4 内容是用户已否掉的 D-38/D-36/捏合预览。

### ③ 输入框跑中间（`fix-viewport-restore-d38`）

- **回炉步骤 2/3 已完成**：探针 5/5 在回退态命中（`tests=5 failures=0`）。
- **三次修不好的真正原因**：那份「已闭合根因」写的是 `onRealViewportChanged` 的行为，
  而**探针 P5 用反射实证 v6 里根本没有这个方法** —— 它描述的是 v3 补丁的行为。
  **按一份描述着不存在代码的诊断去修，三次都修不到点上是必然的。** 纪律①第三个实例。
- **v6 真根因**：`viewportSeeded=true` 之后没有任何路径能调 `recomputeGeometry()` 更新
  `emulator.rows`；`visibleRows = 140.coerceIn(1,84) = 84` → 56 行空黑，与用户 1123px 吻合。
- **线索**：v5 曾用 `onWindowVisibilityChanged` 补过这个缺口，该文件被列为禁区、v6 回退时未捞回。
- **验收线**：修复后 P1/P2/P3/P5 转 **FAIL**、P4 保持 **PASS**。
- **待办**：三席并行施工（审查席 `w-d38-probe` 在岗待命，测试席与开发席未开）。

### ④ 上滑投送远端（`feat-remote-scroll-forward`）

- **用户重新定义推翻了前四轮全部工作**：他要的是把手势送到**远端终端**，
  等价于在那个 TUI 里滚滚轮 —— Claude Code / vim / less 自己处理滚轮，滚本地缓冲永远看不到它们的上文。
- **服务端已提交**（`1511b50c7`）：`TypeScrollWheel`(C→S) + `TypePaneModeChanged`(S→C)，协议只增不改。
  `bridge.InjectScroll` 用**单次** `tmux if-shell -F '#{mouse_any_flag}'` 把判定与执行合进一次派发
  —— 消的是真实竞态（查完 flag 到发字节之间用户可能刚 `q` 退出 vim，鼠标字节会打进裸 shell 命令行）。
- **实测（隔离 tmux 3.6a）**：`mouse_any_flag` 裸 shell=0 / vim=1；
  `send -M` 在服务端侧返回 `no mouse target`（**此路封死**）；`send-keys -H` 可注入；
  `copy-mode -e` 降级可用；`send-keys -X cancel` 让 `pane_in_mode` 1→0。
- **leader 否决过席位一条推荐**：它说「copy-mode 提示初版不做」，否掉 ——
  裸 shell 上滑推进 copy-mode 后**用户打字会被 copy-mode 吃掉**，看到的是「敲了没反应」。
  现在有两层保护：通知帧 + `handleInput` 转发文本前自动 `cancel`。
- **16 条测试全部做过红证**（在无实现的 sha 上逐条编译红，无一条假绿）。
- **已知局限（写进设计文档）**：SGR 坐标硬编码 `1;1`，多面板 TUI（nvim 分屏）可能路由错，
  需 App 上报手势坐标才能修；`sendError` 正常路径不打服务端日志。
- **待办**：App 侧手势接入，等 `app/app` 施工权。

### ⑤ tsnet 回前台连不上（`fix-tsnet-resume-reconnect`）

- **用户 A/B 差分（决定性）**：内嵌 tsnet + token 配对 → 切后台 → 回前台 → **永远连不上**；
  官方 Tailscale App + tailnet 地址直连 → 杀到后台再开 → **立刻连上**。
- **根因锁在一行**：`TsnetWire.kt:91`
  `if (m != null && key == currentKey && (m.state is Starting || m.state is Up)) { return }`
  `state == Up` 的语义是「`start()` 成功过、SOCKS 端口**曾经**通」，不是「现在能拨通」。
  后台冻结 → DERP TCP 超时断裂 → native 不回调 Java 层 → state 永停 `Up` →
  `socketFactoryFor` 照常返回 SOCKS 工厂、拨号必失败；而 `ensureStarted()` 又被这条幂等守卫拦下
  ⇒ **节点永远起不来**。用户说的那个「**永远**」就落在这一行。
- **修法已定：失败驱动，不是探活驱动。**
  信号路径 `OkHttpTransportFactory.create → SOCKS onFailure → ServiceWire.onTailnetSocksFailure
  → TsnetWire.notifySocksRouteFailure`，内部用已存 `currentKey` 重启，30s 节流。
  **否决探活的理由**：SOCKS listener 是本地 Go listener，DERP 死后**仍在监听** ⇒ TCP 探活必假绿；
  且用户场景网络自始至终同一条蜂窝，`onNetworkAvailable` 大概率一次都不响。
- **「第一次失败对用户可见」是正确行为**（席位顶回 leader 的静默重试提议，leader 接受）：
  从「永远连不上」变成「断几秒后自动恢复」本身就是修好了。补充：可见 ≠ 报错吓人。
- **卡在关卡 1（实机复现）**：探针 3/3 断言的是**结构事实**，不是故障本身。
  已要求：**先证明流量确实在走内嵌 tsnet，再断 DERP**，否则会拿到假阴性。
  断包用 `-j DROP` 不用 `REJECT`（REJECT 回 RST，可能反而触发干净的自愈分支；
  真机是静默超时）；且我们的 DERP 是自建广州节点 **TCP 8444 / UDP 3478**，不是 443。
- **B 路径的诚实标注**：席位用「force-stop 冷启动」作弱对照（模拟器上装不了官方 Tailscale），
  **不许写成复现了用户的 B**。

### ⑥ 诊断日志 + 导出（`feat-diagnostic-log-export`）—— 关键路径

- **为什么存在**：用户裁定「抓不到就加日志」。**验收判据**：
  用户真机复现一次、导出一份日志，**我们光看它就能定位根因**，不用再要截图。
- **必须能解两条缺陷**：⑤ 要看得出「state 报 Up 而 SOCKS 拨号在失败」「`ensureStarted` 被幂等守卫拦下」；
  ② 要**光看日志就能算出末列超出屏幕几个像素**。
- **前置四修（审查席预审挖出的既有泄露，先修再做本体）**：
  1. `AuthFrame` 缺安全 `toString()`，默认 `toString` 明文吐 token
  2. `ConnectionConfig` 同上
  3. `TsnetManager.redactAuthKey` 只洗顶层 message、**不触达 cause 链** ——
     一句 `Log.e(TAG, msg, throwable)` 就能漏 authkey
  4. 「认 `tskey-` 前缀」式脱敏对 **headscale 纯 hex** key 必然失效（本工程契约明确放行 headscale）
- **设计决定（已认可）**：不 hook 全局 `Log`（diag 自持写入路径）；
  `registerSecret` 取代前缀匹配（**从结构上解掉第 4 条**）；QR 原始串永不记录。
- **leader 给的两个坑（要在实现里吃掉）**：`registerSecret` 注册**之前**的记录救不了（窗口有多宽要写清）；
  **注册表自己是一份「所有已知秘密的明文集合」**，不能进缓冲/导出，`toString()` 也得安全。
- **极性问题（务必订正）**：那三个 `*LeakTest` 现在是「跑绿 = 泄露存在」。
  作为一次性取证可以，**作为长期回归闸必须翻成「断言不泄露且永远绿」** ——
  否则半年后有人看到绿的 `LeakTest` 会以为一切正常。**语义反着的长期测试比没有测试更危险。**

---

## §4 席位现状（12 席，5 席已退役）

**在岗**：`w-cols-dev`(②开发) `w-cols-prep`(②测试) `w-d38-probe`(③审查·待命)
`w-diag-dev`(⑥开发·worktree) `w-diag-test`(⑥测试) `w-diag-rev`(⑥审查·待 round2)
`w-rev-upload`(①审查·完) `w-scroll-design`(④·待 App 侧) `w-tsresume-probe`(⑤复现)
`w-up-dev` `w-up-probe` `w-up-test`（①三席·完，待命）

**已退役**：`w-c1-{dev,probe,test}`、`w-perf-{link,ts}`（角色文件在 `agents/retired/`）

**看门狗账目**：4 条在途 intent 全部指向活席位，无孤儿。
（今晚归档了一条 08-12 的孤儿 intent `fix-viewport-restore-d38` → 死席位 `w-dev-d38`，
移到 `.team/evidence/retired-intents/`。）

**⚠️ 看门狗 T5 探针约 4 分钟就出针**，与 CLAUDE.md 「心跳周期接近 1 小时」有张力，
会让待命席位反复回「我没卡住」。**处置办法：给已交付的席位写 evidence 文件**
（intent 有、evidence 无 = 判在途），写了就不再戳。

---

## §5 运维与外部

- **生产 daemon** pid 70317，监听 `*:9900`。**未部署**的服务端改动：
  `sendq_metrics.go` 仪表 + `agentstate` 锚点修复 + **缺陷④ 的整套滚轮链路**。
  部署要重启 daemon（断线几秒），**要用户点头**。
- **广州 DERP** `43.136.53.247`，**SSH 端口 52222**（skill 写的 22 是错的），
  TCP 8444 / UDP 3478，自签证书 `sha256-raw:92f3b9d9...22f850`。手机↔Mac 已从 1221ms 降到 ~50ms。
  ⚠️ 该机 `:443` 跑着另一项目的 `claude-chat.service`，**不要碰**。
- **worktree**：`/tmp/am-before`(HEAD 无①修复)、`/tmp/am-after`(fb31674d2)、`/tmp/am-diag`(⑥开发)。
  用完 `git worktree remove`。
- **模拟器** `emulator-5554`（AVD `agentmirror_geo_1260x2800`，1260x2800，API 35）。
  `wedding_user_a_api35` 是别的工程的，不要碰。

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）** ——
  只能 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **【今晚新增，已写进 CLAUDE.md】凭据已泄露 ≠ 停工**：
  用户对该文件的长期决定是「既然泄露了，就写进文件，接下来就用它去测」。
  再次泄露时**只做三件事：一行上报（不复述泄露的值）、就地收紧做法、继续干活**。
  **禁止**因此停工、禁止等新 key、禁止把删本地产物当成风险处置 ——
  片段一旦进入上下文就擦不掉，删截图减少的是执行者的不适而非真实风险。
  轮换与否是用户的事，不是开工前置条件。
  （今晚我违反过一次，被用户批为「掩耳盗铃」且双输，已改正并写死进规则。）
- **报告泄露时永远不要复述泄露的值** —— 那一贴会把片段从一个上下文扩散到另一个。
  写「authkey 的前 N 个字符出现在 X 处并被截进图，已删」即可。
- 查任何配置前先想凭据。**Shadowrocket 偏好 plist 与 `tailscale_keys.bin` 禁读。**
- ⚠️ 禁止 `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
- ⚠️ 本机禁跑无过滤 `ps aux`（暴露席位 API key），核进程一律 `pgrep -fl <精确路径>`。
- 配对 token 与 TS authkey 同级：不落日志、不上屏明文、不入截图，**QR 是唯一合法出口**。
- **不许手改 App 的 SharedPreferences 来绕过配对流程**（今晚拦下过一次）。
- 绝不触碰生产 daemon 与用户真实 tmux，只读也不行；
  起隔离 daemon 必须用 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描。
- 席位禁 `git push`。
- **GPL 隔离**：Termux 系 GPLv3 不可用；herdr-remote(AGPL)、mosh(GPLv3) 只借鉴算法、禁止复制代码。
- 测试净化前缀 `env -u TEAM_AGENT_*`；派单必经 `.team/ta`。
- 全局 CLAUDE.md：**禁止写 memory；禁止用 AskUserQuestion 工具问用户**。

---

## §7 给后继的一句话

**今晚最容易犯的错，是把「席位报了绿」当成「验过了」。**

今晚有四次，绿的东西其实什么都没证明：
Go 探针跑得漂亮但一行产品代码都没执行到；
`w-cols-prep` 的红测红在一个用户永远遇不到的构造值上；
`w-scroll-design` 的 13 条绿测试从来没红过；
而模拟器上「改前包上传成功」——**那不是修复不需要，是这台模拟器根本测不了隧道。**

每次问一句就够了：**这条绿，是在被测对象上、用用户的参数、从红转过来的吗？**
三个条件缺一个，它就还没有证明任何事。
