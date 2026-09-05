// Copyright 2026 AgentMirror Project Authors
// SPDX-License-Identifier: Apache-2.0

// Package tsnetbind 是 gomobile 绑定最小包装：
// 在 App 进程内起 tsnet 用户态节点（无 VpnService），
// 通过 Loopback() 暴露本机 SOCKS5/HTTP 代理给 OkHttp 使用。
// gomobile 类型约束：导出 API 只用 string/int/bool/error 与导出 struct 指针/接口。
//
// Android 特有约束（feat-ts-wire 模拟器实证）：API 30+ SELinux 禁 app 进程
// netlink RTM_GETLINK，Go net.Interfaces() 必死（"netlinkrib: permission denied"）。
// 正解与官方 Android 客户端同款：起网前经 [SetInterfaceProvider] 注入 Java 层
// 的网卡枚举（netmon.RegisterInterfaceGetter），Go 侧不再触 netlink 枚举。
package tsnetbind

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// Node 是一个运行中的 tsnet 用户态节点句柄。
type Node struct {
	srv       *tsnet.Server
	proxyAddr string
	proxyCred string
}

type peerSnapshotRow struct {
	id       string
	online   bool
	ipv4     []string
	hostname string
}

// PeerSnapshotResult is intentionally flat for gomobile. Lines is bounded by the
// caller-independent 256-row window and NextCursor is empty when the table is exhausted.
type PeerSnapshotResult struct {
	Lines      string
	NextCursor string
}

const peerSnapshotPageSize = 256

// upTimeout 界定控制面握手：超时即显式失败（工程红线5 失败可见，
// 与服务端 cmd/agentmirrord 的 tailnetUpTimeout 同语义同值）。
const upTimeout = 60 * time.Second

// Start 用 authkey 起节点并**阻塞至真正入网**（tsnet.Up 等到 Running）：
// dir 为状态目录（Android filesDir 下），hostname 为节点在 tailnet 中的名字，
// controlURL 空串 = 官方控制面，非空 = 自建（headscale 等，011 部署侧自由）。
// 语义修正（feat-ts-wire）：旧版只 srv.Start（异步初始化）就返回句柄，key 无效
// 也会看似成功——"已入网"必须是控制面裁定后的真值，否则 018 状态可视失实。
func Start(dir, hostname, authKey, controlURL string) (*Node, error) {
	// Android app 进程没有 HOME/TMPDIR（Go 的各级"用户目录"回落全断）：
	// logpolicy 找不到日志状态目录会直接 panic("no safe place found to store
	// log state")——模拟器实证的第二颗雷（第一颗是 netlink）。统一把日志状态、
	// 临时目录、HOME 指进 app 私有状态目录，全程不出沙箱。
	if p := filepath.Join(dir, "logs"); os.MkdirAll(p, 0o700) == nil {
		os.Setenv("TS_LOGS_DIR", p)
	}
	if p := filepath.Join(dir, "tmp"); os.MkdirAll(p, 0o700) == nil {
		os.Setenv("TMPDIR", p)
	}
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", dir)
	}
	srv := &tsnet.Server{
		Dir:        dir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Ephemeral:  false,
	}
	ctx, cancel := context.WithTimeout(context.Background(), upTimeout)
	defer cancel()
	if _, err := srv.Up(ctx); err != nil {
		// Up 内部已 Start；失败必须关掉半启动的节点，不留后台重试孤儿。
		srv.Close()
		return nil, err
	}
	// Loopback 起一个仅监听 127.0.0.1 的 SOCKS5+HTTP 代理，
	// Kotlin 侧 OkHttp 以 Proxy(SOCKS, addr) + 凭证接入 tailnet。
	addr, cred, _, err := srv.Loopback()
	if err != nil {
		srv.Close()
		return nil, err
	}
	return &Node{srv: srv, proxyAddr: addr, proxyCred: cred}, nil
}

// ProxyAddr 返回 loopback 代理监听地址（host:port）。
func (n *Node) ProxyAddr() string { return n.proxyAddr }

// ProxyCred 返回代理认证凭证（SOCKS5 用户名口令均为它）。
func (n *Node) ProxyCred() string { return n.proxyCred }

// PeerSnapshot returns a bounded, deterministic snapshot of Status.Peer.
// The known ID is matched against the complete table before the 256-line window;
// cursor is the last emitted StableID and allows later generations to continue.
// Lines are: stableID<TAB>online(0|1)<TAB>ipv4 comma-list<TAB>hostname.
func (n *Node) PeerSnapshot(knownID, cursor string) (*PeerSnapshotResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	lc, err := n.srv.LocalClient()
	if err != nil {
		return nil, err
	}
	status, err := lc.Status(ctx)
	if err != nil {
		return nil, err
	}
	rows := make([]peerSnapshotRow, 0, len(status.Peer))
	for _, peer := range status.Peer {
		id := fmt.Sprint(peer.ID)
		ips := make([]string, 0, len(peer.TailscaleIPs))
		for _, ip := range peer.TailscaleIPs {
			if ip.Is4() {
				ips = append(ips, ip.String())
			}
		}
		sort.Strings(ips)
		rows = append(rows, peerSnapshotRow{id: id, online: peer.Online, ipv4: ips, hostname: cleanPeerText(peer.HostName)})
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].id < rows[j].id })
	if row, ok := findPeerRow(rows, knownID); ok {
		return &PeerSnapshotResult{Lines: formatPeerRows([]peerSnapshotRow{row})}, nil
	}
	window, nextCursor := pagePeerRows(rows, cursor)
	return &PeerSnapshotResult{Lines: formatPeerRows(window), NextCursor: nextCursor}, nil
}

