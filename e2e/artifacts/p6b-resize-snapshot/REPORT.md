# P6b：resize→snapshot 归因复测（新增 snapshots_from_resize 计数）

commit: HEAD（隔离 daemon 现编，含 `SnapshotsFromResize` 计数器）
设备: emulator-5558，客户端: `agentmirror-v6-9653be07f.apk`（同 P6），真实 claude CLI，隔离环境。

## 数字

| 条件 | snapshots_pushed | snapshots_from_resize | 非首帧(pushed-1) | deltas_dropped | queue_peak |
|---|---|---|---|---|---|
| 0ms | 2 | 1 | 1 | 0 | 1 |
| 400ms | **4** | **2** | **3** | 0 | 1 |

（daemon 日志里还有一条 conn=3：2/1/1/63，与 conn=2 完全相同——pm clear+relaunch 之间的连接毛刺，
非本测试触发，已排除；真正 400ms 数据是 conn=4。）

## 判据结果

0ms：`snapshots_pushed − snapshots_from_resize = 2−1 = 1` ✅ 吻合（唯一非首帧快照来自 resize）。

400ms：`snapshots_pushed − snapshots_from_resize = 4−2 = 2 ≠ 1`。**非首帧快照 3 次，resize 只解释了
2 次，缺 1 次未被 `SnapshotsFromResize` 计数的补快照路径。**

## 客户端侧（机器眼，10fps，259帧，25s录屏覆盖21s响应）

`reflowSignal: false` 全程，但有一个孤立非零差分帧（第232帧，远离主聚集区9-32），出现在录屏
接近尾部——响应耗时"Brewed for 21s"，可能对应一次晚到的、被裁进录制窗口尾部的重画，值得和
那第 3 次未计数快照关联，但未确证（帧太少，只是一个观察）。

## 结论（第一轮，已被下轮核对推翻）

假说部分成立：resize 确实是主要贡献者（400ms 下 2 次），但**不是全部**——还有至少 1 次快照补发
没有被现有两条路径（首帧订阅 + resize）覆盖。已第一时间报 leader。

**核对后作废**：`snapshots_pushed` 等计数器是**进程级累计值，不是单连接值**。回查原始
`/tmp/p6-daemon.log`（P6 sendq 任务遗留，未删）发现那轮 `snapshots_pushed 2→10` 实际横跨
**9 条不同连接**（conn=2 到 conn=10，含调试期间多次 pm clear/relaunch 产生的连接毛刺）。
**"非首帧快照 1→9"是多连接累加的测试过程产物，不是单连接异常，此线索作废。**

## 第二轮：断连/重连排查（w-dev-repaint 超时假说）

背景：无 ping/pong + 客户端 10s 读超时 + agent 思考期无数据 → 疑似触发断线重连 → 重订阅 →
完整快照补发 → 客户端整屏重建（"从上往下刷"）。

用**干净重启的 daemon**（新增 `ConnectionsTotal`/`SubscribesTotal`/`SnapshotsFromSubscribe` 计数器，
计数器归零）分别测：

| 测试 | 响应耗时 | connections_total（真实测试连接自身生命周期内） | 结论 |
|---|---|---|---|
| 400ms，"think carefully"，250行 | Crunched 12s | 停在2，未再涨 | 未观测到重连 |
| 400ms，"think very carefully"，300行 | Cogitated 14s | 停在4，未再涨 | 未观测到重连 |

两轮独立测试，`snapshots_pushed = snapshots_from_resize + snapshots_from_subscribe` 等式均成立
（分别 2=1+1、7=3+4），**无第三条快照来源**。

`w-dev-repaint` 已用字节码定案：OkHttp 的 WS reader 在等待下一帧期间会清除读超时，空闲思考期
根本不受 10s 读超时约束——**因此不管客户端到服务端的最大数据间隙多大，都不会触发断连**。
据此，"全程最大数据间隙"这个原计划的第三个测量指标被判定为不再具有判据价值，中止测量
（尝试过一次，`recapprobe` 作为并行订阅探针本身失灵，`chunks=0`，工具问题，未深挖，已如实
报告未伪造数字）。

## 本任务的最终状态

**环境里造不出用户报告的任何一个现象**（发消息不能滑、断线整屏刷）。十余轮测试排除了：
几何差异、历史规模/淘汰、真实 vs 测试 APK 版本、delta 丢弃、resize 快照来源、超时断连。
根因仍未定位。leader 已决定改向用户提议：把这套计数器（`sendq_metrics.go`）装到用户正在用的
生产 daemon 上，等真实现场复现时直接读数，而非继续在造不出现象的隔离环境里排查。

## 今日方法论纪律要点（供后续复用）

1. `delay_proxy.py` 原设计只加延迟不产生背压——四版迭代后修好（真实带宽节流 + 收缩
   `SO_RCVBUF`），四次失败原因写进文件头注释。
2. 进程级累计计数器（`SendQueueMetrics`）不能按单条连接读——必须看该连接自己生命周期内
   计数器有没有继续增长，而不是看它teardown时的绝对值。
3. `recapprobe` 作为独立并行订阅客户端在这次环境里未能可靠工作（`chunks=0`），未进一步排查
   根因，如实标注为工具失败而非产品信号。

隔离环境（daemon/proxy/tmux/临时目录）已全部清理，模拟器已释放。
