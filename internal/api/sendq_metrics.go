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
	"sync/atomic"
)

// SendQueueMetrics 进程级发送队列健康指标（原子计数，无需锁）。
type SendQueueMetrics struct {
	// DeltasDropped 因队列满被丢弃的 delta 帧数（sendMirror default 分支命中次数）。
	DeltasDropped atomic.Int64
	// SnapshotsPushed 服务端补发的快照帧数（sendBinary(SNAPSHOT) 次数，排除首帧订阅）。
	SnapshotsPushed atomic.Int64
	// QueuePeak 单连接 sendCh 达到过的最大长度（满=256 的近似压力信号）。
	QueuePeak atomic.Int64
	// FramesSent 发出的总帧数（发送侧活动基线）。
	FramesSent atomic.Int64
}

// snapshotCounter 仅统计快照帧（首帧订阅快照也算，但对照用「非首帧快照」由调用方区分）。
// 保留给 sendBinary 调用处递增，用于「补发快照」计数。
func (m *SendQueueMetrics) recordSnapshot() {
	m.SnapshotsPushed.Add(1)
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
	DeltasDropped   int64
	SnapshotsPushed int64
	QueuePeak       int64
	FramesSent      int64
}

func (m *SendQueueMetrics) Snapshot() SendQueueMetricsSnapshot {
	return SendQueueMetricsSnapshot{
		DeltasDropped:   m.DeltasDropped.Load(),
		SnapshotsPushed: m.SnapshotsPushed.Load(),
		QueuePeak:       m.QueuePeak.Load(),
		FramesSent:      m.FramesSent.Load(),
	}
}
