package api

// idle_gate_test.go — taskbook#fix-daemon-idle-cpu 红测：空闲降耗门控。
// 缺陷 C 的机理：api 层列表轮询按 ListInterval 无条件 tick，零客户端时仍每秒
// 派生几十个 tmux 扫描子进程空烧 CPU（真机实测 4 孤儿实例各 17.5%）。
// 修复契约：零已认证客户端时 listingLoop 暂停 discovery 轮询（零扫描子进程派生）；
// 从 0→1 有客户端认证时立即做一次全量扫描保首屏新鲜，不等下一个 interval tick。
// 断言用 fake 计数（fake clock / 注入 discoverer 计数），不做真实 CPU 百分比断言
// （CI 不稳，知识基底 §3 经验基）。

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// countingDiscoverer 数每次 Discover 调用；门控红测断言在计数上，而非 CPU 百分比。
type countingDiscoverer struct {
	scans atomic.Int64
	model *discovery.Model
}

func (d *countingDiscoverer) Discover(context.Context) (*discovery.Model, error) {
	d.scans.Add(1)
	return d.model, nil
}

// TestListingLoopPausesWhenNoAuthedClient 是缺陷 C 的核心红测：断开全部客户端后，
// 轮询必须停止（扫描计数不再增长）。修前：loop 无条件 tick → 断开后计数继续涨 → 红。
func TestListingLoopPausesWhenNoAuthedClient(t *testing.T) {
	cd := &countingDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: cd, ListInterval: 30 * time.Millisecond})

	// 正向控制：有已认证客户端时 loop 必须持续轮询（≥3 次扫描）。
	e.auth()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if cd.scans.Load() >= 3 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if cd.scans.Load() < 3 {
		t.Fatalf("authed client must keep polling; scans=%d", cd.scans.Load())
	}

	// 断开连接：服务端处理 teardown（authed→0）后轮询必须停。
	_ = e.conn.CloseNow()

	// 收敛探针：等待计数稳定（teardown + 任何在途扫描落定），再断言无增长。
	var stable int64
	for i := 0; i < 20; i++ {
		time.Sleep(60 * time.Millisecond)
		c := cd.scans.Load()
		if c == stable {
			break
		}
		stable = c
	}
	// 再等几个 interval，扫描计数必须不再增长（零客户端 = 零扫描子进程）。
	time.Sleep(200 * time.Millisecond)
	if got := cd.scans.Load(); got != stable {
		t.Fatalf("scan count grew while idle: stable=%d, now=%d (polling must pause with zero clients)", stable, got)
	}
}

// TestFirstAuthTriggersImmediateScan 红测 0→1 保首屏新鲜：用 10s 的超长 interval，
// 认证是唯一的客户端动作（不发 List）。修前：loop 只等 ticker（10s 后才扫）→ 2s 内
// 计数为 0 → 红。修后：0→1 唤醒 loop 立即全量扫 → 绿。
func TestFirstAuthTriggersImmediateScan(t *testing.T) {
	cd := &countingDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: cd, ListInterval: 10 * time.Second})

	e.auth() // 唯一的客户端动作；认证即应唤醒一次全量扫描
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cd.scans.Load() >= 1 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("first auth did not trigger an immediate scan (scans=0 within 2s, interval=10s)")
}
