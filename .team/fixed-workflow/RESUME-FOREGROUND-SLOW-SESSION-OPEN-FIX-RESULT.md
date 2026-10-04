# READY 新会话缺首快照：修复与验收记录

## 当前结论

独立 A/B 已确证产品缺口：前台 Listing 成功解除旧探活后，新会话 Subscribe 本地返回 true，但若没有首 SNAPSHOT，黄金 Core 不主动超时/重发/重拨，可以一直显示加载。正常 listener、cache geometry 与真实 server pane 切换控制场景通过，不支持把正常切换定为必然冲突。

原始用户 `open_id=q` 日志止于菜单取数，缺后续 route/subscribe/frame/draw 与现场 APK 身份；**独立红测证实缺陷，不代表证明那次用户延迟一定由网络半开造成**。详见同目录 ANALYSIS 文档的路径、行号及其他候选原因。

修复已在隔离工作树编码/冻结，最终 recovery3 独立复验 **61/61通过**，唯一官方 App APK已构建并核取SHA。未改 UI/服务端/9900，未合并 PR，未安装用户手机。真实手机UI验收因设备缺失不可判，不宣称现场延迟已实机消除。

## 黄金源恢复（与修复分开）

- App baseline `83ba5cc363ad7a27ce60e21f539d8a8bc1e89edf`，tree `e2ae4d36e21bcaa42b3a03fbcbc6aa494427fd9c`。
- 真实 Maven 黄金 Core `20260915.close`：AAR `723b1f73c72f7530c63685d93b29b85772f4bc7c374f196ec8124dfba3ab0b82`，JAR `0c2aab7c59ef67d2775074036070f05ef734dba258ed0343345e96d0ee15f66c`。
- 远端 Core main `05c8a7c` 不含完整黄金 Core；历史 Maven worktree 的 gitdir 已失效。本次没有把历史未提交源码当精确基线。
- 根据 `c2ad5ec4d` 上历史 `issue40-core-fix-composite` Connection 源码及黄金 JAR 的 Listing 分支，独立恢复基线提交 `c7fe4e68a`。使用 Gradle 8.14.5 / Kotlin 2.2.0 / Java 17 与未变黄金 protocol 重建，**所有 class/Kotlin metadata 条目与黄金 JAR 字节完全一致**（检查33条，排除 ZIP时间/MANIFEST；归档SHA本身不同）。3个原Core测试通过。
- leader 明确批准黄金源恢复独立提交、新不可变版本发布、App仅钉依赖。

原始证据目录：`.team/fixed-workflow/evidence/resume-foreground-slow-session-open-20261002-developer/`：`golden-core-source.patch`、`golden-rebuild.log/.exit`、`golden-classes-comparison.json`、初始 javap 与源码SHA。

## 最小修复

产品代码只动 Core 的 `ConnectionManager.kt` 与 `Connection.kt`：

1. READY Subscribe/重连重放时按连接 + ref 启动首 SNAPSHOT 守卫；在发帧前登记，兼容同步测试传输。
2. 2,000ms 仅重发一次，以最新 rows/cols/retain 意图；原始 4,000ms 总期限不因 Listing/DELTA/其他ref/重复subscribe而延展。
3. pump 先裁决原始硬期限；首pump若已经超过期限，直接重拨而非再等一个重试窗口。正常2s泵相位下约≤6s开始重拨，不把网络握手/新快照完成时间声称为≤6s。
4. 总期限到本地 finish 旧逻辑连接、启用既有零退避 dial，并重放活跃订阅。**不等待 OkHttp 异步 close 握手回调**；随后请求旧transport清理。
5. 仅当前连接匹配ref的 SNAPSHOT解除；unsubscribe/stop/断线清理。closed使用 volatile 可见性、finish同步单终结，避免UI泵与WS终结并发重复回调；已结束Socket后到的事件忽略。
6. pump以 `remove(ref, wait)` 原子赢取超时守卫才重拨；ConcurrentHashMap迭代持有的、已被WS快照解除的旧条目不能触发重拨。retry仅发送，不创建新的守卫，避免与并发快照确认争用时重新arm。

精确最终源码行：`ConnectionManager.kt:146–149`守卫、`317–341`泵裁决、`584–598`发送、`611`退订清理、`910/1001`重放与终结清理、`963–969`快照确认；`Connection.kt:69,137–143,218`可见性/本地结束/单终结。

## 精确冻结身份

| 对象 | 提交 / 树 | 路径与PR |
|---|---|---|
| Core源码 | `843b18222ea4f9348e87bbc2c611b13a49728d08` / `12f3bfa9982bc0e039291d8c86bd5adfd0e02b1d` | `/Volumes/nvme/Projects/CorralCore-worktrees/session-snapshot-deadline`；corral-core PR107 |
| 不可变 Maven产物 | `28df59b69bb638f32d7eae694a6c9f9174dc742a` / `68aef1425c0bc0b7314ad95295c5d10330f293df` | `/Volumes/nvme/Projects/CorralCore-worktrees/session-snapshot-maven`；corral-core PR108 |
| App依赖候选 | `75c67c4dd29a0722e687542fcc7ca7311cf448a6` / `2ed4db647d84c9e65fef48e4e634440c720bfce9` | `/Volumes/nvme/Projects/CorralCore-worktrees/session-snapshot-app`；corral-app PR44 |

