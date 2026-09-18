package api

// sendq_metrics.go — 发送队列健康指标（D-36「发消息整屏刷」失败态观测 + 常驻健康指标）。
//
// 背景（leader msg_22b66343e7b8）：sendMirror 非阻塞发 sendCh，队列满时丢弃 delta
// （004 契约：丢数据靠下个快照对账）。慢链路（TS）队列常满 → 丢 delta → 客户端与主机
// 不一致 → 服务端补发快照 → 客户端 replaySnapshot 清屏重建 → 「从上往下刷」。
// 局域网队列从不满 → 不丢 → 流畅。这可能是「发消息整屏刷」的真正根因（第 12 条假说，
// 在服务端发送队列，不在客户端渲染层）。
//
// 本文件暴露原子计数器：丢弃次数、快照补发次数、队列峰值、发送总帧数。
// 常驻健康指标（「丢了多少数据」本该是这个产品的健康指标），不随取证移除。

import (
	"sync"
	"sync/atomic"
)

// SendQueueMetrics 进程级发送队列健康指标（原子计数，无需锁）。
type SendQueueMetrics struct {
	// DeltasDropped 因队列满被丢弃的 delta 帧数（sendMirror default 分支命中次数）。
	DeltasDropped atomic.Int64
	// SnapshotsPushed 服务端补发的快照帧数（sendBinary(SNAPSHOT) 次数，含首帧订阅）。
	SnapshotsPushed atomic.Int64
	// SnapshotsFromResize 由 handleResize（真实 reflow）补发的快照帧数——
	// 溯源「非首帧快照 N 次整屏重建」来自哪条路径（leader msg_871ae11b3380）。
	SnapshotsFromResize atomic.Int64
	// SubscribesTotal handleSubscribe 被调用次数（含首次订阅与重复订阅）。
	SubscribesTotal atomic.Int64
	// SnapshotsFromSubscribe 订阅路径推的快照帧数（handleSubscribe 首帧）。
	SnapshotsFromSubscribe atomic.Int64
	// ConnectionsTotal 建立的 WS 连接数（serveConn 计数；连接数本身可能是重连线索）。
	ConnectionsTotal atomic.Int64
	// QueuePeak 单连接 sendCh 达到过的最大长度（满=512 的近似压力信号）。
	QueuePeak atomic.Int64
	// FramesSent 发出的总帧数（发送侧活动基线）。
	FramesSent atomic.Int64
}

// snapshotCounter 仅统计快照帧（首帧订阅快照也算，但对照用「非首帧快照」由调用方区分）。
// 保留给 sendBinary 调用处递增，用于「补发快照」计数。
func (m *SendQueueMetrics) recordSnapshot() {
	m.SnapshotsPushed.Add(1)
}

// recordResizeSnapshot 记录由 handleResize（真实 reflow）补发的快照（溯源用）。
func (m *SendQueueMetrics) recordResizeSnapshot() {
	m.SnapshotsFromResize.Add(1)
}

// recordSubscribe 记录 handleSubscribe 被调用（含首次与重复订阅）。
func (m *SendQueueMetrics) recordSubscribe() {
	m.SubscribesTotal.Add(1)
	m.SnapshotsFromSubscribe.Add(1)
}

// recordConnection 记录新建了一条 WS 连接（serveConn）。
func (m *SendQueueMetrics) recordConnection() {
	m.ConnectionsTotal.Add(1)
}

// ConnMetrics 单条连接自己的计数（P0 修复：teardown 行报本连接的数，字段前缀 conn.*）。
// Relay、writer 与 teardown 可并发访问同一连接的指标；锁只保护这组低频
// 诊断计数，避免 race detector 把真实溢出归因误报为日志竞态。
type ConnMetrics struct {
	mu sync.Mutex

	DeltasDropped          int64
	SnapshotsPushed        int64
	SnapshotsFromResize    int64
	SnapshotsFromSubscribe int64
	FramesSent             int64

	// Wire counters include the complete binary WebSocket message after the
	// writer successfully writes it.
	DeltaWireBytes    int64
	SnapshotWireBytes int64

	// Reflow counters count bytes drained from pipe-pane while the resize gate
	// is active. They are separate from queue drops: redraw bytes are suppressed
	// by policy, not lost to a full send queue.
	ReflowDiscardedBytes  int64
	ReflowDiscardedChunks int64
	ReflowEpochs          int64
}

// ConnMetricsSnapshot is a value copy; it never copies the live metrics mutex.
type ConnMetricsSnapshot struct {
	DeltasDropped          int64
	SnapshotsPushed        int64
	SnapshotsFromResize    int64
	SnapshotsFromSubscribe int64
	FramesSent             int64
	DeltaWireBytes         int64
	SnapshotWireBytes      int64
	ReflowDiscardedBytes   int64
	ReflowDiscardedChunks  int64
	ReflowEpochs           int64
}

