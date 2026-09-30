//go:build c1_backpressure_merge

// 归档说明（关卡1 halt 后，leader msg_cb13c80e8b80 裁定 build tag 隔离、tag 名以
// c1_backpressure_merge 为准，本文件归 w-c1-test 独占）：
//
// 这是 C1（delta 背压合并）的**验收红测**。C1 已在**关卡1 halt**：w-c1-probe 真实
// 链路实测 sendCh(cap 256) 永不满——LLM 流式 queue_peak=1、极端本地背压也只到 2，
// 合并永不触发。中间状态可见的真因在慢链渲染侧（分散到达的 delta 按到达时刻各渲染
// 一次），与队列压力无关。因此本文件在**无 C1 实现的当前 HEAD 上按设计就是红的——
// 红了才对**（满队列丢帧 → 客户端字节缺失）。
//
// 为什么红（先红后绿契约）：本文件只用未实现/实现两侧都存在的稳定接口
// （NewServer / serveConn / sendMirror / writeLoop / EncodeBinary / DecodeBinary /
// wsMsg）。未实现合并 → 满队列丢帧 → 字节流缺失（红）；有合并实现 → 满队列并入
// 缓冲 → 字节流逐字节等价（绿）。
//
// build tag 使默认 `go test ./...` 排除本文件（server 面绿，满足 leader 铁律「全绿」），
// 不阻塞常规 CI。未来重启 C1 时用下面的命令显式运行验收：
//
//	cd server && env -u TEAM_AGENT_* go test -tags c1_backpressure_merge ./internal/api/ -run TestDeltaMerge -v
//
// 预期结果（当前 HEAD，无 C1 实现，2026-08-14 实跑）：**三条红、一条绿**——
//	红 TestDeltaMergeClientBytesEquivalent：1577472B 应逐字节等价，实收 1051648B（丢帧）
//	红 TestDeltaMergeWireCap1MiB：3016748B 应完整到达，实收 1050368B（丢帧）
//	红 TestDeltaMergeRefIsolationOnWire：双 ref 各自流应零丢失，实收 1050368B（beta 流丢）
//	绿 TestDeltaMergeIdlePathUnchanged：队列不满零回归，帧数==生产数（合并不触发）
// C1 实现后全部转绿。详见 docs/c1-delta-backpressure-merge-impl.md §八。
package api

// delta_merge_scenario_test.go — C1 关卡 2 场景红测（w-c1-test 席位）。
//
// 任务 perf-delta-backpressure-merge：delta 背压合并。docs/c1-brief.md §〇 第一关
// （sendCh 会不会满）由探针席证伪；本文件是**场景红测**，与队列满不满无关，
// 随时可写可跑——它从客户端视角钉死合并的语义契约。
//
// 与 ws_conn_merge_test.go（实现侧）分工：那份直接驱动 mergePendingDelta/
// flushPending 验证合并算法本身；本文件走**真实链路**——真实 serveConn +
// writeLoop + 真实 socket 传输 + 真实 sendMirror/队列，断言**客户端观察到的字节流**。
// 这正是关卡 2 判据原文：「合并前后客户端收到的字节流逐字节相同，只是分帧不同。」
//
// 慢链怎么造（关键设计）：macOS loopback 的 TCP 接收窗会被内核自动调大
// （autotuning），「peer 完全沉默」反而让 writer 不断被放开、sendCh 到不了满。
// 所以这里不追求 writer 阻塞在 socket 上，而是**限定 peer 的读取节奏**（慢链的
// drain 速率）——生产以突发速度灌入，peer 按节奏慢慢读，sendCh 必然积压到满。
// 这正是慢链的真实因果：drain 慢于生产。
//
// 红测纪律（先红后绿）：本文件只用未实现与实现两侧都存在的稳定接口
// （NewServer / serveConn / sendMirror / writeLoop / EncodeBinary / DecodeBinary /
// wsMsg），所以同一文件在未实现合并的 HEAD 上编译且**红**（满队列丢帧 →
// 客户端字节流缺失），在合并实现上编译且**绿**（满队列并入缓冲 → 字节流逐字节等价）。
//
// 判定（关卡 2 + 零回归判据，全部客户端可观察，不依赖仪表字段）：
//   [1] 背压饱和期（peer 慢读，sendCh 打满）后放行，客户端收到的全部 delta 字节
//       == 生产侧全部 chunk 的顺序拼接（逐字节）。—— 合并=零丢失=绿；丢弃=红。
//   [2] 空闲期（peer 快读，队列从不积压）不触发合并：帧数 == 生产数，零回归。
//   [3] 双 ref 背压：各流字节序独立，不跨流拼接（AnsiParser 顺序状态机语义）。
//
// 不引入定时器约束（brief §一）：合并的必须只是「本就在排队的东西」。本文件给
// 行为化守门：放行后全部字节须在远小于 writeTimeout(30s) 的窗口内到达——
// 任何「等 N 秒再 flush」的定时器式实现都会把到达推迟、超窗红掉。

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// backpressureEnv 是真实链路的场景夹具：httptest 起的 API 服务器 + 一条真实
// WebSocket 连接（客户端侧）。服务端 wsConn 直接构造（生产字段），writeLoop 由
// 测试在 reserve 之后显式启动——这是「reserve 期间队列不被消费」的确定性保证。
type backpressureEnv struct {
	t      *testing.T
	srv    *Server
	hsrv   *httptest.Server
	client *websocket.Conn // 客户端侧连接（peer）
	conn   *wsConn         // 服务端 wsConn（生产侧，测试构造）

	// collectCh 是 drain goroutine 交给 collectDeltas 的帧流。
	collectCh chan wsMsg
	// lastByte 记录最近一次收到字节的时刻（collectDeltas 的空转红判据）。
	lastByte atomic.Int64
	// readErr 记录 drain goroutine 的退出原因（连接意外关闭的取证）。
	readErr string
}