最终坐标 `dev.agentmirror.core:core-conn:20261002.snapshot-recovery3`：
- AAR SHA256 `ceeb3752668d2acd2128265117405faa6e8d40fbe0da508e4f781b7b1f89af75`。
- JAR SHA256 `3c22c27b31df6a9c8776e5cbeecb3fd54b5d7e97f33b7bb734751a0483d0c841`。
- local repo：Core worktree `.team/snapshot-core-build/maven-repo`。
- App优先 Git不可变提交URL，改动仅 settings仓库声明与conn版本；其余App源码零改动。
- 黄金protocol/terminal原坐标 `20260915.close` 在远端原maven不存在，现复制到上述不可变提交，**不重建、不覆盖已有坐标/metadata**：protocol AAR `e6ec508b623d98f15ad0341b405b558fe0e207d433b3d0117ca70812892b3e79`，terminal AAR `b81934a3ea6d11913a03b89b93aa08be1f755a54a3cdf9ad26e3eaad134c7865`。

recovery1/ad4ff6b59/ed9f3ad2c和recovery2/7491b2aaf/dc6520706均是历史中间候选，独立回归曾通过；分别在完成本地finish跨线程可见性/单终结以及守卫并发原子裁决后升级。recovery1/2 APK明确作废，不交用户；其坐标/证据保留，不冒充最终绿。

## 已执行检查 / 尚待检查

### Developer 实际执行
- 黄金 Connection classes/metadata逐项字节比对：零差异。
- recovery1构建/11个单测：exit0，证据 `candidate-build.log/.exit`、`recovery1-unit-xml/`。
- recovery2、recovery3各自构建与本地Maven发布/12个单测：exit0，证据 `recovery2-build.log/.exit`、`recovery2-unit-xml/`、`recovery3-build.log/.exit`、`recovery3-unit-xml/`。
- 自有单测覆盖2s仅一次重发/4s截止、最新几何与retain、首次晚pump、不匹配ref/DELTA/Listing不清、匹配快照清、重复subscribe不延期、unsubscribe/stop、离线直到实际重放才计时、无close回调仍即刻新dial、旧事件不取消新守卫/不重复重拨、并发timeout与failure只终结一次。
- 隔离树 `git diff --check`通过，源码/产物/App均推feature分支；三个PR未合并。

### Independent tester 交付（不看红测源码）
黄金基线 `/tmp/foreground-slow-evidence-baseline-20261002/`：
- PR41 2项 GREEN；新 A/B RED。
- listener routing / `%0→新ref` GREEN；真实server隔离tmux+WS退旧订新首SNAPSHOT 67ms，exit0。
- cache geometry 4/4、SessionVM 47/47通过。
- PerfTraceWiring 基线3/4，一个onDraw空屏捕获断言失败，未冒认为本目标产品红。

历史 recovery1 `/tmp/foreground-slow-evidence-candidate-ad4ff6-20261002/`：
- A/B+matching/wrongref/unsubscribe边界5/5；PR41 2/2、routing2/2、transition1/1、cache4/4、SessionVM47/47最终回归通过。
- 首轮多类组合有一次既有copy-mode line177 flake（actual restored/live-live）；单独及完整组合重跑exit0，保留原始失败和rerun，不声称首跑全绿。

历史recovery2独立回归全部通过，APK `a1d8db0e56e82dfb532ceeeae492d6f3601afc1e1d2e704c4ec68688b4a9f6f0`、三核/远端Git产物同源已核；该APK在recovery3冻结前明确作废。原始receipt `.team/evidence/session-snapshot-recovery2-tester/FINAL-FROZEN-RECEIPT.md` 已标SUPERSEDED。

最终 recovery3 独立回归及唯一APK已完成：
- 原始receipt `.team/evidence/session-snapshot-recovery3-tester/FINAL-FROZEN-RECEIPT.md`；`regression.exit=0`，JUnit 6类合计61/61，无fail/error/skipped。developer只读取结果XML/receipt，未读取独立红测源码。
- A/B及边界5/5，PR41 2/2，listener2/2，transition1/1，cache4/4，SessionVM47/47，**均对应本页最终App/Core/产物身份**。
- APK `.team/evidence/session-snapshot-recovery3-tester/app-debug.apk`，41,074,779 bytes，SHA256 `0efb2251cedf618feb67592e65557cb231e04d7cd0ae7bcad3221310501b51fd`（developer再次本地hash实核）。构建exit0；包名 `dev.agentmirror.app`，versionCode1/versionName0.1.0。
- 三核dependencyInsight与远端Git产物SHA全一致，原始 `remote-three-core-hashes.txt`、dependency日志在tester同证据目录。
- App真实UI设备验证已报告实际阻塞（devices=[]、无adb）；没有安装/视觉证据。原始q全链及现场手机APK身份仍缺。交付不等于实机问题已唯一归因/手动验收通过；PR保持未合并，9900不动。
