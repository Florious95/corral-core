package main

// pidfile_test.go — taskbook#fix-daemon-idle-cpu 红测：单实例守卫。
// 契约：同一状态目录下二启必须被拒绝（活实例持有锁）；释放后再次启动成功；
// 残留死 pid 的陈旧 pidfile 不阻塞启动（flock 是权威锁，内核在进程死亡时自动释放，
// 不存在陈旧锁，也就不需要 kill -0 + 进程名核对——pid 复用误判在 flock 下不存在）。

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPidfileAcquireRejectWhileHeld(t *testing.T) {
	dir := t.TempDir()
	pidfile, release, err := acquirePidfile(dir, "agentmirrord")
	if err != nil {
		t.Fatalf("first acquire: %v", err)
	}
	if pidfile == "" {
		t.Fatal("first acquire returned empty pidfile path")
	}
	if release == nil {
		t.Fatal("first acquire returned nil release")
	}

	// 活实例持有锁时二启必须被拒绝，且报错要指名 pidfile 位置（可诊断）。
	if _, _, err := acquirePidfile(dir, "agentmirrord"); err == nil {
		t.Fatal("second acquire must fail while the first instance holds the lock")
	} else if !strings.Contains(err.Error(), "already running") {
		t.Errorf("second-acquire error must name the conflict, got: %v", err)
	}

	release()

	// 释放后新实例可再次获取（一次启动 → 退出 → 再启动）。
	if _, rel2, err := acquirePidfile(dir, "agentmirrord"); err != nil {
		t.Fatalf("acquire after release: %v", err)
	} else {
		rel2()
	}
}

func TestPidfileAcquireOverwritesStalePid(t *testing.T) {
	dir := t.TempDir()
	// 陈旧 pidfile（内容无关紧要；flock 是权威锁，不靠 pid 内容判活）。
	if err := os.WriteFile(filepath.Join(dir, "agentmirrord.pid"), []byte("999999999\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, release, err := acquirePidfile(dir, "agentmirrord"); err != nil {
		t.Fatalf("acquire over stale pidfile: %v", err)
	} else {
		release()
	}
}

func TestPidfileWritesOwnPid(t *testing.T) {
	dir := t.TempDir()
	_, release, err := acquirePidfile(dir, "agentmirrord")
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	b, err := os.ReadFile(filepath.Join(dir, "agentmirrord.pid"))
	if err != nil {
		t.Fatalf("read pidfile: %v", err)
	}
	if got := strings.TrimSpace(string(b)); got == "" {
		t.Fatal("pidfile must contain the daemon pid")
	}
}