// startBackpressureEnv 启动服务器并拨入一条客户端连接。drain goroutine 从启动
// 即运行：把每条 binary 帧交给 collectCh。
//
// 背压怎么造（关键，多次实测后的最终设计）：macOS loopback 的 TCP 接收窗被内核
// 自动调大，peer 即使完全不读，writer 也能把 ~4MB 以上全部推过去而不阻塞——
// 「socket 背压」在 loopback 上不可靠，任何靠 peer 不读/慢读制造队列积压的方案都
// 实测失败。所以本夹具用**确定性机制**：服务端 wsConn 直接构造（不启动 writeLoop），
// 把 sendCh 预填占位帧到满（reserve），再经 sendMirror 生产——sendMirror 的满队列
// 分支（合并或丢弃）必然被触发；然后启动 writeLoop 放行，客户端收字节。占位帧是
// 客户端字节流的一部分，计入期望。合并只作用于「本就在排队的东西」。
//
// writeLoop 是真实生产代码，正常启动、正常消费 sendCh——只把「何时开始消费」这个
// 时序点由测试控制（reserve 完成后），等价于真实链路中队列刚被填满的瞬间。
//
// 先红后绿：同一文件在未实现合并的 HEAD 上（sendMirror 满队列=丢弃）编译且**红**；
// 在合并实现上编译且**绿**。
func startBackpressureEnv(t *testing.T) *backpressureEnv {
	t.Helper()
	opts := Options{
		Token:        "test-token",
		Discoverer:   scriptedDiscoverer{model: testModel()},
		Log:          discardLogger(),
		ListInterval: 50 * time.Millisecond,
	}
	srv := NewServer(opts)

	// 自定义 handler：/ws 走生产同一路径的 websocket.Accept（InsecureSkipVerify），
	// 把 hijack 出的**服务端** websocket.Conn 交给测试。这是与客户端共享同一 TCP
	// socket 的真实服务端连接（Accept 与生产 handleWS 一致）。
	serverConnCh := make(chan *websocket.Conn, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		serverConnCh <- c
		// hijack 后 handler 返回不影响连接存活
	})
	hsrv := httptest.NewServer(mux)
	t.Cleanup(func() {
		hsrv.Close()
		srv.Close()
	})

	url := "ws" + strings.TrimPrefix(hsrv.URL, "http") + "/ws"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatalf("dial ws: %v", err)
	}
	// 客户端默认 read limit=32768，而合并帧可接近 1MiB——不抬升则 peer.Read 因
	// message too big 报错并关连接（这就是最初「256 帧后停」的根因，已实证）。
	// 真实客户端（安卓 App）没有这个限制（BinaryMaxPayloadLen=1MiB 是协议上限）。
	client.SetReadLimit(1<<20 + 4096)
	t.Cleanup(func() { _ = client.CloseNow() })

	// 拿服务端真实 conn（Accept 完成，与客户端同一 TCP socket 的两端）。
	var serverConn *websocket.Conn
	select {
	case serverConn = <-serverConnCh:
	case <-time.After(5 * time.Second):
		t.Fatal("server-side websocket.Accept never completed")
	}

	// 服务端 wsConn：生产字段，writeLoop 未启动（由 reserve 完成后显式 go 启动）。
	connCtx, connCancel := context.WithCancel(context.Background())
	writeCtx, writeStop := context.WithCancel(context.Background())
	conn := &wsConn{
		s:         srv,
		id:        connSeq.Add(1),
		conn:      serverConn, // 真实服务端连接：writeLoop 写入 → 客户端可读
		ctx:       connCtx,
		cancel:    connCancel,
		writeCtx:  writeCtx,
		writeStop: writeStop,
		subs:      make(map[string]*subscription),
		sendCh:    make(chan wsMsg, 256),
	}
	t.Cleanup(func() {
		writeStop()
		connCancel()
	})

	env := &backpressureEnv{t: t, srv: srv, hsrv: hsrv, client: client, conn: conn}
	env.collectCh = make(chan wsMsg, 512)

	// drain goroutine：唯一持有 peer 的 Reader。尽快读，把每条二进制帧交给 collectCh。
	go func() {
		for {
			readCtx, cancel2 := context.WithTimeout(context.Background(), 3*time.Second)
			typ, data, err := env.client.Read(readCtx)
			cancel2()
			if err != nil {
				if readCtx.Err() == context.DeadlineExceeded {
					continue // 超时心跳；连接仍活
				}
				env.readErr = fmt.Sprintf("peer read err: %v (connection closed)", err)
				return // 连接关闭
			}
			env.lastByte.Store(time.Now().UnixNano())
			select {
			case env.collectCh <- wsMsg{typ: typ, data: data}:
			default:
			}
		}
	}()

	return env
}

