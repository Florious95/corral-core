// Copyright 2026 AgentMirror Project Authors
// SPDX-License-Identifier: Apache-2.0

package tsnetbind

// tsnetbind_test.go（feat-ts-wire 红测先行）：Android API 30+ SELinux 禁 app 进程
// netlink RTM_GETLINK，Go net.Interfaces() 在 Android 必死（模拟器实证
// "route ip+net: netlinkrib: permission denied"）。正解 = 官方 Android 客户端同款：
// netmon.RegisterInterfaceGetter 注入 Java 层枚举。本文件锁行文本编码的解析契约
// （Kotlin 侧 TsnetInterfaceCodec 生成，两端同一格式）。

import (
	"fmt"
	"net"
	"strings"
	"testing"
)

// TestParseInterfacesSingle 锁单行契约：
// name|index|mtu|up|loopback|multicast|ptp|cidr1,cidr2
func TestParseInterfacesSingle(t *testing.T) {
	got, err := parseInterfaces("wlan0|3|1500|1|0|1|0|192.168.1.5/24,fe80::1/64")
	if err != nil {
		t.Fatalf("parseInterfaces: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("len = %d, want 1", len(got))
	}
	i := got[0]
	if i.Name != "wlan0" || i.Index != 3 || i.MTU != 1500 {
		t.Errorf("iface = %+v, want wlan0/3/1500", i.Interface)
	}
	if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagRunning == 0 {
		t.Errorf("up iface must carry FlagUp|FlagRunning, got %v", i.Flags)
	}
	if i.Flags&net.FlagMulticast == 0 {
		t.Errorf("multicast flag lost: %v", i.Flags)
	}
	if i.Flags&net.FlagLoopback != 0 {
		t.Errorf("non-loopback iface must not carry FlagLoopback: %v", i.Flags)
	}
	addrs, err := i.Addrs()
	if err != nil || len(addrs) != 2 {
		t.Fatalf("Addrs = %v (err %v), want 2 CIDR addrs", addrs, err)
	}
	if !strings.Contains(addrs[0].String(), "192.168.1.5") {
		t.Errorf("addr[0] = %v, want 192.168.1.5/24", addrs[0])
	}
}

// TestParseInterfacesLoopbackAndDown 覆盖 lo 与 down 网卡的旗标映射。
func TestParseInterfacesLoopbackAndDown(t *testing.T) {
	got, err := parseInterfaces("lo|1|65536|1|1|0|0|127.0.0.1/8\ndummy0|9|1400|0|0|0|0|")
	if err != nil {
		t.Fatalf("parseInterfaces: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("len = %d, want 2", len(got))
	}
	if got[0].Flags&net.FlagLoopback == 0 {
		t.Errorf("lo must carry FlagLoopback, got %v", got[0].Flags)
	}
	if got[1].Flags&net.FlagUp != 0 {
		t.Errorf("down iface must not carry FlagUp, got %v", got[1].Flags)
	}
	if addrs, _ := got[1].Addrs(); len(addrs) != 0 {
		t.Errorf("no-addr iface Addrs = %v, want empty", addrs)
	}
}

// TestParseInterfacesSkipsMalformedLines：坏行跳过不拖垮整表（半行/字段数错/坏数字/
// 坏 CIDR 项内跳过），全表无一有效行才报错——netmon 拿到空表会误判"无网络"。
func TestPeerSnapshotKnownIDSearchesCompleteTable(t *testing.T) {
	rows := snapshotRows(300)
	row, ok := findPeerRow(rows, "peer-299")
	if !ok || row.id != "peer-299" {
		t.Fatalf("known peer lookup = %+v, want peer-299", row)
	}
}

func TestPeerSnapshotPagesAllRowsWithoutBudgetSkips(t *testing.T) {
	rows := snapshotRows(300)
	seen := make([]string, 0, len(rows))
	cursor := ""
	for {
		window, next := pagePeerRows(rows, cursor)
		for _, row := range window {
			seen = append(seen, row.id)
		}
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != len(rows) {
		t.Fatalf("paged row count = %d, want %d", len(seen), len(rows))
	}
	for i, id := range seen {
		want := fmt.Sprintf("peer-%03d", i)
		if id != want {
			t.Fatalf("row %d = %q, want %q (9/33/>256 fairness)", i, id, want)
		}
	}
}

func snapshotRows(count int) []peerSnapshotRow {
	rows := make([]peerSnapshotRow, count)
	for i := range rows {
		rows[i] = peerSnapshotRow{id: fmt.Sprintf("peer-%03d", i), hostname: fmt.Sprintf("host-%03d", i)}
	}
	return rows
}

func TestParseInterfacesSkipsMalformedLines(t *testing.T) {
	got, err := parseInterfaces("garbage\nwlan0|3|1500|1|0|1|0|192.168.1.5/24,notacidr\n|x|y|1|0|0|0|")
	if err != nil {
		t.Fatalf("parseInterfaces with one good line: %v", err)
	}
	if len(got) != 1 || got[0].Name != "wlan0" {
		t.Fatalf("got %d ifaces, want only wlan0", len(got))
	}
	if addrs, _ := got[0].Addrs(); len(addrs) != 1 {
		t.Errorf("bad CIDR must be skipped within line, addrs = %v", addrs)
	}

	if _, err := parseInterfaces("garbage-only"); err == nil {
		t.Fatal("all-malformed table must error (empty table would fake 'no network')")
	}
	if _, err := parseInterfaces(""); err == nil {
		t.Fatal("empty table must error")
	}
}
