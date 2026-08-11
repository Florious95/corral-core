// Single-instance guard for the agentmirrord daemon (taskbook
// #fix-daemon-idle-cpu: orphan instances silently coexisted and each burned
// ~17.5% CPU). The guard is an advisory flock on <stateDir>/agentmirrord.pid:
//
//   - the kernel releases the flock when the process dies, so a stale pidfile
//     (contents are advisory) never blocks a restart and there is no pid-reuse
//     window to misjudge — flock is the authority, not kill -0;
//   - a second launch that cannot acquire the lock fails loudly with the
//     pidfile path, instead of silently running as another orphan;
//   - the pidfile sits next to the pairing token (same per-user config root),
//     so a host runs at most one daemon and all daemon state lives in one tree.

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/agentmirror/agentmirror/internal/pairing"
)

// acquirePidfile acquires the single-instance flock for name inside dir and,
// on success, writes the current pid into it. It returns the pidfile path and
// a release function that unlocks and closes it (idempotent). A second
// instance holding the lock yields an error naming the conflicting pidfile, so
// the operator knows which instance is alive and where to look.
// @contract
// @pre dir 可创建（不存在则建，0700）；name 非空
// @post 成功时 flock 被持有且 pid 已写入；release 解锁并关闭、幂等
// @err 二启持锁冲突报 "another agentmirrord instance is already running" 并含 pidfile 路径；mkdir/open/flock/truncate/write 任一失败均包装且含路径
// @inv flock 是权威锁，pidfile 内容仅为提示（陈旧 pid 不阻塞重启）
func acquirePidfile(dir, name string) (string, func(), error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", nil, fmt.Errorf("state dir: %w", err)
	}
	path := filepath.Join(dir, name+".pid")

	// O_CREATE|O_TRUNC each acquire: the pid contents are advisory and are
	// rewritten below; flock is what serializes instances.
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return "", nil, fmt.Errorf("open pidfile %s: %w", path, err)
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = f.Close()
		if err == syscall.EWOULDBLOCK {
			return "", nil, fmt.Errorf("another agentmirrord instance is already running (lock held: %s)", path)
		}
		return "", nil, fmt.Errorf("flock pidfile %s: %w", path, err)
	}

	// Holding the lock: write our pid (a reader can see who owns it, though
	// flock remains the source of truth for liveness).
	if err := f.Truncate(0); err != nil {
		_ = f.Close()
		return "", nil, fmt.Errorf("truncate pidfile %s: %w", path, err)
	}
	if _, err := f.WriteString(fmt.Sprintf("%d\n", os.Getpid())); err != nil {
		_ = f.Close()
		return "", nil, fmt.Errorf("write pidfile %s: %w", path, err)
	}

	release := func() {
		// Unlock and close; the lock dies with the fd, so a released instance
		// lets the next launch in. Best-effort: the daemon is exiting.
		_ = syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
		_ = f.Close()
	}
	return path, release, nil
}

// resolveStateDir returns the effective state directory: an explicit override
// (flag/env AGENTMIRROR_STATE_DIR, or test injection) wins; otherwise the
// pairing token dir — the shared per-user agentmirror config root, so the
// pidfile and the token live in one tree.
func resolveStateDir(override string) (string, error) {
	if strings.TrimSpace(override) != "" {
		return override, nil
	}
	return pairing.TokenDir()
}
