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

// ErrSubscriberOverflow means a subscriber's private relay queue filled before
// it could consume the pane's raw bytes. The stream cannot reconstruct bytes
// after this point; API callers must terminate the owning connection so the
// client can replay a fresh snapshot.
var ErrSubscriberOverflow = errors.New("bridge: subscriber queue overflow")

// ErrAttachUnstable means a pane pipe repeatedly died before a subscriber
// could register. The bound prevents a live context from creating an
// unbounded attach storm.
var ErrAttachUnstable = errors.New("bridge: subscriber attach unstable")

const maxSubscribeAttachAttempts = 2

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
		key:        key,
		socket:     socket,
		target:     target,
		timeout:    timeout,
		subs:       make(map[uint64]chan []byte),
		losses:     make(map[uint64]chan error),
		lossClosed: make(map[uint64]bool),
		failed:     make(map[uint64]bool),
		dead:       true,
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
	subs       map[uint64]chan []byte
	losses     map[uint64]chan error
	lossClosed map[uint64]bool
	failed     map[uint64]bool
	nextID     uint64
	refs       int
	dead       bool
	fanoutDone chan struct{}

	// attachFn is nil in production; tests may replace the tmux attach seam
	// with a deterministic generation script.
	attachFn func(context.Context) error
	// beforeDataClose is nil in production; tests use it to pause the exact
	// terminal transition and assert cause publication ordering.
	beforeDataClose func(uint64)
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
// @post 返回 ch 与 detach；detach 只减本订阅者引用计数；计数归零且仍持有本 FIFO 才拆 pipe；慢订阅者独立队列满则发出一次 loss 并停止该订阅者
// @err 建 FIFO 失败→fmt.Errorf；attach 超时→ErrTmuxTimeout；tmux 失败→ErrServerUnreachable/ErrPaneNotFound；FIFO 无 writer→fifoOpenTimeout 后 decidable error
// @inv none — 纯镜像，只读 pane 输出流
func (p *Pane) Subscribe(ctx context.Context) (<-chan []byte, func(), error) {
	ch, _, detach, err := subscribeWithLoss(ctx, p.socket, p.target, p.timeout)
	return ch, detach, err
}

// SubscribeWithLoss is Subscribe plus a one-shot terminal signal for a
// subscriber-local queue overflow. The loss channel is buffered so a loss
// that happens before the API relay starts cannot be missed.
// @contract
// @pre 目标 pane 存在（pipe-pane -o 前置 attach）
// @post loss receives ErrSubscriberOverflow at most once, then closes; normal
// pipe displacement closes both channels without an overflow error
// @err same attach/FIFO errors as Subscribe
// @inv another subscriber's bytes and pipe generation are unaffected
func (p *Pane) SubscribeWithLoss(ctx context.Context) (<-chan []byte, <-chan error, func(), error) {
	return subscribeWithLoss(ctx, p.socket, p.target, p.timeout)
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
	ch, _, detach, err := subscribeWithLoss(ctx, socket, target, timeout)
	return ch, detach, err
}

func subscribeWithLoss(ctx context.Context, socket, target string, timeout time.Duration) (<-chan []byte, <-chan error, func(), error) {
	return getSharedPipe(socket, target, timeout).add(ctx)
}

func (s *sharedPipe) attachForSubscribe(ctx context.Context) error {
	if s.attachFn != nil {
		return s.attachFn(ctx)
	}
	return s.attach(ctx)
}

func (s *sharedPipe) clearDeadGeneration() {
	s.mu.Lock()
	if !s.dead {
		s.mu.Unlock()
		return
	}
	reader, fifo, done := s.reader, s.fifo, s.fanoutDone
	s.reader = nil
	s.fifo = ""
	s.fanoutDone = nil
	s.mu.Unlock()
	if reader != nil {
		_ = reader.Close()
	}
	if fifo != "" {
		_ = os.Remove(fifo)
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(fifoOpenTimeout + time.Second):
		}
	}
}