// startWriter 启动服务端 writeLoop（真实生产代码），开始消费 sendCh 并写到 peer。
func (e *backpressureEnv) startWriter() {
	go e.conn.writeLoop()
}

// reserveQueue 把 sendCh 直接预填到 cap（不经过 sendMirror），使后续所有
// sendMirror 必然走满队列分支。此时 writeLoop 尚未启动，队列不会被消费——填满是
// 确定性的。占位帧是客户端字节流的一部分，必须被计入期望（先生产先到达）。
// 返回占位帧的 payload 顺序（序号 0..cap-1，经 chunk(i) 生成）。
func (e *backpressureEnv) reserveQueue(ref string, chunk func(i int) []byte) [][]byte {
	e.t.Helper()
	var reserved [][]byte
	for i := 0; i < cap(e.conn.sendCh); i++ {
		c := chunk(i)
		frame, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: ref, Data: c})
		if err != nil {
			e.t.Fatalf("EncodeBinary: %v", err)
		}
		e.conn.sendCh <- wsMsg{typ: wsBinary, data: frame}
		reserved = append(reserved, c)
	}
	if len(e.conn.sendCh) != cap(e.conn.sendCh) {
		e.t.Fatalf("reserve did not fill queue: len=%d cap=%d", len(e.conn.sendCh), cap(e.conn.sendCh))
	}
	return reserved
}

// produceDeltasUnderBackpressure 经 sendMirror 生产 total 个 delta 帧（ref 自选，
// chunk(i) 给定 payload）。sendCh 已被 reserveQueue 预填满，所以这 total 帧里有
// cap 个把占位帧换出（走正常入队）、其余必然走满队列分支（合并或丢弃）。
// 返回生产帧的 payload 顺序（不含占位帧；占位帧已在期望前缀里）。
func (e *backpressureEnv) produceDeltasUnderBackpressure(ref string, total int, chunk func(i int) []byte) [][]byte {
	e.t.Helper()
	payloads := make([][]byte, 0, total)
	for i := 0; i < total; i++ {
		c := chunk(i)
		frame, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: ref, Data: c})
		if err != nil {
			e.t.Fatalf("EncodeBinary: %v", err)
		}
		e.conn.sendMirror(frame)
		payloads = append(payloads, c)
	}
	return payloads
}

