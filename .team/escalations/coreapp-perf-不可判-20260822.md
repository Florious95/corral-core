# coreapp-v1 · t.perf 不可判（机器被占满，非活坏）

**结论：合法终态 `不可判`（判据 exit=2），⛔ 不折进通过也不折进失败。**
链停在这里，等机器让出来才能续，**不是**代码问题，也不是席位偷懒。

## 判据原文（leader 在 wt-ca 里自己复跑，非席位自报）

```
$ sh tools/perfbase/judge-perf-nonregress.sh
UNJUDGEABLE big_scrollback.first_draw.p50 取不到：float() argument must be a string or a number, not 'NoneType'
exit=2
```

取不到数，是因为**根本没测**——没有 `recheck-*-capp-ab.json`。

## 为什么没测：本机负载

| 时刻 | load1 / load5 / load15 | free page | 席位动作 |
|---|---|---|---|
| 14:51 接手 | 71.93 / 59.38 / 35.39 | 20097（~314MB） | 不起模拟器 |
| 14:56 | **115.92** / 90.70 / 55.47 | 5046（~79MB） | 仍不起 |
| 14:58 leader 复核 | **107.95** / 96.96 / 63.19 | 3554（~55MB） | — |

占用来源（`ps -axo pid,ppid,etime,pcpu,rss,comm`，⛔ 未取 argv）：
`ffmpeg` pcpu **648.7%**（pid 90333，已跑 12:40）、`爱奇艺` 11.4%、多个 Chrome Helper 合计约 60%。
**这些是用户自己的进程，⛔ leader 不动。**

对照：本工程当初禁模拟器时的阈值是 free 59MB / load 29 —— **现在的 load 是那条禁令的约 4 倍**。

## 席位做对了什么（记下来，这是正确行为）

`pb-emu` 没有硬起模拟器把机器拖垮，没有编 A/B 数，没有换取数方式，
没有把没跑的写成 PASS，并且**把上一轮误覆盖的 `.team/perf/raw/` 恢复了**。
按任务书 02「内存不够或 load 过高 ⇒ 报不可判」执行，行为符合判据四态。

## 续跑条件（满足其一即可重起驱动器）

load1 ≤ 15 且 free+inactive 充裕，然后：

```
cd .worktrees/wt-ca/.team/nodes/ca-emu/tmp/appsrc-a && ./gradlew :app:assembleRelease
# 核 md5 A ≠ B（A=composite 构建，B=引用式构建 3ebc9c55703c780c842a2f410b85034e）
APK_A=... APK_B=.../app-release.apk bash tmp/runab.sh
# 落 .team/perf/recheck-<日期>-capp-ab.json；判据地板 = 同批 A 组
```

⛔ 在 load 降下来之前**不要重起驱动器**：每次重派都会撞同一堵墙，
只会烧席位额度并把 `rounds` 推向上限，而账本层看起来像是「反复失败」。
