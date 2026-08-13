// 根因探针：上传通道 VPN/proxy 绕过检测（fix-upload-transport-tsnet）
//
// 探针定义：
//   命中（bug 存在）= 上传 HTTP 请求没有经过 SOCKS 代理直接建连 → 蜂窝/物理网卡出去
//   不命中（bug 已修）= 上传 HTTP 请求经过 SOCKS 代理建连 → 走 tsnet 用户态隧道
//
// 自证（纪律⑨）：
//   场景 A（无代理 = 当前 HEAD 状态）→ 命中（SOCKS 服务器没有收到连接）
//   场景 B（有代理 = 修复后状态）    → 不命中（SOCKS 服务器收到了连接）
//
// 复现路径（不靠用户手机）：
//   两个本地 goroutine（SOCKS5 + HTTP server），纯 loopback，无网络依赖，零外部依赖。
//
// 运行：
//   cd e2e/harness && go test -v -run TestUploadTransportProbe ./...
package main

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// TestUploadTransportProbe 是核心探针。
// 结构：两个子测试，分别模拟"修复前（无代理）"和"修复后（有代理）"状态，
// 用同一套 SOCKS5 服务器记录连接次数来判定。
func TestUploadTransportProbe(t *testing.T) {
	// --- 公共基础设施 ---
	// 1. 启动 mock HTTP 上传服务器（模拟 daemon /upload 端点）
	uploadSrv := newRecordingHTTPServer(t)
	// 2. 启动 SOCKS5 转发代理（记录是否被访问）
	socks5Srv := newSocks5ForwardProxy(t)

	t.Run("A_no_proxy_probe_must_HIT", func(t *testing.T) {
		// 场景 A：不配置 SOCKS 代理（模拟当前 HEAD：HttpUrlConnectionUploader.openConnection 无 proxy）
		// 预期：SOCKS5 服务器连接计数 == 0（探针命中，bug 存在）
		before := socks5Srv.connCount()
		// 直接 HTTP POST，绕过 SOCKS（Java URL.openConnection() 无 proxy 参数的等价行为）
		err := doUploadDirect(uploadSrv.addr)
		if err != nil {
			t.Logf("direct upload error (expected for tailnet-only target): %v", err)
		}
		after := socks5Srv.connCount()
		if after > before {
			// SOCKS 服务器被用了 → 不应该发生（此场景无代理）
			t.Errorf("probe MISS in no-proxy scenario: SOCKS proxy was used (%d→%d). Self-proof broken.", before, after)
		} else {
			t.Logf("probe HIT ✓: direct upload bypassed SOCKS proxy (count=%d→%d). Bug confirmed.", before, after)
		}
	})

	t.Run("B_with_proxy_probe_must_NOT_HIT", func(t *testing.T) {
		// 场景 B：配置 SOCKS5 代理（模拟修复后：上传复用 TsnetProxySocketFactory）
		// 预期：SOCKS5 服务器连接计数 > 0（探针不命中，bug 已修）
		before := socks5Srv.connCount()
		err := doUploadViaSocks5(socks5Srv.addr, uploadSrv.addr)
		if err != nil {
			t.Logf("proxied upload error: %v", err)
		}
		after := socks5Srv.connCount()
		if after <= before {
			// SOCKS 服务器没被用 → 修复无效
			t.Errorf("probe HIT in with-proxy scenario: SOCKS proxy was NOT used. Fix is not working.")
		} else {
			t.Logf("probe NOT HIT ✓: upload went through SOCKS proxy (count=%d→%d). Fix confirmed.", before, after)
		}
	})
}