// collectDeltas 收集该连接上全部 delta 帧的 payload（按 ref 分组）。totalWant 是
// 期望总字节数（各 ref 之和）。返回 (各 ref 字节流拼接, 收到帧数)。
//
// 失败判定：超时未收全 → Fatal；peer 静默 >2s 而字节仍不足 → Fatal
// （这正是「满队列丢帧」的客户端可观察信号——HEAD 上红得快）。
// 定时器式实现的守门：全部字节必须在远小于 writeTimeout(30s) 的窗口内到达，
// 任何人为延迟都会让本函数超窗红掉。
func (e *backpressureEnv) collectDeltas(totalWant int) (map[string][]byte, int) {
	e.t.Helper()
	got := map[string][]byte{}
	frames := 0
	have := 0
	overall := time.Now().Add(15 * time.Second)
	for time.Now().Before(overall) {
		select {
		case d, ok := <-e.collectCh:
			if !ok {
				if have >= totalWant {
					return got, frames
				}
				e.t.Fatalf("collect channel closed with only %d/%d bytes (%d frames)", have, totalWant, frames)
			}
			if d.typ != wsBinary {
				continue // 防御：客户端未 auth，不应有控制帧；有则跳过
			}
			p, err := protocol.DecodeBinary(d.data)
			if err != nil {
				e.t.Fatalf("decode binary: %v", err)
			}
			if p.Kind != protocol.KindDelta {
				continue
			}
			// 协议单帧上限守门：任何到达客户端的 delta 帧 payload 超过 1MiB 都是
			// 合并违约（brief 约束「合并后单帧 ≤1MiB」），任何场景测试都必须拦住。
			// 用协议常量 BinaryMaxPayloadLen（未实现/实现两侧都存在），不引用合并
			// 实现私有的 maxMergedDeltaBytes——保证本文件在两侧都能编译（先红后绿）。
			if len(p.Data) > protocol.BinaryMaxPayloadLen {
				e.t.Fatalf("delta frame payload %d bytes exceeds 1MiB wire cap (merge sealed too late)", len(p.Data))
			}
			frames++
			got[p.Ref] = append(got[p.Ref], p.Data...)
			have += len(p.Data)
			if have >= totalWant {
				return got, frames
			}
		case <-time.After(50 * time.Millisecond):
			if have > 0 && time.Since(time.Unix(0, e.lastByte.Load())) > 2*time.Second {
				e.t.Fatalf("peer quiet >2s with only %d/%d bytes (%d frames): frames were dropped, byte stream incomplete", have, totalWant, frames)
			}
		}
	}
	e.t.Fatalf("collect timed out: got %d/%d bytes in %d frames", have, totalWant, frames)
	return nil, 0
}

// concatBytes 顺序拼接若干 payload。
func concatBytes(payloads [][]byte) []byte {
	var out []byte
	for _, p := range payloads {
		out = append(out, p...)
	}
	return out
}

// bigIndexedChunk 生成带序号、大小 ~4KiB 的 delta payload：带单调序号（字节流
// 顺序可逐字节验证），大小适中（占位帧+生产帧总量在 collectDeltas 的 15s 窗口内
// 可读完）。占位帧用序号 0..cap-1，生产帧用 cap..cap+total-1，保证占位与生产
// 在字节流里可区分、且顺序唯一。
func bigIndexedChunk(i int) []byte {
	return []byte(fmt.Sprintf("chunk-%04d|%s\n", i, strings.Repeat("X", 4096)))
}

// countedChunk 生成带序号、大小 ~4KiB 的 delta payload（marker 前缀由 prefix
// 给出，序号从 start 起）。用于多 ref 场景：每个 ref 的 marker 带不同前缀，
// 序号互不冲突，字节流顺序唯一可验证。
func countedChunk(prefix string, start, i int) []byte {
	return []byte(fmt.Sprintf("%s%04d|%s\n", prefix, start+i, strings.Repeat("X", 4096)))
}

// chunkSeq 从一段字节流中抽取全部 "chunk-NNNN" 序号的序列。每个 chunk 由
// bigIndexedChunk 注入单调 marker，所以收到的字节流里 marker 的先后顺序就是
// 「生产 chunk 的到达顺序」——重排（order）与丢失（drop）一眼可分。
func chunkSeq(b []byte) []int {
	var out []int
	rest := b
	for {
		i := bytes.Index(rest, []byte("chunk-"))
		if i < 0 {
			break
		}
		rest = rest[i+6:]
		if len(rest) < 4 {
			break
		}
		n := 0
		for _, c := range rest[:4] {
			if c < '0' || c > '9' {
				n = -1
				break
			}
			n = n*10 + int(c-'0')
		}
		if n < 0 {
			continue
		}
		out = append(out, n)
	}
	return out
}

