package bridge

// stream.go implements the incremental output stream for a pane using tmux's
// pipe-pane: the server appends every new byte of pane output to a FIFO, and
// a subscriber goroutine relays those bytes over a channel.
//
// Subscribe order is fixed by the knowledge base: the pipe is attached before
// the caller grabs a snapshot, so no output between the two is ever lost (the
// snapshot is a full-screen redraw that stitches the seam).
//
// The stream is a mirror only: cancelling detaches the pipe and drains the
// FIFO; it never touches the pane's runtime state.
//
// One active pipe per pane (tmux's own limit): a new Subscribe replaces any
// previous pipe, whose relay then sees EOF and closes its channel. The API
// layer is expected to hold one subscription per pane at a time.

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// streamBufferBytes is the FIFO read buffer size; sized to a few terminal
// lines so a burst of pane output is drained in few syscalls.
const streamBufferBytes = 65536

// fifoOpenTimeout bounds the relay's wait for a writer to connect to its FIFO.
// A healthy pipe-pane writer connects within milliseconds of pipe-pane -o; the
// bound exists so a subscribe whose pipe never gets a writer (a crashed-pipe
// restart, root-cause chain step 4) fails with a decidable error instead of
// blocking the relay forever and wedging teardown.
const fifoOpenTimeout = 3 * time.Second

// subSeq and bufferSeq produce collision-free FIFO paths and tmux buffer
// names across concurrent subscriptions and injections in one process.
var (
	subSeq    uint64
	bufferSeq uint64
)

// newBufferName returns a unique tmux buffer name for one injection.
func newBufferName() string {
	return fmt.Sprintf("tb-%d-%d", os.Getpid(), atomic.AddUint64(&bufferSeq, 1))
}

// newFIFOPath returns a unique FIFO path for one subscription: process id +
// per-process counter, so re-subscribing the same pane never reuses a path a
// prior relay may still hold open.
func newFIFOPath(p *Pane) string {
	target := strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			return r
		}
		return '_'
	}, p.target)
	return filepath.Join(os.TempDir(), fmt.Sprintf("agentmirror-%d-%d-%s.fifo", os.Getpid(), atomic.AddUint64(&subSeq, 1), target))
}

// Subscribe attaches a pipe-pane incremental stream and returns a channel of
// raw terminal byte chunks plus a cancel function that detaches the pipe and
// closes the channel. A second Subscribe on the same pane is idempotent in
// the tmux sense: it replaces the previous pipe without error.
// @contract
// @pre 目标 pane 存在（pipe-pane -o 前置 attach）；订阅前先 detach 已有 pipe（崩溃残留免疫）
// @post 返回 ch 与 detach；调用 detach 后 pipe 拆除、FIFO 移除、ch 关闭；relay 满缓冲时丢字节（下个快照对账）
// @err 建 FIFO 失败→fmt.Errorf；attach 超时→ErrTmuxTimeout；tmux 失败→ErrServerUnreachable/ErrPaneNotFound；FIFO 无 writer→fifoOpenTimeout 后 decidable error
// @inv none — 纯镜像，只读 pane 输出流
func (p *Pane) Subscribe(ctx context.Context) (<-chan []byte, func(), error) {
	return subscribe(ctx, p.socket, p.target, newFIFOPath(p), p.timeout)
}

// openFIFO opens fifo read-only, waiting for a writer to connect, but never
// blocks forever: it returns the open error as soon as it occurs, and a
// decidable timeout error once fifoOpenTimeout elapses with no writer (the
// crashed-pipe restart case, where pipe-pane -o silently toggled and no cat
// ever attached). The wait is bounded because a relay blocked in a FIFO open
// would make teardown unreachable (root-cause chain step 4).
func openFIFO(fifo string) (*os.File, error) {
	done := make(chan openResult, 1)
	go func() {
		f, err := os.OpenFile(fifo, os.O_RDONLY, 0)
		done <- openResult{f, err}
	}()
	// Unblock the pending open after the deadline: briefly opening the FIFO
	// write end ourselves lets the read-end open complete (the writer's
	// "rendezvous" half), then the goroutine returns and we report the timeout.
	timer := time.NewTimer(fifoOpenTimeout)
	defer timer.Stop()
	select {
	case r := <-done:
		return r.f, r.err
	case <-timer.C:
		if wfd, err := syscall.Open(fifo, syscall.O_WRONLY|syscall.O_NONBLOCK, 0); err == nil {
			syscall.Close(wfd)
		}
		// Drain the goroutine so it never leaks; it is now unblocked.
		select {
		case r := <-done:
			if r.f != nil {
				_ = r.f.Close()
			}
		case <-time.After(100 * time.Millisecond):
		}
		return nil, fmt.Errorf("bridge: fifo %s: no writer connected within %v", fifo, fifoOpenTimeout)
	}
}

