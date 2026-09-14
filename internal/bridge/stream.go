package bridge

// stream.go implements the incremental output stream for a pane using tmux's
// pipe-pane: the server appends every new byte of pane output to a FIFO, and
// a fan-out goroutine copies those bytes to every live subscriber channel.
//
// Subscribe order is fixed by the knowledge base: the pipe is attached before
// the caller grabs a snapshot, so no output between the two is ever lost (the
// snapshot is a full-screen redraw that stitches the seam).
//
// The stream is a mirror only: cancelling a subscriber drops that ref; the
// pane-level pipe is detached only when the last subscriber leaves, and only
// if this process still owns the FIFO it attached.
//
// One active pipe-pane per pane (tmux's own limit). N subscribers share that
// pipe via an in-process hub keyed by socket+target, so a second Subscribe
// must not detach the first, and the first's detach must not tear down a
// pipe it no longer owns.

import (
	"context"
	"errors"
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

// ErrSubscriberOverflow marks raw-byte loss in one subscriber's private queue.
// The owning connection must reconnect and subscribe to a fresh snapshot.
var ErrSubscriberOverflow = errors.New("bridge: subscriber queue overflow")

type streamSubscriber struct {
	data   chan []byte
	loss   chan error
	closed bool // guarded by sharedPipe.mu
}

// finish publishes the cause before EOF; overflow discards untrustworthy data.
// Caller holds sharedPipe.mu. It never detaches or calls back into the API.
func (sub *streamSubscriber) finish(cause error) {
	if sub.closed {
		return
	}
	sub.closed = true
	if cause != nil {
		sub.loss <- cause
		for len(sub.data) > 0 {
			select {
			case <-sub.data:
			default:
			}
		}
	}
	close(sub.loss)
	close(sub.data)
}

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

// pipeHub is the process-wide fan-out table. Catalog rebuild constructs a
// fresh *Pane on every listing scan; the hub is keyed by socket+target so
// those handles still share one pipe-pane.
var (
	hubMu sync.Mutex
	hub   = map[string]*sharedPipe{}
)

func hubKey(socket, target string) string {
	return socket + "\x00" + target
}

func getSharedPipe(socket, target string, timeout time.Duration) *sharedPipe {
	key := hubKey(socket, target)
	hubMu.Lock()
	defer hubMu.Unlock()
	if s, ok := hub[key]; ok {
		return s
	}
	s := &sharedPipe{
		key:     key,
		socket:  socket,
		target:  target,
		timeout: timeout,
		subs:    make(map[uint64]*streamSubscriber),
		dead:    true,
	}
	hub[key] = s
	return s
}

// sharedPipe is one pane's pipe-pane plus the subscriber set that reads it.
type sharedPipe struct {
	key     string
	socket  string
	target  string
	timeout time.Duration

	attachMu   sync.Mutex // serializes attach vs last-ref teardown
	mu         sync.Mutex
	fifo       string
	gen        uint64
	reader     *os.File
	subs       map[uint64]*streamSubscriber
	nextID     uint64
	refs       int
	dead       bool
	fanoutDone chan struct{}
}

// newBufferName returns a unique tmux buffer name for one injection.
func newBufferName() string {
	return fmt.Sprintf("tb-%d-%d", os.Getpid(), atomic.AddUint64(&bufferSeq, 1))
}

// newFIFOPath returns a unique FIFO path for one pipe generation: process id +
// per-process counter, so re-attaching the same pane never reuses a path a
// prior fan-out may still hold open.
func newFIFOPath(p *Pane) string {
	target := strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			return r
		}
		return '_'
	}, p.target)
	return filepath.Join(os.TempDir(), fmt.Sprintf("agentmirror-%d-%d-%s.fifo", os.Getpid(), atomic.AddUint64(&subSeq, 1), target))
}