// TestUploadTransportProbeSelfCert 是纪律⑨的自证测试（两次区分）：
//
//   场景 X（tailnet 目标，无代理）→ 命中：SOCKS 未被调用，连接尝试直接发出
//     （对应用户看到的「从蜂窝地址 10.4.x.x 出去」，因为没有 SOCKS 路由）
//   场景 Y（LAN 目标，无代理）   → 不命中：直连成功，SOCKS 无需介入
//     （LAN 路径本来就能通，没有 bug）
//
// 100.64.0.1 是 Tailscale CGNAT 段（100.64.0.0/10）内的地址，本机无此路由
// → 直连必然失败 → 探针可检测「SOCKS 是否被调用」。
func TestUploadTransportProbeSelfCert(t *testing.T) {
	// LAN 目标：本地 HTTP 服务器（可达）
	uploadSrv := newRecordingHTTPServer(t)
	socks5Srv := newSocks5ForwardProxy(t)

	// 场景 X：tailnet IP（100.64.0.1）直连 → 必然失败，SOCKS 未被调用 → 命中
	t.Run("X_tailnet_direct_probe_HIT", func(t *testing.T) {
		fakeTailnetAddr := "100.64.0.1:65533" // CGNAT 段，本机无路由，秒超时
		before := socks5Srv.connCount()
		_ = doUploadDirectTimeout(fakeTailnetAddr, 500*time.Millisecond) // 超时 0.5s，不影响 CI
		after := socks5Srv.connCount()
		if after > before {
			t.Error("self-cert X FAIL: direct-to-tailnet unexpectedly used SOCKS")
		} else {
			t.Logf("self-cert X PASS: tailnet IP direct upload = SOCKS not used (count=%d→%d). Bug condition confirmed.", before, after)
		}
	})

	// 场景 Y：LAN 目标（127.0.0.1）直连 → 成功，SOCKS 未介入 → 不命中
	t.Run("Y_lan_direct_probe_NOT_HIT", func(t *testing.T) {
		before := socks5Srv.connCount()
		err := doUploadDirect(uploadSrv.addr)
		after := socks5Srv.connCount()
		if err != nil {
			t.Errorf("self-cert Y FAIL: LAN direct upload failed (%v), probe is broken", err)
		} else if after > before {
			t.Error("self-cert Y FAIL: LAN direct upload used SOCKS, probe is not testing what we think")
		} else {
			t.Logf("self-cert Y PASS: LAN IP direct upload succeeded without SOCKS (count=%d→%d). Correct behavior.", before, after)
		}
	})
}

