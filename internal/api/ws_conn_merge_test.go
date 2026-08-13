package api

// ws_conn_merge_test.go — delta 背压合并（perf-delta-backpressure-merge）独立验证。
//
// 任务纪律④：临时取证/诊断代码放独立文件，不塞进高频改动文件。
// 本文件是「关卡2 红测（本地实现侧）」：在不依赖网络/不依赖真实队列的条件下，
// 用纯协议层（EncodeBinary）构造的真实二进制 delta 帧，验证合并的字节等价性、
// 1 MiB 上限、ref 隔离与指标语义。最终由 w-c1-test 在真实链路/队列上收口。
//
// 关键不变式（docs/ts-link-baseline.md §语义安全）：
//   - 字节序逐字节等价：一个大 delta == N 个小 delta（AnsiParser 顺序状态机）
//   - 帧头允许一帧装更多字节（payload ≤ 1 MiB）
//   - 合并只发生在「本来就在排队的东西」——不引入定时器、零延迟代价
//   - 多 ref 背压时各 ref 各自累积（绝不跨流拼接、绝不丢字节）

import (
	"context"
	"strings"
	"sync"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestMergePendingDeltaSameRefConcat: 同一 ref 的连续 delta 被顺序拼接。
// 语义：合并前 N 个 delta 帧的 payload 字节流 == 合并后一个大帧的 payload 字节流。
func TestMergePendingDeltaSameRefConcat(t *testing.T) {
	ref := "s1"
	d1 := mkDeltaFrame(t, ref, "hello ")
	d2 := mkDeltaFrame(t, ref, "world\n")
	d3 := mkDeltaFrame(t, ref, "\x1b[32mgreen\x1b[0m")

	c := newMergeHarness(t)

	// 先填满 sendCh（cap 256），让 sendMirror 走 default 分支进入合并缓冲。
	c.fillSendCh(t)

	c.conn.sendMirror(d1)
	c.conn.sendMirror(d2)
	c.conn.sendMirror(d3)

	wantPayload := concatPayloads(t, ref, "hello ", "world\n", "\x1b[32mgreen\x1b[0m")

	got := pendingPayloadFor(t, c.conn, ref)
	if string(got) != string(wantPayload) {
		t.Fatalf("merged payload differs\n got %q\nwant %q", got, wantPayload)
	}
	// 缓冲只存 payload（无帧头）。
	if len(got) > 0 && got[0] == 'R' {
		t.Fatalf("buffer stored a full frame, expected payload only: %q", got)
	}
	// 只有 1 个条目（同 ref 连续合并进一个 stream）。
	if n := len(c.conn.pending); n != 1 {
		t.Fatalf("pending streams = %d, want 1", n)
	}

	// 关卡5：并入的每一帧都计了 buffered（本连接）。
	if got := c.conn.connMetrics.DeltasBuffered; got != 3 {
		t.Fatalf("conn deltas_buffered = %d, want 3", got)
	}
}

// TestMergePendingDeltaMultiRefNoCrossPollution: 双 ref 背压下各 ref 各自累积、
// 互不污染、绝不丢字节。test 席红测 TestDeltaMergeRefIsolationOnWire 的方法级验证。
func TestMergePendingDeltaMultiRefNoCrossPollution(t *testing.T) {
	refA, refB := "alpha", "beta"
	c := newMergeHarness(t)
	c.fillSendCh(t)

	// 交替生产 A/B（队列满，全部走合并路径）。
	for i := 0; i < 10; i++ {
		c.conn.sendMirror(mkDeltaFrame(t, refA, "A"+string(rune('0'+i))))
		c.conn.sendMirror(mkDeltaFrame(t, refB, "B"+string(rune('0'+i))))
	}

	gotA := pendingPayloadFor(t, c.conn, refA)
	gotB := pendingPayloadFor(t, c.conn, refB)

	var wantA, wantB string
	for i := 0; i < 10; i++ {
		wantA += "A" + string(rune('0'+i))
		wantB += "B" + string(rune('0'+i))
	}
	if string(gotA) != wantA {
		t.Fatalf("alpha stream corrupted: got %q want %q", gotA, wantA)
	}
	if string(gotB) != wantB {
		t.Fatalf("beta stream corrupted: got %q want %q", gotB, wantB)
	}
	// 无跨流拼接：alpha 流里绝无 beta 字节。
	if strings.Contains(string(gotA), "B") || strings.Contains(string(gotB), "A") {
		t.Fatalf("cross-stream pollution: A=%q B=%q", gotA, gotB)
	}
	// buffered 计数 = 20 帧全部并入（零丢失）。
	if got := c.conn.connMetrics.DeltasBuffered; got != 20 {
		t.Fatalf("conn deltas_buffered = %d, want 20 (zero loss)", got)
	}
	// DeltasDropped 应为 0（多 ref 缓冲下无丢弃路径）。
	if got := c.conn.connMetrics.DeltasDropped; got != 0 {
		t.Fatalf("conn deltas_dropped = %d, want 0 (multi-ref buffer never drops)", got)
	}
}

// TestSealStreamLocked: sealStreamLocked 方法级单测——缓冲有内容且队列有位置时，
// 重建帧入队、条目移除。字节完整（重建后 == 原帧）。
func TestSealStreamLocked(t *testing.T) {
	refA := "s1"
	dA := mkDeltaFrame(t, refA, "alpha")

	c := newMergeHarness(t)
	c.conn.pendingMu.Lock()
	c.conn.pending = []pendingStream{{ref: refA, payload: []byte("alpha")}}
	c.conn.pendingMu.Unlock()

	c.conn.sealStreamLocked(0)

	// 条目移除、缓冲空。
	c.conn.pendingMu.Lock()
	empty := len(c.conn.pending) == 0
	c.conn.pendingMu.Unlock()
	if !empty {
		t.Fatalf("buffer not cleared after seal: %+v", c.conn.pending)
	}
	// A 帧应已入队（队头，无 prefill），重建后 == 原 dA。
	m := c.pop(t)
	if string(m.data) != string(dA) {
		t.Fatalf("sealed frame != original dA")
	}
}

// TestSealStreamLocked_Full: sealStreamLocked 方法级单测——队列满时 seal 失败，条目保留。
func TestSealStreamLocked_Full(t *testing.T) {
	refA := "s1"
	c := newMergeHarness(t)
	c.fillSendCh(t) // 队列满

	c.conn.pendingMu.Lock()
	c.conn.pending = []pendingStream{{ref: refA, payload: []byte("alpha")}}
	c.conn.pendingMu.Unlock()

	c.conn.sealStreamLocked(0)

	// 条目保留原内容。
	c.conn.pendingMu.Lock()
	kept := len(c.conn.pending) == 1 && c.conn.pending[0].ref == refA && string(c.conn.pending[0].payload) == "alpha"
	c.conn.pendingMu.Unlock()
	if !kept {
		t.Fatalf("stream not kept on full queue: %+v", c.conn.pending)
	}
}

// TestMergePendingDeltaOverCapStillWithinLimit: 超限拼接的兜底正确性——
// 第一块 700KiB 进缓冲，第二块同 ref 到达且队列满：seal 失败→条目保留 big，
// big2 开新条目（同 ref 两个条目），每个 ≤1MiB，绝不越限、绝不丢。
func TestMergePendingDeltaOverCapStillWithinLimit(t *testing.T) {
	ref := "s1"
	big := make([]byte, 700*1024)
	big2 := make([]byte, 700*1024)
	dBig := mkDeltaFrame(t, ref, string(big))
	dBig2 := mkDeltaFrame(t, ref, string(big2))

	c := newMergeHarness(t)
	c.fillSendCh(t)

	c.conn.sendMirror(dBig)
	c.conn.sendMirror(dBig2) // 超限 → seal big 失败（满）→ big 保留 + big2 开新条目

	c.conn.pendingMu.Lock()
	streams := c.conn.pending
	c.conn.pendingMu.Unlock()
	if len(streams) != 2 {
		t.Fatalf("pending streams = %d, want 2 (big kept + big2 new entry)", len(streams))
	}
	for i, s := range streams {
		if len(s.payload) > maxMergedDeltaBytes {
			t.Fatalf("stream[%d] exceeds 1 MiB cap: %d", i, len(s.payload))
		}
	}
	if string(streams[0].payload) != string(big) {
		t.Fatalf("stream[0] content changed after overflow attempt")
	}
	if string(streams[1].payload) != string(big2) {
		t.Fatalf("stream[1] != big2")
	}
}

// TestMergePendingDeltaByteEquivalence: 合并后重建的帧与「原样分别发送」在客户端
// 收到的字节流上逐字节等价——把同一 ref 的 N 个 delta 合并成一帧,其 payload 等于
// N 个 delta 的 payload 顺序拼接（关卡2 判据的纯协议层验证）。
func TestMergePendingDeltaByteEquivalence(t *testing.T) {
	ref := "s1"
	chunks := []string{"\x1b[31mred\x1b[0m", "\n", "line2 ", "\x1b[32mgreen\x1b[0m", "\n\x1b[0m"}
	c := newMergeHarness(t)
	c.fillSendCh(t)

	for _, ch := range chunks {
		c.conn.sendMirror(mkDeltaFrame(t, ref, ch))
	}

	// 缓冲 payload = 各 chunk 顺序拼接。
	want := concatPayloads(t, ref, chunks...)
	if got := pendingPayloadFor(t, c.conn, ref); string(got) != string(want) {
		t.Fatalf("merged payload != concat\n got %q\nwant %q", got, want)
	}

	// 清空 sendCh 里的 prefill（合并帧排在它们后面）,再 flush。
	c.drainAll(t)
	c.conn.flushPending()
	m := c.pop(t)
	if m.typ != wsBinary {
		t.Fatalf("flushed message type = %v, want binary", m.typ)
	}
	p, err := protocol.DecodeBinary(m.data)
	if err != nil {
		t.Fatalf("decode merged frame: %v", err)
	}
	if p.Kind != protocol.KindDelta {
		t.Fatalf("merged frame kind = %v, want KindDelta", p.Kind)
	}
	if p.Ref != ref {
		t.Fatalf("merged frame ref = %q, want %q", p.Ref, ref)
	}
	if string(p.Data) != string(want) {
		t.Fatalf("merged frame data != concat\n got %q\nwant %q", p.Data, want)
	}
}

// TestMergePendingDeltaFlushWake: 排空 sendCh 后，pending 有货但无新 delta 时，
// pendingWake 触发 flush（最后一段不丢）。leader msg_be828fdd9d78 的守门测试：
// 塞满→排空→pending 有货→flushPending 由唤醒路径调用→缓冲清空。
func TestMergePendingDeltaFlushWake(t *testing.T) {
	ref := "s1"
	c := newMergeHarness(t)
	c.fillSendCh(t)

	// 背压 delta 进 pending。
	c.conn.sendMirror(mkDeltaFrame(t, ref, "last segment"))

	// 排空 sendCh（writeLoop 消费完，pending 还有货）。
	c.drainAll(t)

	// 无新 delta 到达。pendingWake 应已由 mergePendingDelta 投递（cap1）。
	select {
	case <-c.conn.pendingWake:
		// 唤醒信号在——模拟 writeLoop 消费它 → flushPending。
		c.conn.flushPending()
	default:
		t.Fatalf("pendingWake not signaled after merge into pending")
	}

	// flush 后缓冲清空。
	c.conn.pendingMu.Lock()
	empty := len(c.conn.pending) == 0
	c.conn.pendingMu.Unlock()
	if !empty {
		t.Fatalf("pending not flushed after wake: %+v", c.conn.pending)
	}
	// 合并帧已入队。
	m := c.pop(t)
	if m.typ != wsBinary {
		t.Fatalf("flushed message type = %v, want binary", m.typ)
	}
	p, err := protocol.DecodeBinary(m.data)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if string(p.Data) != "last segment" {
		t.Fatalf("flushed payload = %q, want %q", p.Data, "last segment")
	}
}

// TestMergePendingDeltaIdlePathUnchanged: 队列不满（空闲）时 sendMirror 直接入队，
// 缓冲保持空——合并对空闲路径零影响（零回归判据）。
func TestMergePendingDeltaIdlePathUnchanged(t *testing.T) {
	ref := "s1"
	c := newMergeHarness(t)
	// 不 fill——队列空,fast path。
	c.conn.sendMirror(mkDeltaFrame(t, ref, "idle"))

	select {
	case m := <-c.conn.sendCh:
		if m.typ != wsBinary {
			t.Fatalf("idle path message type = %v, want binary", m.typ)
		}
		p, err := protocol.DecodeBinary(m.data)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		if string(p.Data) != "idle" {
			t.Fatalf("idle path data = %q, want %q", p.Data, "idle")
		}
	default:
		t.Fatalf("idle path did not enqueue the frame")
	}
	if n := len(c.conn.pending); n != 0 {
		t.Fatalf("idle path left pending buffer non-empty: %+v", c.conn.pending)
	}
}

// TestFrameRefParsesRef: frameRef 从真实编码的二进制帧里取出 ref。
func TestFrameRefParsesRef(t *testing.T) {
	ref := "session/alpha"
	f := mkDeltaFrame(t, ref, "payload")
	if got := frameRef(f); got != ref {
		t.Fatalf("frameRef = %q, want %q", got, ref)
	}
}

// TestFrameRefShortFrameSafe: 短帧（<5 字节）frameRef 返回空串，不 panic。
func TestFrameRefShortFrameSafe(t *testing.T) {
	if got := frameRef([]byte{0x01, 0x02, 0x03}); got != "" {
		t.Fatalf("short frame ref = %q, want empty", got)
	}
}

// --- harness ---------------------------------------------------------------

// mergeHarness 用最小的 wsConn + Server 构造，直接驱动 sendMirror/合并路径，
// 不需要真实的 websocket/httptest/relay goroutine。
type mergeHarness struct {
	t    *testing.T
	conn *wsConn
}

func newMergeHarness(t *testing.T) *mergeHarness {
	t.Helper()
	srv := NewServer(Options{Log: discardLogger()})
	ctx, cancel := context.WithCancel(context.Background())
	conn := &wsConn{
		s:           srv,
		id:          1,
		ctx:         ctx,
		cancel:      cancel,
		sendCh:      make(chan wsMsg, 256),
		pendingWake: make(chan struct{}, 1),
		pendingMu:   sync.Mutex{},
	}
	t.Cleanup(func() {
		cancel()
		srv.Close()
	})
	return &mergeHarness{t: t, conn: conn}
}

// pendingPayloadFor 返回缓冲里 ref 条目的 payload（若多个条目则拼接——同 ref
// 连续合并只有一个条目；超限封帧后可能有第二个）。取锁保证一致性。
func pendingPayloadFor(t *testing.T, c *wsConn, ref string) []byte {
	t.Helper()
	c.pendingMu.Lock()
	defer c.pendingMu.Unlock()
	var out []byte
	for _, s := range c.pending {
		if s.ref == ref {
			out = append(out, s.payload...)
		}
	}
	return out
}

// fillSendCh 把 sendCh 填到 cap，让 sendMirror 的 select 必然走 default（合并分支）。
func (h *mergeHarness) fillSendCh(t *testing.T) {
	t.Helper()
	for i := 0; i < cap(h.conn.sendCh); i++ {
		h.conn.sendCh <- wsMsg{typ: wsBinary, data: []byte("prefill")}
	}
}

// drainAll 清空 sendCh（丢掉所有 prefill）。
func (h *mergeHarness) drainAll(t *testing.T) {
	t.Helper()
	for {
		select {
		case <-h.conn.sendCh:
		default:
			return
		}
	}
}

// pop 从 sendCh 取一条（需先保证非空）。
func (h *mergeHarness) pop(t *testing.T) wsMsg {
	t.Helper()
	select {
	case m := <-h.conn.sendCh:
		return m
	default:
		t.Fatalf("sendCh empty")
		return wsMsg{}
	}
}

// mkDeltaFrame 用协议层编码一个真实的 KindDelta 二进制帧（与 relay 相同路径）。
func mkDeltaFrame(t *testing.T, ref, payload string) []byte {
	t.Helper()
	f, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: ref, Data: []byte(payload)})
	if err != nil {
		t.Fatalf("EncodeBinary: %v", err)
	}
	return f
}

// concatPayloads 返回把各 payload 顺序拼接的期望字节（同 ref 合并的正确性基准）。
func concatPayloads(t *testing.T, ref string, payloads ...string) []byte {
	t.Helper()
	return []byte(strings.Join(payloads, ""))
}