// openResult carries the outcome of a single FIFO open attempt across the
// goroutine boundary.
type openResult struct {
	f   *os.File
	err error
}

// subscribe wires pipe-pane to a FIFO and relays bytes to a channel.
func subscribe(ctx context.Context, socket, target, fifo string, timeout time.Duration) (<-chan []byte, func(), error) {
	// Remove a stale FIFO left by a crashed run; it is recreated below.
	if _, err := os.Stat(fifo); err == nil {
		_ = os.Remove(fifo)
	}
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		return nil, nil, fmt.Errorf("bridge: create fifo %s: %w", fifo, err)
	}

	// Detach any existing pipe BEFORE attaching ours. When a prior daemon was
	// killed, its pipe-pane cat is left attached to the pane holding the old
	// FIFO; a bare pipe-pane -o then sees the pane already piped and silently
	// toggles it off (tmux semantics), so our new FIFO would never get a
	// writer and the relay would block forever (root-cause chain step 2-3).
	// pipe-pane with no command is a detach and is idempotent: it returns rc=0
	// even when no pipe is attached, and it makes the stale cat see EOF and
	// exit. Treat "crash residue" as the normal input (004 philosophy).
	_, _ = runTmux(context.Background(), socket, timeout, "pipe-pane", "-t", target)

	// Attach the pipe before the snapshot so no output can slip between
	// this call and the snapshot the caller takes next.
	cmd, stderr, derivedCtx, cancel := newTmuxCommand(ctx, socket, timeout, "pipe-pane", "-o", "-t", target, "cat >> "+shellQuote(fifo))
	if err := cmd.Run(); err != nil {
		cancel()
		_ = os.Remove(fifo)
		if derivedCtx.Err() != nil {
			return nil, nil, ErrTmuxTimeout
		}
		return nil, nil, classifyTmuxError(stderr.String())
	}
	cancel() // the attach command has finished; the deadline timer is done

	// Open the FIFO read end with a hard deadline: the relay must never block
	// forever waiting for a writer, or teardown becomes unreachable. A healthy
	// pipe attaches its writer within milliseconds, so the timeout only fires
	// when the attach silently failed — and it surfaces as a decidable error.
	reader, err := openFIFO(fifo)
	if err != nil {
		return nil, nil, err
	}

	ch := make(chan []byte, 16)
	var cancelOnce sync.Once

	// relay drains the FIFO to the channel until the pipe is detached
	// (writer gone -> EOF) or the pane dies. A slow consumer must never
	// stall the mirror, so a full buffer drops bytes; the next snapshot
	// reconciles.
	relay := func() {
		defer close(ch)
		defer reader.Close()
		buf := make([]byte, streamBufferBytes)
		for {
			n, err := reader.Read(buf)
			if n > 0 {
				select {
				case ch <- append([]byte(nil), buf[:n]...):
				default: // overflow: drop, reconcile on next snapshot
				}
			}
			if err != nil {
				return
			}
		}
	}

	detach := func() {
		cancelOnce.Do(func() {
			// Detach the pipe (no command argument) before the FIFO is
			// removed, so the writer sees EOF and the relay exits.
			_, _ = runTmux(context.Background(), socket, timeout, "pipe-pane", "-t", target)
			_ = os.Remove(fifo)
		})
	}

	go relay()
	return ch, detach, nil
}