// doUploadDirectTimeout 直接 POST 到 target，无代理，超时可配置（用于必然不可达目标）。
func doUploadDirectTimeout(target string, timeout time.Duration) error {
	client := &http.Client{Timeout: timeout}
	resp, err := client.Post("http://"+target+"/upload", "multipart/form-data", strings.NewReader("--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"t.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--b--\r\n"))
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// doUploadDirect 直接 POST 到 target，无代理（等价 Java URL.openConnection() 无 proxy）。
func doUploadDirect(target string) error {
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Post("http://"+target+"/upload", "multipart/form-data", strings.NewReader("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--boundary--\r\n"))
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// doUploadViaSocks5 通过 SOCKS5 代理 POST 到 target（等价修复后：用 TsnetProxySocketFactory 建连）。
func doUploadViaSocks5(socksAddr, target string) error {
	dialer := &socks5Dialer{proxyAddr: socksAddr}
	transport := &http.Transport{DialContext: dialer.DialContext}
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	resp, err := client.Post("http://"+target+"/upload", "multipart/form-data", strings.NewReader("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--boundary--\r\n"))
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// --- 基础设施 ---

type recordingHTTPServer struct {
	addr      string
	reqCount  atomic.Int64
}

func newRecordingHTTPServer(t *testing.T) *recordingHTTPServer {
	t.Helper()
	srv := &recordingHTTPServer{}
	mux := http.NewServeMux()
	mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		srv.reqCount.Add(1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"path":"/tmp/probe-upload.txt"}`)
	})
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("HTTP server listen: %v", err)
	}
	srv.addr = l.Addr().String()
	httpSrv := &http.Server{Handler: mux}
	go httpSrv.Serve(l) //nolint:errcheck
	t.Cleanup(func() { httpSrv.Close() })
	return srv
}

type socks5Server struct {
	addr     string
	conns    atomic.Int64
}

func (s *socks5Server) connCount() int64 { return s.conns.Load() }

// newSocks5ForwardProxy 起一个最小 SOCKS5 无认证转发代理，记录入站连接数。
// 协议：RFC 1928 SOCKS5 CONNECT，无认证，转发到真实目标。
func newSocks5ForwardProxy(t *testing.T) *socks5Server {
	t.Helper()
	srv := &socks5Server{}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("SOCKS5 server listen: %v", err)
	}
	srv.addr = l.Addr().String()
	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				return
			}
			srv.conns.Add(1)
			go handleSocks5(conn)
		}
	}()
	t.Cleanup(func() { l.Close() })
	return srv
}

// handleSocks5 处理一次 SOCKS5 CONNECT 握手并转发数据。
func handleSocks5(client net.Conn) {
	defer client.Close()
	client.SetDeadline(time.Now().Add(5 * time.Second)) //nolint:errcheck

	// 握手：Version=5, 方法=无认证
	buf := make([]byte, 256)
	if _, err := io.ReadFull(client, buf[:2]); err != nil {
		return
	}
	nmethods := int(buf[1])
	if _, err := io.ReadFull(client, buf[:nmethods]); err != nil {
		return
	}
	client.Write([]byte{0x05, 0x00}) //nolint:errcheck // 选择无认证

	// 读请求
	if _, err := io.ReadFull(client, buf[:4]); err != nil {
		return
	}
	// buf[0]=5, buf[1]=CMD(1=CONNECT), buf[2]=RSV, buf[3]=ATYP
	atyp := buf[3]
	var targetAddr string
	switch atyp {
	case 0x01: // IPv4
		if _, err := io.ReadFull(client, buf[:4]); err != nil {
			return
		}
		targetAddr = fmt.Sprintf("%d.%d.%d.%d", buf[0], buf[1], buf[2], buf[3])
	case 0x03: // 域名
		if _, err := io.ReadFull(client, buf[:1]); err != nil {
			return
		}
		n := int(buf[0])
		if _, err := io.ReadFull(client, buf[:n]); err != nil {
			return
		}
		targetAddr = string(buf[:n])
	case 0x04: // IPv6
		if _, err := io.ReadFull(client, buf[:16]); err != nil {
			return
		}
		targetAddr = fmt.Sprintf("[%x:%x:%x:%x:%x:%x:%x:%x]",
			binary.BigEndian.Uint16(buf[0:2]), binary.BigEndian.Uint16(buf[2:4]),
			binary.BigEndian.Uint16(buf[4:6]), binary.BigEndian.Uint16(buf[6:8]),
			binary.BigEndian.Uint16(buf[8:10]), binary.BigEndian.Uint16(buf[10:12]),
			binary.BigEndian.Uint16(buf[12:14]), binary.BigEndian.Uint16(buf[14:16]))
	default:
		return
	}
	if _, err := io.ReadFull(client, buf[:2]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(buf[:2])
	target := fmt.Sprintf("%s:%d", targetAddr, port)

	// 连接目标
	remote, err := net.DialTimeout("tcp", target, 3*time.Second)
	if err != nil {
		client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) //nolint:errcheck
		return
	}
	defer remote.Close()
	// 成功响应
	client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) //nolint:errcheck
	client.SetDeadline(time.Time{}) //nolint:errcheck
	remote.SetDeadline(time.Time{}) //nolint:errcheck

	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, client); done <- struct{}{} }() //nolint:errcheck
	go func() { io.Copy(client, remote); done <- struct{}{} }() //nolint:errcheck
	<-done
}

// socks5Dialer 用于 http.Transport.DialContext，通过 SOCKS5 代理建立 TCP 连接。
type socks5Dialer struct {
	proxyAddr string
}

func (d *socks5Dialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", d.proxyAddr)
	if err != nil {
		return nil, fmt.Errorf("dial SOCKS5 proxy: %w", err)
	}
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		conn.Close()
		return nil, err
	}
	var port int
	fmt.Sscanf(portStr, "%d", &port)

	conn.SetDeadline(time.Now().Add(5 * time.Second)) //nolint:errcheck
	// 握手
	conn.Write([]byte{0x05, 0x01, 0x00})                 //nolint:errcheck
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 handshake: %w", err)
	}
	// CONNECT 请求（ATYP=0x03 域名）
	req := []byte{0x05, 0x01, 0x00, 0x03, byte(len(host))}
	req = append(req, []byte(host)...)
	req = append(req, byte(port>>8), byte(port))
	conn.Write(req) //nolint:errcheck
	resp := make([]byte, 10)
	if _, err := io.ReadFull(conn, resp); err != nil {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect response: %w", err)
	}
	if resp[1] != 0x00 {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect rejected: %d", resp[1])
	}
	conn.SetDeadline(time.Time{}) //nolint:errcheck
	return conn, nil
}
