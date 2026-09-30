package api

// ws_perf_subscribe_test.go — t.srv subscribe 三时间戳量具。
// 键名契约：perf_subscribe recv_ms start_ms done_ms queue_ms（queue_ms = start-recv）。

import (
	"bytes"
	"context"
	"log/slog"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestLogPerfSubscribeQueueIsStartMinusRecv(t *testing.T) {
	var buf bytes.Buffer
	lg := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	c := &wsConn{
		ctx: context.Background(),
		s:   &Server{log: lg},
	}
	c.logPerfSubscribe("sess/a", 10, 70, 90)
	line := buf.String()
	for _, k := range []string{"perf_subscribe", "recv_ms", "start_ms", "done_ms", "queue_ms"} {
		if !strings.Contains(line, k) {
			t.Fatalf("log missing key %s: %q", k, line)
		}
	}
	got := slogInt(t, line, "queue_ms")
	if got != 60 {
		t.Fatalf("queue_ms=%d want start-recv=60; line=%q", got, line)
	}
	if slogInt(t, line, "recv_ms") != 10 || slogInt(t, line, "start_ms") != 70 || slogInt(t, line, "done_ms") != 90 {
		t.Fatalf("operand mismatch: %q", line)
	}
}

func TestLogPerfSubscribeDisabledSkipsLine(t *testing.T) {
	var buf bytes.Buffer
	lg := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelError}))
	c := &wsConn{
		ctx: context.Background(),
		s:   &Server{log: lg},
	}
	c.logPerfSubscribe("sess/a", 1, 2, 3)
	if strings.Contains(buf.String(), "perf_subscribe") {
		t.Fatalf("Error-level logger must not emit perf_subscribe: %q", buf.String())
	}
}

func TestPerfSubscribeUnknownRefLogsOperands(t *testing.T) {
	var buf bytes.Buffer
	lg := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	e := startWS(t, Options{
		Token:        "test-token",
		Log:          lg,
		Discoverer:   scriptedDiscoverer{model: testModel()},
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.Subscribe{Ref: "no-such-ref", Rows: 24, Cols: 80})
	got := e.readControl()
	if got.FrameType() != protocol.TypeError {
		t.Fatalf("expected error frame, got %v", got.FrameType())
	}
	logs := buf.String()
	if !strings.Contains(logs, "msg=perf_subscribe") {
		t.Fatalf("subscribe path did not log perf_subscribe: %q", logs)
	}
	recv := slogInt(t, logs, "recv_ms")
	start := slogInt(t, logs, "start_ms")
	done := slogInt(t, logs, "done_ms")
	queue := slogInt(t, logs, "queue_ms")
	if recv < 0 || start < recv || done < start {
		t.Fatalf("timestamps not monotonic recv=%d start=%d done=%d line=%q", recv, start, done, logs)
	}
	if queue != start-recv {
		t.Fatalf("queue_ms=%d want start-recv=%d; line=%q", queue, start-recv, logs)
	}
}

func TestPerfSubscribeNotEmittedForList(t *testing.T) {
	var buf bytes.Buffer
	lg := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	e := startWS(t, Options{
		Token:        "test-token",
		Log:          lg,
		Discoverer:   scriptedDiscoverer{model: testModel()},
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	_ = e.readControl()
	if strings.Contains(buf.String(), "perf_subscribe") {
		t.Fatalf("list frame must not emit perf_subscribe: %q", buf.String())
	}
}

func slogInt(t *testing.T, line, key string) int64 {
	t.Helper()
	token := key + "="
	i := strings.Index(line, token)
	if i < 0 {
		t.Fatalf("missing %s in %q", key, line)
	}
	rest := line[i+len(token):]
	end := strings.IndexAny(rest, " \n\t")
	if end < 0 {
		end = len(rest)
	}
	n, err := strconv.ParseInt(rest[:end], 10, 64)
	if err != nil {
		t.Fatalf("parse %s from %q: %v", key, line, err)
	}
	return n
}