// Subscribe attaches (or joins) a pipe-pane incremental stream and returns a
// channel of raw terminal byte chunks plus a cancel function that drops this
// subscriber. The pane-level pipe is created on the first live subscriber and
// torn down on the last, with an ownership check against the FIFO we attached.
// A second Subscribe on the same pane shares the pipe; it does not replace it.
// @contract
// @pre 目标 pane 存在（pipe-pane -o 前置 attach）；仅在 0→1 / dead→live 时 detach 已有 pipe（崩溃残留免疫）
// @post 返回 ch 与 detach；detach 只减本订阅者引用计数；计数归零且仍持有本 FIFO 才拆 pipe；慢订阅者独立队列满则一次失效并停止输出
// @err 建 FIFO 失败→fmt.Errorf；attach 超时→ErrTmuxTimeout；tmux 失败→ErrServerUnreachable/ErrPaneNotFound；FIFO 无 writer→fifoOpenTimeout 后 decidable error
// @inv none — 纯镜像，只读 pane 输出流
func (p *Pane) Subscribe(ctx context.Context) (<-chan []byte, func(), error) {
	return subscribe(ctx, p.socket, p.target, p.timeout)
}

// SubscribeWithLoss adds a buffered one-shot overflow cause to Subscribe.
// Normal pipe displacement closes loss without a cause, preserving its EOF semantics.
func (p *Pane) SubscribeWithLoss(ctx context.Context) (<-chan []byte, <-chan error, func(), error) {
	return getSharedPipe(p.socket, p.target, p.timeout).addWithLoss(ctx)
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

// subscribe joins the process-wide hub for this pane.
func subscribe(ctx context.Context, socket, target string, timeout time.Duration) (<-chan []byte, func(), error) {
	return getSharedPipe(socket, target, timeout).add(ctx)
}

func (s *sharedPipe) add(ctx context.Context) (<-chan []byte, func(), error) {
	ch, _, detach, err := s.addWithLoss(ctx)
	return ch, detach, err
}

func (s *sharedPipe) addWithLoss(ctx context.Context) (<-chan []byte, <-chan error, func(), error) {
	s.attachMu.Lock()
	defer s.attachMu.Unlock()

	s.mu.Lock()
	needAttach := s.fifo == "" || s.dead
	s.mu.Unlock()
	if needAttach {
		if err := s.attach(ctx); err != nil {
			return nil, nil, nil, err
		}
	}

	s.mu.Lock()
	id := s.nextID
	s.nextID++
	sub := &streamSubscriber{data: make(chan []byte, 16), loss: make(chan error, 1)}
	s.subs[id] = sub
	s.refs++
	s.mu.Unlock()

	var once sync.Once
	return sub.data, sub.loss, func() { once.Do(func() { s.drop(id) }) }, nil
}

// attach creates a new FIFO, detaches crash-residue pipe-pane, hangs ours,
// and starts the fan-out reader. Caller holds attachMu.
func (s *sharedPipe) attach(ctx context.Context) error {
	s.mu.Lock()
	oldDone := s.fanoutDone
	oldReader := s.reader
	oldFifo := s.fifo
	s.mu.Unlock()

	if oldReader != nil {
		_ = oldReader.Close()
	}
	if oldFifo != "" {
		_ = os.Remove(oldFifo)
	}
	if oldDone != nil {
		select {
		case <-oldDone:
		case <-time.After(fifoOpenTimeout + time.Second):
			return fmt.Errorf("bridge: previous pipe fanout stuck")
		}
	}

	fifo := newFIFOPath(&Pane{target: s.target})
	if _, err := os.Stat(fifo); err == nil {
		_ = os.Remove(fifo)
	}
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		return fmt.Errorf("bridge: create fifo %s: %w", fifo, err)
	}

	// Detach any existing pipe BEFORE attaching ours. When a prior daemon was
	// killed, its pipe-pane cat is left attached to the pane holding the old
	// FIFO; a bare pipe-pane -o then sees the pane already piped and silently
	// toggles it off (tmux semantics), so our new FIFO would never get a
	// writer and the relay would block forever (root-cause chain step 2-3).
	// This runs only on 0→1 / dead→live attach, never when live subscribers
	// already share a healthy pipe.
	_, _ = runTmux(context.Background(), s.socket, s.timeout, "pipe-pane", "-t", s.target)

	cmd, stderr, derivedCtx, cancel := newTmuxCommand(ctx, s.socket, s.timeout, "pipe-pane", "-o", "-t", s.target, "cat >> "+shellQuote(fifo))
	if err := cmd.Run(); err != nil {
		cancel()
		_ = os.Remove(fifo)
		if derivedCtx.Err() != nil {
			return ErrTmuxTimeout
		}
		return classifyTmuxError(stderr.String())
	}
	cancel()

	reader, err := openFIFO(fifo)
	if err != nil {
		_, _ = runTmux(context.Background(), s.socket, s.timeout, "pipe-pane", "-t", s.target)
		_ = os.Remove(fifo)
		return err
	}

	done := make(chan struct{})
	s.mu.Lock()
	s.fifo = fifo
	s.reader = reader
	s.dead = false
	s.gen++
	gen := s.gen
	s.fanoutDone = done
	s.mu.Unlock()
	go s.fanout(reader, done, gen)
	return nil
}

