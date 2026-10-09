package api

// sendq_metrics_test.go — P0 自检测试：per-connection 计数必须各自隔离，teardown 行
// 报的是本连接的数，不是进程累计（此前进程级累计被打在 per-conn 行误导数轮）。

import (
	"testing"
)

// TestConnMetricsIsolation: 两条连接各自产生不同的计数，断言互不污染。
// 直接测 ConnMetrics（纯值类型，不依赖 wsConn/websocket/Server 构造）。
func TestConnMetricsIsolation(t *testing.T) {
	var c1 ConnMetrics
	var c2 ConnMetrics

	// 连接 1：丢 3 次 delta、发 5 帧（其中 2 快照、1 来自 resize、1 来自订阅）。
	for i := 0; i < 3; i++ {
		c1.recordDrop()
	}
	c1.recordSnapshot()
	c1.recordSnapshot()
	c1.recordResizeSnapshot()
	c1.recordSubscribe()
	for i := 0; i < 5; i++ {
		c1.recordFramesSent()
	}

	// 连接 2：完全不同的一组（丢 1 次、发 2 帧、1 快照来自订阅）。
	c2.recordDrop()
	c2.recordSnapshot()
	c2.recordSubscribe()
	c2.recordFramesSent()
	c2.recordFramesSent()

	// 断言各自报各自的数，互不污染。
	if c1.DeltasDropped != 3 {
		t.Fatalf("conn1 deltas_dropped = %d, want 3", c1.DeltasDropped)
	}
	if c1.SnapshotsPushed != 2 {
		t.Fatalf("conn1 snapshots_pushed = %d, want 2", c1.SnapshotsPushed)
	}
	if c1.SnapshotsFromResize != 1 {
		t.Fatalf("conn1 snapshots_from_resize = %d, want 1", c1.SnapshotsFromResize)
	}
	if c1.SnapshotsFromSubscribe != 1 {
		t.Fatalf("conn1 snapshots_from_subscribe = %d, want 1", c1.SnapshotsFromSubscribe)
	}
	if c1.FramesSent != 5 {
		t.Fatalf("conn1 frames_sent = %d, want 5", c1.FramesSent)
	}

	if c2.DeltasDropped != 1 {
		t.Fatalf("conn2 deltas_dropped = %d, want 1", c2.DeltasDropped)
	}
	if c2.SnapshotsPushed != 1 {
		t.Fatalf("conn2 snapshots_pushed = %d, want 1", c2.SnapshotsPushed)
	}
	if c2.FramesSent != 2 {
		t.Fatalf("conn2 frames_sent = %d, want 2", c2.FramesSent)
	}
	// 连接 2 没有 resize 快照，必须为 0（不被 c1 污染）。
	if c2.SnapshotsFromResize != 0 {
		t.Fatalf("conn2 snapshots_from_resize = %d, want 0 (isolation violated)", c2.SnapshotsFromResize)
	}
}
