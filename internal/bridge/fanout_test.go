package bridge

// fanout_test.go — Q5 红测：同一 pane 一条 pipe 必须能喂多个订阅者。
// 修前（stream.go 每个 Subscribe 无参 pipe-pane 拆别人的管道）A、B 必红。

import (
	"context"
	"strings"
	"testing"
	"time"
)

// TestFanoutTwoSubscribersStillReceive is assertion A: after a second
// subscriber attaches, the first still receives DELTA (不受影响).
// 修前：第二个 Subscribe 先无参 pipe-pane 拆掉第一个的 FIFO，第一个 ch EOF，红。
func TestFanoutTwoSubscribersStillReceive(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	c1, cancel1, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("first Subscribe: %v", err)
	}
	defer cancel1()

	c2, cancel2, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("second Subscribe: %v", err)
	}
	defer cancel2()

	if err := p.Inject(context.Background(), "FANOUT_A_STILL"); err != nil {
		t.Fatalf("Inject: %v", err)
	}
	if !waitForStream(t, c1, "FANOUT_A_STILL") {
		t.Fatal("A: first subscriber must still receive DELTA after second attaches")
	}
	if !waitForStream(t, c2, "FANOUT_A_STILL") {
		t.Fatal("second subscriber never saw the same DELTA")
	}
}

// TestFanoutFirstDetachDoesNotKickSecond is assertion B: 第一个断开后，
// 第二个仍能收到 DELTA（一方 disconnect/detach 不误伤另一方）。
// 修前：第一个 cancel 对整个 pane 发无参 pipe-pane，第二个 FIFO 被拆，红。
func TestFanoutFirstDetachDoesNotKickSecond(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	_, cancel1, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("first Subscribe: %v", err)
	}
	c2, cancel2, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("second Subscribe: %v", err)
	}
	defer cancel2()

	cancel1() // 第一人断开；管道所有权不该被无条件拆除

	if err := p.Inject(context.Background(), "FANOUT_B_ALIVE"); err != nil {
		t.Fatalf("Inject: %v", err)
	}
	if !waitForStream(t, c2, "FANOUT_B_ALIVE") {
		t.Fatal("B: second subscriber must still receive DELTA after first detach")
	}
}

// TestFanoutSinglePipeAfterTwoSubscribers pins the tmux-side invariant:
// two subscribers share one pane_pipe, not two sequential attaches.
func TestFanoutSinglePipeAfterTwoSubscribers(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	_, c1, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("first Subscribe: %v", err)
	}
	defer c1()
	_, c2, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("second Subscribe: %v", err)
	}
	defer c2()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		out, err := tt.run("display-message", "-p", "-t", p.target, "#{pane_pipe}")
		if err == nil && strings.TrimSpace(out) == "1" {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("pane_pipe != 1 after two subscribers (want a single shared pipe)")
}