func findPeerRow(rows []peerSnapshotRow, knownID string) (peerSnapshotRow, bool) {
	if knownID == "" {
		return peerSnapshotRow{}, false
	}
	for _, row := range rows {
		if row.id == knownID {
			return row, true
		}
	}
	return peerSnapshotRow{}, false
}

// pagePeerRows emits one non-overlapping bounded window. A cursor always advances
// in sorted-table order; it never wraps around and repeats earlier peers before a
// caller has observed the tail of a large table.
func pagePeerRows(rows []peerSnapshotRow, cursor string) ([]peerSnapshotRow, string) {
	if len(rows) == 0 {
		return nil, ""
	}
	start := 0
	if cursor != "" {
		for i, row := range rows {
			if row.id == cursor {
				start = i + 1
				break
			}
		}
	}
	if start >= len(rows) {
		return nil, ""
	}
	end := start + peerSnapshotPageSize
	if end > len(rows) {
		end = len(rows)
	}
	window := rows[start:end]
	if end == len(rows) {
		return window, ""
	}
	return window, window[len(window)-1].id
}

func cleanPeerText(s string) string {
	return strings.NewReplacer("\t", " ", "\n", " ", "\r", " ").Replace(s)
}

func formatPeerRows(rows []peerSnapshotRow) string {
	var b strings.Builder
	for i, row := range rows {
		if i > 0 {
			b.WriteByte('\n')
		}
		fmt.Fprintf(&b, "%s\t%d\t%s\t%s", row.id, boolInt(row.online), strings.Join(row.ipv4, ","), row.hostname)
	}
	return b.String()
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}

// Close 停节点并释放资源。
func (n *Node) Close() error { return n.srv.Close() }

// InterfaceProvider 由 Kotlin 实现：喂 Java 层（java.net.NetworkInterface）的
// 网卡枚举结果。行文本而非结构体列表——gomobile 对 slice/复合类型支持贫弱，
// 单 string 往返最稳。行格式（与 Kotlin TsnetInterfaceCodec 同一契约）：
//
//	name|index|mtu|up|loopback|multicast|ptp|cidr1,cidr2
//
// 布尔为 1/0；地址为 CIDR，可为空。调用频度 = netmon 轮询（秒级间隔的后台路径，
// 非渲染热路径，编码分配可接受）。
type InterfaceProvider interface {
	Interfaces() (string, error)
}

// SetInterfaceProvider 注册 Java 层网卡枚举（须在 Start 之前调用，进程级一次）。
// 注册后 netmon 的接口枚举全部走 provider，Go 侧不再触 netlink。
func SetInterfaceProvider(p InterfaceProvider) {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		s, err := p.Interfaces()
		if err != nil {
			return nil, err
		}
		return parseInterfaces(s)
	})
}

// parseInterfaces 解析 provider 的行文本为 netmon 接口表。坏行/坏地址项跳过
// （单卡异常不拖垮全表），但全表无一有效行必须报错——空表会让 netmon 误判
// "无网络"而静默瘫痪，显式失败可见（003）。
func parseInterfaces(s string) ([]netmon.Interface, error) {
	var out []netmon.Interface
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		f := strings.Split(line, "|")
		if len(f) != 8 || f[0] == "" {
			continue
		}
		idx, err1 := strconv.Atoi(f[1])
		mtu, err2 := strconv.Atoi(f[2])
		if err1 != nil || err2 != nil {
			continue
		}
		var flags net.Flags
		if f[3] == "1" {
			flags |= net.FlagUp | net.FlagRunning
		}
		if f[4] == "1" {
			flags |= net.FlagLoopback
		}
		if f[5] == "1" {
			flags |= net.FlagMulticast
		}
		if f[6] == "1" {
			flags |= net.FlagPointToPoint
		}
		// Java 无 broadcast 旗标查询口；非 loopback 非 ptp 的常规网卡按惯例置位。
		if flags&(net.FlagLoopback|net.FlagPointToPoint) == 0 {
			flags |= net.FlagBroadcast
		}
		// AltAddrs 非 nil 时 netmon.Interface.Addrs() 直接返回它（不再触 netlink）。
		// 无地址网卡也要给空 slice 而非 nil，否则 Addrs() 会回落 net.Interface.Addrs()。
		addrs := []net.Addr{}
		for _, a := range strings.Split(f[7], ",") {
			a = strings.TrimSpace(a)
			if a == "" {
				continue
			}
			ip, ipnet, err := net.ParseCIDR(a)
			if err != nil {
				continue // 单地址坏项跳过（Java 层偶发怪地址不拖垮该卡）
			}
			out2 := &net.IPNet{IP: ip, Mask: ipnet.Mask}
			addrs = append(addrs, out2)
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("tsnetbind: no usable interfaces in provider table (%d bytes)", len(s))
	}
	return out, nil
}