func (s *sharedPipe) add(ctx context.Context) (<-chan []byte, <-chan error, func(), error) {
	s.attachMu.Lock()
	defer s.attachMu.Unlock()

	attempts := 0
	for {
		s.mu.Lock()
		needAttach := s.fifo == "" || s.dead
		s.mu.Unlock()
		if needAttach {
			if attempts >= maxSubscribeAttachAttempts {
				s.clearDeadGeneration()
				return nil, nil, nil, fmt.Errorf("%w: attempts=%d", ErrAttachUnstable, attempts)
			}
			attempts++
			if err := s.attachForSubscribe(ctx); err != nil {
				s.clearDeadGeneration()
				return nil, nil, nil, err
			}
		}

		s.mu.Lock()
		// The fanout reader can discover a dead pipe between the initial
		// check and registration. Never add a subscriber to that generation;
		// retry the attach while attachMu still serializes ownership.
		if s.fifo == "" || s.dead {
			s.mu.Unlock()
			if attempts >= maxSubscribeAttachAttempts {
				s.clearDeadGeneration()
				return nil, nil, nil, fmt.Errorf("%w: attempts=%d", ErrAttachUnstable, attempts)
			}
			continue
		}
		if s.losses == nil {
			s.losses = make(map[uint64]chan error)
		}
		if s.lossClosed == nil {
			s.lossClosed = make(map[uint64]bool)
		}
		if s.failed == nil {
			s.failed = make(map[uint64]bool)
		}
		id := s.nextID
		s.nextID++
		ch := make(chan []byte, 16)
		loss := make(chan error, 1)
		s.subs[id] = ch
		s.losses[id] = loss
		s.lossClosed[id] = false
		s.failed[id] = false
		s.refs++
		s.mu.Unlock()

		var once sync.Once
		return ch, loss, func() { once.Do(func() { s.drop(id) }) }, nil
	}
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
			if s.failed == nil {
				s.failed = make(map[uint64]bool)
			}
			if s.losses == nil {
				s.losses = make(map[uint64]chan error)
			}
			if s.lossClosed == nil {
				s.lossClosed = make(map[uint64]bool)
			}
			for id, ch := range s.subs {
				if s.failed[id] {
					continue
				}
				select {
				case ch <- chunk:
				default:
					// Raw terminal bytes are stateful; silently dropping one
					// chunk can never be reconciled by later deltas. Mark this
					// subscriber failed exactly once, discard queued stale bytes,
					// and notify the API outside the data channel.
					s.failed[id] = true
					for {
						select {
						case <-ch:
						default:
							goto drained
						}
					}
				drained:
					// Publish the terminal cause before closing data. Relay EOF
					// therefore has a happens-before cause and cannot misclassify
					// overflow as an ordinary displaced pipe.
					if loss := s.losses[id]; loss != nil {
						select {
						case loss <- ErrSubscriberOverflow:
						default:
						}
						if !s.lossClosed[id] {
							close(loss)
							s.lossClosed[id] = true
						}
					}
					if s.beforeDataClose != nil {
						s.beforeDataClose(id)
					}
					close(ch)
				}
			}
			s.mu.Unlock()
		}
		if err != nil {
			s.mu.Lock()
			if s.gen == gen {
				s.dead = true
				s.refs = 0
				for id, ch := range s.subs {
					delete(s.subs, id)
					if !s.failed[id] {
						close(ch)
					}
					if loss := s.losses[id]; loss != nil && !s.lossClosed[id] {
						close(loss)
						s.lossClosed[id] = true
					}
					// The old generation owns these auxiliary entries even after
					// membership is removed; clear them now so a later generation
					// cannot retain stale channels or failed flags.
					delete(s.losses, id)
					delete(s.lossClosed, id)
					delete(s.failed, id)
				}
			}
			s.mu.Unlock()
			return
		}
	}
}

func (s *sharedPipe) drop(id uint64) {
	s.mu.Lock()
	if ch, ok := s.subs[id]; ok {
		delete(s.subs, id)
		if !s.failed[id] {
			close(ch)
		}
		if loss := s.losses[id]; loss != nil && !s.lossClosed[id] {
			close(loss)
		}
		delete(s.losses, id)
		delete(s.lossClosed, id)
		delete(s.failed, id)
		s.refs--
		if s.refs < 0 {
			s.refs = 0
		}
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
