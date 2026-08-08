// Copyright 2026 AgentMirror Project Authors
// SPDX-License-Identifier: Apache-2.0

// Package tsnetbind 是 gomobile 绑定最小包装：
// 在 App 进程内起 tsnet 用户态节点（无 VpnService），
// 通过 Loopback() 暴露本机 SOCKS5/HTTP 代理给 OkHttp 使用。
// gomobile 类型约束：导出 API 只用 string/int/bool/error 与导出 struct 指针。
package tsnetbind

import (
	"tailscale.com/tsnet"
)

// Node 是一个运行中的 tsnet 用户态节点句柄。
type Node struct {
	srv       *tsnet.Server
	proxyAddr string
	proxyCred string
}

// Start 用 authkey 起节点：dir 为状态目录（Android filesDir 下），
// hostname 为节点在 tailnet 中的名字。返回可用句柄或错误。
func Start(dir, hostname, authKey string) (*Node, error) {
	srv := &tsnet.Server{
		Dir:       dir,
		Hostname:  hostname,
		AuthKey:   authKey,
		Ephemeral: false,
	}
	if err := srv.Start(); err != nil {
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

// Close 停节点并释放资源。
func (n *Node) Close() error { return n.srv.Close() }
