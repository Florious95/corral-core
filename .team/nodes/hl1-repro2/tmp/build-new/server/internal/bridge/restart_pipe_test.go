package bridge

// restart_pipe_test.go — 红测先行案卷（taskbook#fix-bridge-restart-pipe）。
// 复现 e2e 老化层实证缺陷：daemon 被杀后旧 pipe-pane cat 残留 attach 在 pane，
// 新 subscribe 的 pipe-pane -o 见已有 pipe 即 toggle（不挂新命令），新 FIFO
// 无 writer，relay 永久阻塞在 O_RDONLY open → delta 0 帧、teardown 不可达
// （根因链第 2-4 步）。红测先行：两个场景对当前代码必红，修复后必绿。

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// attachStalePipe 模拟崩溃 daemon 的残留现场（根因链第 2 步）：给 pane 挂一个
// pipe-pane -o cat，并让 cat 真正连上它的 FIFO（非阻塞读端保持打开），形成
// "残留 writer 持有旧 FIFO" 的现场。返回残留读端（保持存活至测试结束）。
func attachStalePipe(t *testing.T, tt *testTMUX, p *Pane) *os.File {
	t.Helper()
	stale := filepath.Join(filepath.Dir(tt.sock), "stale.fifo")
	if err := syscall.Mkfifo(stale, 0o600); err != nil {
		t.Fatalf("mkfifo stale fifo: %v", err)
	}
	if _, err := tt.run("pipe-pane", "-o", "-t", p.target, "cat >> "+shellQuote(stale)); err != nil {
		t.Fatalf("attach stale pipe: %v", err)
	}
	// 非阻塞读端：让残留 cat 的 O_WRONLY open 完成会合，cat 保持存活不退出。
	rd, err := os.OpenFile(stale, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		t.Fatalf("open stale fifo read end: %v", err)
	}
	t.Cleanup(func() { rd.Close() })
	// fail-fast 校验现场成立：pane 上确有 pipe（pane_pipe=1）。
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		out, err := tt.run("display-message", "-p", "-t", p.target, "#{pane_pipe}")
		if err == nil && strings.TrimSpace(out) == "1" {
			return rd
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("stale pipe never attached (pane_pipe != 1)")
	return rd
}

// TestSubscribeRecoversAfterCrashedPipe 是主修路径的红测：崩溃残留后重新
// subscribe 必须恢复 delta 流（004 无状态免疫跨 daemon 重启）。
// 修前：新 pipe-pane -o 见已有 pipe 即 toggle 不挂新命令 → 新 FIFO 无 writer
// → relay 阻塞 → 注入不回显 → 本测试红。修后：subscribe 先 detach 再 -o →
// delta 必须恢复 → 绿。
func TestSubscribeRecoversAfterCrashedPipe(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")
	attachStalePipe(t, tt, p)

	ch, cancel, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("Subscribe after crashed pipe: %v", err)
	}
	defer cancel()

	if err := p.Inject(context.Background(), "CRASH_RECOVER_77"); err != nil {
		t.Fatalf("Inject: %v", err)
	}
	if !waitForStream(t, ch, "CRASH_RECOVER_77") {
		t.Fatal("delta never arrived after crashed-pipe re-subscribe (004 replay broken)")
	}
}

// TestSubscribeTeardownNotBlockedAfterCrashedPipe 是加固路径的红测：relay 打开
// FIFO 必须永不无限阻塞，teardown 必可达（根因链第 4 步"死锁不能自愈"）。
// 修前：relay 阻塞在 O_RDONLY open，cancel 后 ch 永不关闭 → 本测试红。
// 修后：subscribe 内有限等待 writer，teardown 可达 → ch 关闭 → 绿。
func TestSubscribeTeardownNotBlockedAfterCrashedPipe(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")
	attachStalePipe(t, tt, p)

	ch, cancel, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("Subscribe after crashed pipe: %v", err)
	}
	_ = ch

	cancel()
	select {
	case _, ok := <-ch:
		if ok {
			t.Fatal("ch not closed after cancel (relay still alive)")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("teardown blocked: relay stuck in FIFO open after cancel (root-cause step 4)")
	}
}

// TestOpenFIFOTimesOut is the 加固's direct red test: a FIFO that never gets a
// writer must yield a decidable error within fifoOpenTimeout, never hang. The
// old implementation's relay called os.OpenFile unconditionally and blocked
// forever (root-cause chain step 3-4); openFIFO replaces that with a bounded
// open that unblocks itself and reports the timeout.
func TestOpenFIFOTimesOut(t *testing.T) {
	dir := t.TempDir()
	fifo := filepath.Join(dir, "no-writer.fifo")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Fatalf("mkfifo: %v", err)
	}
	defer os.Remove(fifo)

	start := time.Now()
	f, err := openFIFO(fifo)
	elapsed := time.Since(start)
	if err == nil {
		f.Close()
		t.Fatal("openFIFO on a writerless FIFO must time out with an error, got nil")
	}
	if elapsed > fifoOpenTimeout+time.Second {
		t.Fatalf("openFIFO took %v (limit %v): must fail fast, not hang", elapsed, fifoOpenTimeout)
	}
	if !strings.Contains(err.Error(), "no writer") {
		t.Fatalf("openFIFO timeout error should be decidable, got %v", err)
	}
	t.Logf("openFIFO timed out as expected after %v: %v", elapsed.Round(time.Millisecond), err)
}