// TestDeltaMergeClientBytesEquivalent — 关卡 2 红测本体。
//
// 场景：peer 慢读（20ms/帧）→ sendCh 打满 → 溢出 overflow 个 delta（走满队列
// 分支：合并或丢弃）→ 放行 peer 快读 → 收字节。
//
// 断言（客户端视角，逐字节）：
//   - 收到的全部 delta 字节 == 生产侧全部 payload 的顺序拼接。合并=零丢失=绿；
//     丢弃=字节缺失=红。
//   - 收到帧数 < 生产数（合并确实把满队列到达的 chunk 拼进了更少帧）。
//   - 全部字节在 << writeTimeout(30s) 内到达（定时器式延迟会超窗红掉）。
func TestDeltaMergeClientBytesEquivalent(t *testing.T) {
	e := startBackpressureEnv(t)

	// 占位帧序号 0..cap-1，生产帧序号 cap..cap+total-1（顺序唯一）。
	const total = 128
	reserved := e.reserveQueue("alpha", bigIndexedChunk)
	payloads := e.produceDeltasUnderBackpressure("alpha", total, func(i int) []byte {
		return bigIndexedChunk(cap(e.conn.sendCh) + i)
	})

	e.startWriter()
	want := concatBytes(append(reserved, payloads...))
	got, frames := e.collectDeltas(len(want))

	if string(got["alpha"]) != string(want) {
		// 逐字节不等 → 用 chunk marker 序列区分「重排」还是「丢失」。
		gotSeq := chunkSeq(got["alpha"])
		wantSeq := chunkSeq(want)
		diffAt := 0
		for diffAt < len(want) && diffAt < len(got["alpha"]) && got["alpha"][diffAt] == want[diffAt] {
			diffAt++
		}
		diag := "no marker divergence"
		if len(gotSeq) != len(wantSeq) {
			diag = fmt.Sprintf("chunk LOSS: got %d chunks, want %d (missing/duplicate)", len(gotSeq), len(wantSeq))
		} else {
			first := 0
			for first < len(gotSeq) && gotSeq[first] == wantSeq[first] {
				first++
			}
			if first < len(gotSeq) {
				diag = fmt.Sprintf("chunk REORDER at marker[%d]: got chunk %d, want chunk %d", first, gotSeq[first], wantSeq[first])
			}
		}
		t.Fatalf("client byte stream != produced chunks (逐字节等价被破坏): "+
			"produced %d bytes, received %d bytes, first diff at %d; "+
			"received %d frames for %d produced chunks; %s",
			len(want), len(got["alpha"]), diffAt, frames, len(payloads), diag)
	}

	if frames >= len(payloads)+len(reserved) {
		t.Fatalf("merge did not reduce frame count: %d frames for %d total chunks (满队列到达的 chunk 没有被合并)", frames, len(payloads)+len(reserved))
	}
	t.Logf("byte stream equivalent: %d bytes in %d frames (was %d chunks) ✓", len(got["alpha"]), frames, len(payloads)+len(reserved))
}

// TestDeltaMergeIdlePathUnchanged — 零回归判据：peer 快读（队列从不积压）时，
// 每个 delta 独立成帧发出、逐帧到达、字节等价，且帧数 == 生产数（不合并）。
// 合并必须只发生在「队列满」时；队列不满行为与现状完全一致。
func TestDeltaMergeIdlePathUnchanged(t *testing.T) {
	e := startBackpressureEnv(t)
	e.startWriter() // 空闲路径：writeLoop 立即启动，队列从不积压

	chunks := []string{"hello ", "world\n", "\x1b[32mgreen\x1b[0m", "\r\n"}
	want := strings.Join(chunks, "")

	for _, ch := range chunks {
		frame, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "alpha", Data: []byte(ch)})
		if err != nil {
			e.t.Fatalf("EncodeBinary: %v", err)
		}
		e.conn.sendMirror(frame)
	}

	var got []byte
	frames := 0
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case d := <-e.collectCh:
			if d.typ != wsBinary {
				continue
			}
			p, err := protocol.DecodeBinary(d.data)
			if err != nil {
				continue
			}
			if p.Kind != protocol.KindDelta {
				continue
			}
			got = append(got, p.Data...)
			frames++
		case <-time.After(50 * time.Millisecond):
		}
		if len(got) >= len(want) {
			break
		}
	}
	if string(got) != string(want) {
		e.t.Fatalf("idle path bytes mismatch: got %q want %q", got, want)
	}
	if frames != len(chunks) {
		e.t.Fatalf("idle path merged deltas: %d frames for %d chunks (合并不应在队列不满时触发，零回归被破坏)", frames, len(chunks))
	}
}