func (s *sharedPipe) fanout(reader *os.File, done chan struct{}, gen uint64) {
	defer close(done)
	defer reader.Close()
	buf := make([]byte, streamBufferBytes)
	for {
		n, err := reader.Read(buf)
		if n > 0 {
			chunk := append([]byte(nil), buf[:n]...)
			s.mu.Lock()
			if s.gen != gen {
				s.mu.Unlock()
				return
			}
			for id, sub := range s.subs {
				if sub.closed {
					continue
				}
				select {
				case sub.data <- chunk:
				default:
					// The queue overflow makes this subscriber's stream
					// unrecoverable. Remove it while holding the same lock that
					// serializes fanout and detach, then close both channels so
					// the relay observes the loss boundary immediately. Do not
					// call drop here: it would re-enter s.mu and deadlock.
					delete(s.subs, id)
					s.refs--
					sub.finish(ErrSubscriberOverflow)
				}
			}
			last := s.refs == 0
			fifo, live := s.fifo, !s.dead
			s.mu.Unlock()
			if last {
				// fanout owns the active reader and cannot synchronously wait
				// for its own done signal. Return after scheduling the normal
				// owner-checked teardown; a concurrent new subscriber may
				// safely keep this generation alive.
				go s.teardownIfOwner(fifo, gen, live)
				return
			}
		}
		if err != nil {
			s.mu.Lock()
			if s.gen == gen {
				s.dead = true
				for _, sub := range s.subs {
					sub.finish(nil)
				}
			}
			s.mu.Unlock()
			return
		}
	}
}

func (s *sharedPipe) drop(id uint64) {
	s.mu.Lock()
	if sub, ok := s.subs[id]; ok {
		delete(s.subs, id)
		sub.finish(nil)
		s.refs--
	}
	last := s.refs == 0
	live := !s.dead
	fifo := s.fifo
	gen := s.gen
	s.mu.Unlock()
	if last {
		s.teardownIfOwner(fifo, gen, live)
	}
}

// teardownIfOwner detaches pipe-pane only when this generation still owns the
// FIFO we attached and the pipe did not already die (stolen / pane gone).
// A dead pipe is not ours to yank: bare pipe-pane would injure whoever
// replaced us.
func (s *sharedPipe) teardownIfOwner(fifo string, gen uint64, live bool) {
	s.attachMu.Lock()
	defer s.attachMu.Unlock()

	s.mu.Lock()
	if s.refs > 0 || s.gen != gen || s.fifo != fifo {
		s.mu.Unlock()
		return
	}
	if s.dead {
		live = false
	}
	socket, target, timeout := s.socket, s.target, s.timeout
	reader := s.reader
	done := s.fanoutDone
	s.mu.Unlock()

	if live && fifo != "" {
		_, _ = runTmux(context.Background(), socket, timeout, "pipe-pane", "-t", target)
	}
	if reader != nil {
		_ = reader.Close()
	}
	if fifo != "" {
		_ = os.Remove(fifo)
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	}

	s.mu.Lock()
	if s.gen == gen && s.fifo == fifo {
		s.fifo = ""
		s.reader = nil
		s.dead = true
		s.fanoutDone = nil
	}
	empty := s.refs == 0 && s.fifo == ""
	s.mu.Unlock()

	if !empty {
		return
	}
	hubMu.Lock()
	if cur := hub[s.key]; cur == s {
		s.mu.Lock()
		still := s.refs == 0 && s.fifo == ""
		s.mu.Unlock()
		if still {
			delete(hub, s.key)
		}
	}
	hubMu.Unlock()
}
