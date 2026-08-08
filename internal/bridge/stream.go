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
func (p *Pane) Subscribe(ctx context.Context) (<-chan []byte, func(), error) {
	return subscribe(ctx, p.socket, p.target, newFIFOPath(p), p.timeout)
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

	// Attach the pipe before the snapshot so no output can slip between
	// this call and the snapshot the caller takes next.
	cmd, stderr := newTmuxCommand(ctx, socket, "pipe-pane", "-o", "-t", target, "cat >> "+shellQuote(fifo))
	if err := cmd.Run(); err != nil {
		_ = os.Remove(fifo)
		if ctx.Err() != nil {
			return nil, nil, ErrTmuxTimeout
		}
		return nil, nil, classifyTmuxError(stderr.String())
	}

	ch := make(chan []byte, 16)
	var cancelOnce sync.Once

	// relay drains the FIFO to the channel until the pipe is detached
	// (writer gone -> EOF) or the pane dies. A slow consumer must never
	// stall the mirror, so a full buffer drops bytes; the next snapshot
	// reconciles.
	relay := func() {
		defer close(ch)
		reader, err := os.OpenFile(fifo, os.O_RDONLY, 0)
		if err != nil {
			return
		}
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

	cancel := func() {
		cancelOnce.Do(func() {
			// Detach the pipe (no command argument) before the FIFO is
			// removed, so the writer sees EOF and the relay exits.
			_, _ = runTmux(context.Background(), socket, timeout, "pipe-pane", "-t", target)
			_ = os.Remove(fifo)
		})
	}

	go relay()
	return ch, cancel, nil
}
