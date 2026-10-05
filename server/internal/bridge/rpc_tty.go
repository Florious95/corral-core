package bridge

// A pane-local tty journal survives daemon SIGKILL. It contains only terminal
// attributes and exact process/tty ownership, never commands or credentials.
// @contract
// @pre foreground native RPC identity and an open descriptor for its tty
// @post the first pre-bridge termios survives crash/reattach; only its owner
// restores it, and a replacement TUI's settings are never overwritten
// @err invalid provenance or an existing live owner refuses attachment
// @inv no wrapper, socket, timer or subprocess is added to the pane

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"runtime"
	"strings"
	"sync"

	"golang.org/x/sys/unix"
)

const rpcTTYOption = "@corral_rpc_tty"

type ttyJournal struct {
	Version       int
	Platform      string
	PID           int
	Started       string
	TTY           string
	Device, Inode uint64
	Original      *unix.Termios
	OwnerPID      int
	OwnerStarted  string
	Lease         string
}

// Serializes a canceled lease's cleanup against immediate same-process
// reattach. Across daemon processes the live owner stamp prevents takeover.
var rpcTTYMu sync.Mutex
var rpcTTYContexts = make(map[string]context.Context)

func (p *Pane) readTTYJournal(ctx context.Context) (*ttyJournal, error) {
	data, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{"+rpcTTYOption+"}")
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(string(data)) == "" {
		return nil, nil
	}
	var record ttyJournal
	if len(data) > 4096 || json.Unmarshal(data, &record) != nil || record.Version != 1 || record.Platform != runtime.GOOS || record.Original == nil || record.Lease == "" || record.PID <= 1 || record.Started == "" || record.OwnerPID <= 1 || record.OwnerStarted == "" || record.TTY == "" {
		return nil, errors.New("RPC tty provenance is invalid; attachment refused")
	}
	return &record, nil
}

func (p *Pane) claimRPCTTY(ctx context.Context, process PiProcess, f *os.File) (func(), error) {
	rpcTTYMu.Lock()
	defer rpcTTYMu.Unlock()
	var stat unix.Stat_t
	if err := unix.Fstat(int(f.Fd()), &stat); err != nil {
		return nil, err
	}
	original, err := ttySettings(int(f.Fd()))
	if err != nil {
		return nil, err
	}
	ownerStamp, err := processStamp(os.Getpid())
	if err != nil {
		return nil, err
	}
	prior, err := p.readTTYJournal(ctx)
	if err != nil {
		return nil, err
	}
	matches := func(record *ttyJournal) bool {
		return record.PID == process.PID && record.Started == process.Started && record.TTY == process.TTY && record.Device == uint64(stat.Dev) && record.Inode == uint64(stat.Ino)
	}
	if prior != nil && matches(prior) {
		stamp, ownerErr := processStamp(prior.OwnerPID)
		if ownerErr != nil && !errors.Is(ownerErr, unix.ESRCH) && !errors.Is(ownerErr, os.ErrNotExist) {
			return nil, errors.New("RPC tty owner cannot be confirmed")
		}
		if ownerErr == nil && stamp == prior.OwnerStarted {
			oldCtx := rpcTTYContexts[prior.Lease]
			if prior.OwnerPID != os.Getpid() || (oldCtx != nil && oldCtx.Err() == nil) {
				return nil, errors.New("RPC tty already has a live bridge owner")
			}
		}
		original = prior.Original
	}
	var nonce [16]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, err
	}
	lease := hex.EncodeToString(nonce[:])
	record := ttyJournal{Version: 1, Platform: runtime.GOOS, PID: process.PID, Started: process.Started, TTY: process.TTY, Device: uint64(stat.Dev), Inode: uint64(stat.Ino), Original: original, OwnerPID: os.Getpid(), OwnerStarted: ownerStamp, Lease: lease}
	data, _ := json.Marshal(record)
	// Persist before changing tty settings. A crash at any subsequent point
	// retains the real original state, not the already-adjusted restart state.
	if _, err := runTmux(ctx, p.socket, p.timeout, "set-option", "-p", "-t", p.target, rpcTTYOption, string(data)); err != nil {
		return nil, err
	}
	if prior != nil {
		delete(rpcTTYContexts, prior.Lease)
	}
	leaseCtx, cancelLease := context.WithCancel(ctx)
	rpcTTYContexts[lease] = leaseCtx
	if err := setRPCInput(int(f.Fd())); err != nil {
		cancelLease()
		delete(rpcTTYContexts, lease)
		if setTTYSettings(int(f.Fd()), original) == nil {
			_, _ = runTmux(ctx, p.socket, p.timeout, "set-option", "-p", "-u", "-t", p.target, rpcTTYOption)
		}
		return nil, err // failed restoration retains recoverable provenance
	}
	var once sync.Once
	restore := func() {
		once.Do(func() {
			rpcTTYMu.Lock()
			defer rpcTTYMu.Unlock()
			defer f.Close()
			defer cancelLease()
			defer delete(rpcTTYContexts, lease)
			checkCtx, cancel := context.WithTimeout(context.Background(), defaultTimeout)
			defer cancel()
			current, err := p.readTTYJournal(checkCtx)
			if err != nil || current == nil || current.Lease != lease {
				return
			}
			now, err := p.NativePi(checkCtx)
			if err != nil {
				return // uncertain identity must retain provenance, not guess
			}
			if now.PID == process.PID && now.Started == process.Started && now.TTY == process.TTY && now.Mode == "rpc" && matches(current) {
				if err := setTTYSettings(int(f.Fd()), current.Original); err != nil {
					return
				}
			}
			// If a different process owns the tty, discard only this old lease;
			// never restore its original settings into the replacement process.
			_, _ = runTmux(checkCtx, p.socket, p.timeout, "set-option", "-p", "-u", "-t", p.target, rpcTTYOption)
		})
	}
	return restore, nil
}