// TestDeltaMergeWireCap1MiB — 协议单帧上限（≤1MiB）的客户端视角守门。
//
// 场景：同一 ref 持续背压，生产总量远超 1MiB（30×~64KiB ≈ 1.9MiB）。若合并把
// 所有 chunk 无脑拼进一个缓冲再发，单帧会超 BinaryMaxPayloadLen(1MiB)、协议违约。
// 正确的合并必须在越限前 seal 出独立帧。
//
// 断言（客户端可见）：
//   - 收到的每个 delta 帧 payload ≤ maxMergedDeltaBytes（1MiB）——线缆上单帧不越限；
//   - 收到字节总量 == 生产总量（字节等价依旧成立，零丢失）。
//
// 对未实现合并的 HEAD：满队列丢帧 → 字节不等 → 红。对合并实现：seal 越限帧 →
// 单帧 ≤1MiB 且字节等价 → 绿。
func TestDeltaMergeWireCap1MiB(t *testing.T) {
	e := startBackpressureEnv(t)

	// 生产帧 payload ~64KiB（bigIndexedChunk 默认 4KiB，这里换大的）。
	bigChunk := func(i int) []byte {
		return []byte(fmt.Sprintf("big-%04d|%s\n", i, strings.Repeat("Z", 65536)))
	}
	const total = 30 // ~1.9MiB 总量，远超 1MiB
	reserved := e.reserveQueue("alpha", func(i int) []byte { return []byte(fmt.Sprintf("R%04d|%s\n", i, strings.Repeat("Q", 4096))) })
	payloads := e.produceDeltasUnderBackpressure("alpha", total, bigChunk)

	e.startWriter()
	want := concatBytes(append(reserved, payloads...))
	got, frames := e.collectDeltas(len(want))

	if string(got["alpha"]) != string(want) {
		t.Fatalf("wire-cap burst byte stream != produced (produced %d, got %d, %d frames): 字节等价被破坏",
			len(want), len(got["alpha"]), frames)
	}
	if frames >= len(payloads)+len(reserved) {
		t.Fatalf("wire-cap burst did not merge: %d frames for %d chunks", frames, len(payloads)+len(reserved))
	}
	t.Logf("wire-cap: %d bytes in %d frames (was %d chunks), all frames ≤1MiB ✓", len(got["alpha"]), frames, len(payloads)+len(reserved))
}

// TestDeltaMergeRefIsolationOnWire — 双 ref 背压：两条流的 delta 同时溢出队列时，
// 各流字节序独立、不跨流拼接（AnsiParser 顺序状态机语义，docs/ts-link-baseline.md
// §语义安全）。合并后各 ref 收到的字节 == 该 ref 生产 payload 的顺序拼接。
func TestDeltaMergeRefIsolationOnWire(t *testing.T) {
	e := startBackpressureEnv(t)
	refA, refB := "alpha", "beta"

	chunkA := func(i int) []byte { return countedChunk("A", 0, i) }
	chunkB := func(i int) []byte { return countedChunk("B", 0, i) }

	payloadsByRef := map[string][][]byte{refA: {}, refB: {}}
	// 用 refA 的占位帧把队列预填满（refA 流前缀）。随后经 sendMirror 交叠生产
	// A/B：A 帧把占位换出、B 帧必然走满队列分支（合并或丢弃）。
	reservedA := e.reserveQueue(refA, chunkA)
	payloadsByRef[refA] = append(payloadsByRef[refA], reservedA...)
	for i := 0; i < 40; i++ {
		for _, ref := range []string{refA, refB} {
			var c []byte
			if ref == refA {
				c = chunkA(i)
			} else {
				c = chunkB(i)
			}
			frame, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: ref, Data: c})
			if err != nil {
				e.t.Fatalf("EncodeBinary: %v", err)
			}
			e.conn.sendMirror(frame)
			payloadsByRef[ref] = append(payloadsByRef[ref], c)
		}
	}

	wantA := concatBytes(payloadsByRef[refA])
	wantB := concatBytes(payloadsByRef[refB])

	e.startWriter()
	got, _ := e.collectDeltas(len(wantA) + len(wantB))

	if string(got[refA]) != string(wantA) {
		t.Fatalf("ref %s byte stream corrupted under backpressure: got %d bytes want %d", refA, len(got[refA]), len(wantA))
	}
	if string(got[refB]) != string(wantB) {
		t.Fatalf("ref %s byte stream corrupted under backpressure: got %d bytes want %d", refB, len(got[refB]), len(wantB))
	}
}