// recordDrop 记录本连接因队列满丢弃的 delta。
func (m *ConnMetrics) recordDrop() {
	m.mu.Lock()
	m.DeltasDropped++
	m.mu.Unlock()
}

// recordSnapshot 记录本连接发出的快照帧（含首帧订阅）。
func (m *ConnMetrics) recordSnapshot() {
	m.mu.Lock()
	m.SnapshotsPushed++
	m.mu.Unlock()
}

// recordResizeSnapshot 记录本连接由 resize 补发的快照。
func (m *ConnMetrics) recordResizeSnapshot() {
	m.mu.Lock()
	m.SnapshotsFromResize++
	m.mu.Unlock()
}

// recordSubscribe 记录本连接的订阅次数（含重复订阅）。
func (m *ConnMetrics) recordSubscribe() {
	m.mu.Lock()
	m.SnapshotsFromSubscribe++
	m.mu.Unlock()
}

// recordFramesSent 记录本连接发出的总帧数。
func (m *ConnMetrics) recordFramesSent() {
	m.mu.Lock()
	m.FramesSent++
	m.mu.Unlock()
}

// recordWire records bytes after a binary frame has been written successfully.
func (m *ConnMetrics) recordWire(snapshot bool, bytes int) {
	m.mu.Lock()
	if snapshot {
		m.SnapshotWireBytes += int64(bytes)
	} else {
		m.DeltaWireBytes += int64(bytes)
	}
	m.mu.Unlock()
}

// recordReflowDiscarded records one pipe chunk drained during a reflow gate.
func (m *ConnMetrics) recordReflowDiscarded(bytes int) {
	m.mu.Lock()
	m.ReflowDiscardedBytes += int64(bytes)
	m.ReflowDiscardedChunks++
	m.mu.Unlock()
}

// recordReflowEpoch records one resize gate coordination epoch.
func (m *ConnMetrics) recordReflowEpoch() {
	m.mu.Lock()
	m.ReflowEpochs++
	m.mu.Unlock()
}

func (m *ConnMetrics) snapshot() ConnMetricsSnapshot {
	m.mu.Lock()
	defer m.mu.Unlock()
	return ConnMetricsSnapshot{
		DeltasDropped:          m.DeltasDropped,
		SnapshotsPushed:        m.SnapshotsPushed,
		SnapshotsFromResize:    m.SnapshotsFromResize,
		SnapshotsFromSubscribe: m.SnapshotsFromSubscribe,
		FramesSent:             m.FramesSent,
		DeltaWireBytes:         m.DeltaWireBytes,
		SnapshotWireBytes:      m.SnapshotWireBytes,
		ReflowDiscardedBytes:   m.ReflowDiscardedBytes,
		ReflowDiscardedChunks:  m.ReflowDiscardedChunks,
		ReflowEpochs:           m.ReflowEpochs,
	}
}

// recordDrop 递增丢弃计数。
func (m *SendQueueMetrics) recordDrop() {
	m.DeltasDropped.Add(1)
}

// recordQueued 记录入队并更新队列峰值（len 近似，非精确并发峰值）。
func (m *SendQueueMetrics) recordQueued(queuedLen int) {
	m.FramesSent.Add(1)
	for {
		cur := m.QueuePeak.Load()
		if int64(queuedLen) <= cur || m.QueuePeak.CompareAndSwap(cur, int64(queuedLen)) {
			return
		}
	}
}

// Snapshot 返回当前指标的只读快照（取证/健康检查用；原子读，非精确并发一致）。
type SendQueueMetricsSnapshot struct {
	DeltasDropped          int64
	SnapshotsPushed        int64
	SnapshotsFromResize    int64
	SubscribesTotal        int64
	SnapshotsFromSubscribe int64
	ConnectionsTotal       int64
	QueuePeak              int64
	FramesSent             int64
}

func (m *SendQueueMetrics) Snapshot() SendQueueMetricsSnapshot {
	return SendQueueMetricsSnapshot{
		DeltasDropped:          m.DeltasDropped.Load(),
		SnapshotsPushed:        m.SnapshotsPushed.Load(),
		SnapshotsFromResize:    m.SnapshotsFromResize.Load(),
		SubscribesTotal:        m.SubscribesTotal.Load(),
		SnapshotsFromSubscribe: m.SnapshotsFromSubscribe.Load(),
		ConnectionsTotal:       m.ConnectionsTotal.Load(),
		QueuePeak:              m.QueuePeak.Load(),
		FramesSent:             m.FramesSent.Load(),
	}
}
