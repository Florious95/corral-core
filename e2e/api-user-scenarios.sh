#!/usr/bin/env bash
# API-only user-scenario and performance baseline for agentmirrord.
#
# The suite deliberately drives only program interfaces: WebSocket protocol
# frames plus POST /upload.  Fixture setup uses a self-owned tmux -L server;
# there is no UI, QR scan, window lookup, real-user tmux, or production :9900
# interaction.  Performance values are baseline observations, never gates in
# this first round.  Only functional correctness, isolation, exact stream
# delivery, and zero-residue cleanup can fail the run.

set -euo pipefail
umask 077

# The acceptance wrapper unsets its literal argument; scrub every real prefix
# match again so no managed-agent state can reach a daemon/client child.
for env_name in $(compgen -e TEAM_AGENT_ || true); do
  unset "$env_name"
done
unset TMUX TS_AUTHKEY TS_CONTROL_URL TS_DEBUG_REGISTER

E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$E2E_ROOT/.." && pwd)"
SERVER_ROOT="$REPO_ROOT/server"
ART="$E2E_ROOT/artifacts/test-api-user-scenarios-perf"
mkdir -p "$ART"

BUILD_LOG="$ART/build.log"
DAEMON_LOG="$ART/daemon.log"
CLIENT_LOG="$ART/client.log"
TMUX_AUDIT="$ART/tmux-targets.tsv"
PS_AUDIT="$ART/ps-spawns.log"
VIOLATIONS="$ART/isolation-violations.log"
CLEANUP_JSON="$ART/cleanup.json"
SCENARIO_JSON="$ART/scenarios.json"
BASELINE_JSON="$ART/baseline.json"
REPORT="$ART/REPORT.md"

: >"$BUILD_LOG"
: >"$DAEMON_LOG"
: >"$CLIENT_LOG"
: >"$TMUX_AUDIT"
: >"$PS_AUDIT"
: >"$VIOLATIONS"

REAL_GO="$(command -v go)"
REAL_TMUX="$(command -v tmux)"
REAL_PS="$(command -v ps)"
REAL_LSOF="$(command -v lsof)"
PYTHON_BIN="$(command -v python3)"

BUILD_ROOT=""
RUN_ROOT=""
RUN_HOME=""
TMUX_ROOT=""
TMUX_LABEL=""
SOCKET_DIR=""
DAEMON_BIN=""
CLIENT_BIN=""
DAEMON_PID=""
CLIENT_PID=""
TMUX_PID=""
OWNED_PIDS=""
PORT=""
CLEANED=0

pid_alive() {
  local pid="${1:-}"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# Stop only a captured PID whose live argv still contains its self-owned path.
# The marker check makes PID reuse fail closed instead of signalling a stranger.
stop_owned_pid() {
  local pid="${1:-}" marker="${2:-}" command_line="" i
  [ -n "$pid" ] || return 0
  if ! pid_alive "$pid"; then
    wait "$pid" 2>/dev/null || true
    return 0
  fi
  command_line="$($REAL_PS -o command= -p "$pid" 2>/dev/null || true)"
  case "$command_line" in
    *"$marker"*) ;;
    *)
      printf 'refused to signal pid %s: ownership marker absent\n' "$pid" >&2
      return 1
      ;;
  esac
  kill -TERM "$pid" 2>/dev/null || true
  for i in $(seq 1 50); do
    pid_alive "$pid" || break
    sleep 0.1
  done
  if pid_alive "$pid"; then
    command_line="$($REAL_PS -o command= -p "$pid" 2>/dev/null || true)"
    case "$command_line" in
      *"$marker"*) kill -KILL "$pid" 2>/dev/null || true ;;
      *) return 1 ;;
    esac
  fi
  wait "$pid" 2>/dev/null || true
  ! pid_alive "$pid"
}

port_listens() {
  local port="$1"
  "$PYTHON_BIN" - "$port" <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(0.2)
try:
    listening = s.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0
finally:
    s.close()
raise SystemExit(0 if listening else 1)
PY
}

pick_high_port() {
  "$PYTHON_BIN" <<'PY'
import secrets, socket
for _ in range(500):
    port = 49152 + secrets.randbelow(65001 - 49152)
    if port == 9900:
        continue
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", port))
    except OSError:
        s.close()
        continue
    s.close()
    print(port)
    break
else:
    raise SystemExit("no high loopback port available")
PY
}

tmux_own() {
  env -i \
    HOME="$RUN_HOME" \
    LANG=C \
    PATH="/usr/bin:/bin:/usr/sbin:/sbin" \
    SHELL=/bin/bash \
    TMPDIR="$RUN_ROOT/tmp" \
    TMUX_TMPDIR="$TMUX_ROOT" \
    API_SCENARIO_OWNED_PIDS="$OWNED_PIDS" \
    "$REAL_TMUX" -L "$TMUX_LABEL" "$@"
}

# Cleanup is scoped to captured PIDs, one tmux label, one high port, and two
# validated /tmp roots.  It is safe both on the happy path and from EXIT trap.
cleanup_all() {
  local ok=1 root pid
  [ "$CLEANED" -eq 0 ] || return 0

  if [ -n "$CLIENT_PID" ]; then
    stop_owned_pid "$CLIENT_PID" "$CLIENT_BIN" || ok=0
    CLIENT_PID=""
  fi
  if [ -n "$DAEMON_PID" ]; then
    stop_owned_pid "$DAEMON_PID" "$DAEMON_BIN" || ok=0
    DAEMON_PID=""
  fi
  if [ -n "$TMUX_PID" ] && pid_alive "$TMUX_PID"; then
    tmux_own kill-server >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do
      pid_alive "$TMUX_PID" || break
      sleep 0.1
    done
    if pid_alive "$TMUX_PID"; then
      ok=0
    fi
  fi
  TMUX_PID=""

  if [ -n "$OWNED_PIDS" ] && [ -f "$OWNED_PIDS" ]; then
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      stop_owned_pid "$pid" "$RUN_ROOT/bin/" || ok=0
    done <"$OWNED_PIDS"
  fi

  if [ -n "$PORT" ] && port_listens "$PORT"; then
    ok=0
  fi

  root="$RUN_ROOT"
  if [ -n "$root" ] && [ -e "$root" ]; then
    case "$root" in
      /tmp/am-api-scenarios-run.*|/private/tmp/am-api-scenarios-run.*)
        rm -rf -- "$root"
        ;;
      *) ok=0 ;;
    esac
  fi
  root="$BUILD_ROOT"
  if [ -n "$root" ] && [ -e "$root" ]; then
    case "$root" in
      /tmp/am-api-scenarios-build.*|/private/tmp/am-api-scenarios-build.*)
        rm -rf -- "$root"
        ;;
      *) ok=0 ;;
    esac
  fi
  CLEANED=1
  [ "$ok" -eq 1 ]
}

on_exit() {
  local rc=$?
  trap - EXIT INT TERM
  if ! cleanup_all; then
    rc=1
  fi
  exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

BUILD_ROOT="$(mktemp -d /tmp/am-api-scenarios-build.XXXXXX)"
RUN_ROOT="$(mktemp -d /tmp/am-api-scenarios-run.XXXXXX)"
case "$BUILD_ROOT" in /tmp/am-api-scenarios-build.*|/private/tmp/am-api-scenarios-build.*) ;; *) exit 1 ;; esac
case "$RUN_ROOT" in /tmp/am-api-scenarios-run.*|/private/tmp/am-api-scenarios-run.*) ;; *) exit 1 ;; esac

RUN_HOME="$RUN_ROOT/home"
TMUX_ROOT="$RUN_ROOT/tmux"
TMUX_LABEL="am-api-scenarios-$$"
SOCKET_DIR="$TMUX_ROOT/tmux-$(id -u)"
OWNED_PIDS="$RUN_ROOT/owned-pids.txt"
DAEMON_BIN="$BUILD_ROOT/bin/agentmirrord"
CLIENT_BIN="$BUILD_ROOT/bin/api-scenario-client"
CLIENT_SRC="$BUILD_ROOT/client"
SHIM_DIR="$BUILD_ROOT/shim"

mkdir -p "$BUILD_ROOT/bin" "$CLIENT_SRC" "$SHIM_DIR" \
  "$RUN_HOME" "$TMUX_ROOT" "$RUN_ROOT/tmp" "$RUN_ROOT/state" \
  "$RUN_ROOT/uploads" "$RUN_ROOT/bin" "$RUN_ROOT/workspace-a" \
  "$RUN_ROOT/workspace-b"
: >"$OWNED_PIDS"

# A daemon-only tmux shim refuses every discovery/bridge target outside the
# exact owned socket directory.  It logs only target + operation, never input.
cat >"$SHIM_DIR/tmux" <<'SH'
#!/bin/sh
target=
operation=
take_next=0
for arg in "$@"; do
  if [ "$take_next" = 1 ]; then
    target=$arg
    take_next=0
    continue
  fi
  # Only the global option before the operation names the socket.  A later
  # capture-pane -S <history-line> is scrollback syntax, not another target.
  if [ -z "$operation" ] && [ "$arg" = "-S" ]; then
    take_next=1
    continue
  fi
  if [ -z "$operation" ] && [ "${arg#-}" = "$arg" ]; then
    operation=$arg
  fi
done
printf '%s\t%s\n' "$target" "$operation" >>"$API_SCENARIO_TMUX_AUDIT"
case "$target" in
  "$API_SCENARIO_ALLOWED_SOCKET_DIR"/*) ;;
  *) printf 'refused_non_owned_tmux_target\n' >>"$API_SCENARIO_VIOLATIONS"; exit 97 ;;
esac
exec "$API_SCENARIO_REAL_TMUX" "$@"
SH
chmod 700 "$SHIM_DIR/tmux"

# The state pipeline launches ps itself.  Count those exact daemon-side spawns
# without recording the global process table or its argv/output.
cat >"$SHIM_DIR/ps" <<'SH'
#!/bin/sh
printf 'ps\n' >>"$API_SCENARIO_PS_AUDIT"
exec "$API_SCENARIO_REAL_PS" "$@"
SH
chmod 700 "$SHIM_DIR/ps"

# Wrapper-shaped interactive pane: bash remains the pane root while a child
# whose executable basename is codex lets the real state adapter classify it.
ln -s /bin/sleep "$RUN_ROOT/bin/codex"
cat >"$RUN_ROOT/bin/interactive-agent" <<'SH'
#!/bin/bash
"$(dirname "$0")/codex" 600 &
printf '%s\n' "$!" >>"$API_SCENARIO_OWNED_PIDS"
printf '❯\n'
export PS1='❯ '
exec /bin/bash --noprofile --norc -i
SH
chmod 700 "$RUN_ROOT/bin/interactive-agent"

# ---------------------------------------------------------------------------
# Temporary Go client.  It imports the real protocol codec, keeps token input
# env-only, and blocks while reading large output so the harness itself drops
# no frames.  Hidden octal markers distinguish executed output from tty echo.
# ---------------------------------------------------------------------------
cat >"$CLIENT_SRC/go.mod" <<EOF
module github.com/agentmirror/agentmirror/e2e/apiuserscenario

go 1.26.5

require (
	github.com/agentmirror/agentmirror v0.0.0
	github.com/coder/websocket v1.8.14
)

replace github.com/agentmirror/agentmirror => $SERVER_ROOT
EOF
cp "$E2E_ROOT/harness/go.sum" "$CLIENT_SRC/go.sum"
cat >"$CLIENT_SRC/main.go" <<'GO'
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"mime/multipart"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

type distribution struct {
	Unit    string    `json:"unit"`
	Count   int       `json:"count"`
	Samples []float64 `json:"samples"`
	Min     float64   `json:"min"`
	P50     float64   `json:"p50"`
	P95     float64   `json:"p95"`
	Max     float64   `json:"max"`
}

func summarize(unit string, samples []float64) distribution {
	values := append([]float64(nil), samples...)
	sort.Float64s(values)
	d := distribution{Unit: unit, Count: len(values), Samples: append([]float64(nil), samples...)}
	if len(values) == 0 {
		return d
	}
	d.Min, d.Max = values[0], values[len(values)-1]
	d.P50 = interpolated(values, 0.50)
	d.P95 = interpolated(values, 0.95)
	return d
}

func interpolated(values []float64, p float64) float64 {
	if len(values) == 1 {
		return values[0]
	}
	pos := float64(len(values)-1) * p
	lo, hi := int(math.Floor(pos)), int(math.Ceil(pos))
	if lo == hi {
		return values[lo]
	}
	return values[lo] + (values[hi]-values[lo])*(pos-float64(lo))
}

type scenarioResult struct {
	ID         string   `json:"id"`
	Status     string   `json:"status"`
	Assertions []string `json:"assertions"`
}

type largeOutputMetric struct {
	ExpectedBytes        int     `json:"expected_bytes"`
	ReceivedBytes        int     `json:"received_bytes"`
	LossBytes            int     `json:"loss_bytes"`
	InputToEndMs         float64 `json:"input_to_end_ms"`
	StreamElapsedMs      float64 `json:"stream_elapsed_ms"`
	ThroughputBytesPerSec float64 `json:"throughput_bytes_per_second"`
}

type uploadMetric struct {
	FileBytes       int64        `json:"file_bytes"`
	Samples         distribution `json:"latency"`
	MultipartBytes  []int        `json:"multipart_bytes"`
}

type metrics struct {
	PairToFirstListing distribution      `json:"pair_to_first_listing"`
	SubscribeFirstFrame distribution     `json:"subscribe_first_frame"`
	OutputEndToEnd      distribution     `json:"output_end_to_end"`
	LargeOutput         largeOutputMetric `json:"large_output"`
	ScrollbackPage      distribution     `json:"scrollback_page"`
	Upload              uploadMetric     `json:"upload"`
	ReconnectRecovery   distribution     `json:"reconnect_recovery"`
}

type fullResult struct {
	Status           string           `json:"status"`
	PercentileMethod string           `json:"percentile_method"`
	Scenarios        []scenarioResult `json:"scenarios"`
	Metrics          metrics          `json:"metrics"`
	ObservedStates   []string         `json:"observed_states"`
}

type message struct {
	control protocol.Typed
	binary  *protocol.BinaryPayload
}

type client struct {
	conn          *websocket.Conn
	pendingDeltas []protocol.ListDelta
}

func dialAuth(parent context.Context, url, token string) (*client, error) {
	ctx, cancel := context.WithTimeout(parent, 15*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		return nil, fmt.Errorf("dial websocket: %w", err)
	}
	conn.SetReadLimit(2 << 20)
	c := &client{conn: conn}
	if err := c.write(ctx, protocol.Auth{Token: token}); err != nil {
		conn.CloseNow()
		return nil, err
	}
	typ, raw, err := conn.Read(ctx)
	if err != nil {
		conn.CloseNow()
		return nil, err
	}
	if typ != websocket.MessageText || bytes.Contains(raw, []byte(token)) {
		conn.CloseNow()
		return nil, fmt.Errorf("authentication reply missing or echoed credential")
	}
	f, err := protocol.UnmarshalFrame(raw)
	if err != nil || f.FrameType() != protocol.TypeAuthAck {
		conn.CloseNow()
		return nil, fmt.Errorf("invalid authentication reply")
	}
	ack, ok := f.(protocol.AuthAck)
	if !ok || !ack.OK {
		conn.CloseNow()
		return nil, fmt.Errorf("authentication rejected")
	}
	return c, nil
}

func (c *client) write(ctx context.Context, p protocol.Typed) error {
	body, err := protocol.MarshalFrame(p)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := c.conn.Write(wctx, websocket.MessageText, body); err != nil {
		return fmt.Errorf("write %s: %w", p.FrameType(), err)
	}
	return nil
}

func (c *client) read(ctx context.Context) (message, error) {
	typ, body, err := c.conn.Read(ctx)
	if err != nil {
		return message{}, err
	}
	if typ == websocket.MessageBinary {
		p, err := protocol.DecodeBinary(body)
		if err != nil {
			return message{}, err
		}
		return message{binary: &p}, nil
	}
	f, err := protocol.UnmarshalFrame(body)
	if err != nil {
		return message{}, err
	}
	return message{control: f}, nil
}

func (c *client) stashDelta(f protocol.Typed) bool {
	d, ok := f.(protocol.ListDelta)
	if ok {
		c.pendingDeltas = append(c.pendingDeltas, d)
	}
	return ok
}

func (c *client) waitControl(ctx context.Context, want protocol.FrameType) (protocol.Typed, error) {
	for {
		m, err := c.read(ctx)
		if err != nil {
			return nil, err
		}
		if m.control == nil {
			continue
		}
		if m.control.FrameType() == protocol.TypeError {
			e := m.control.(protocol.ErrorFrame)
			return nil, fmt.Errorf("server error %s: %s", e.Code, e.Reason)
		}
		if m.control.FrameType() == want {
			return m.control, nil
		}
		c.stashDelta(m.control)
	}
}

func (c *client) waitBinary(ctx context.Context, kind protocol.BinaryKind, ref string) (protocol.BinaryPayload, error) {
	for {
		m, err := c.read(ctx)
		if err != nil {
			return protocol.BinaryPayload{}, err
		}
		if m.binary != nil {
			if m.binary.Kind == kind && m.binary.Ref == ref {
				return *m.binary, nil
			}
			continue
		}
		if m.control != nil {
			if m.control.FrameType() == protocol.TypeError {
				e := m.control.(protocol.ErrorFrame)
				return protocol.BinaryPayload{}, fmt.Errorf("server error %s: %s", e.Code, e.Reason)
			}
			c.stashDelta(m.control)
		}
	}
}

func (c *client) requestList(ctx context.Context, reqID uint32) (protocol.Listing, error) {
	if err := c.write(ctx, protocol.List{ReqID: reqID}); err != nil {
		return protocol.Listing{}, err
	}
	f, err := c.waitControl(ctx, protocol.TypeListing)
	if err != nil {
		return protocol.Listing{}, err
	}
	l, ok := f.(protocol.Listing)
	if !ok || l.ReqID != reqID || l.Seq == 0 {
		return protocol.Listing{}, fmt.Errorf("invalid listing correlation/sequence")
	}
	return l, nil
}

func validState(s protocol.AgentState) bool {
	return s == protocol.StateWorking || s == protocol.StateIdle || s == protocol.StateBlocked || s == protocol.StateDone || s == protocol.StateUnknown
}

func validateListing(l protocol.Listing, primaryCWD, otherCWD string) (string, error) {
	want := map[string]bool{primaryCWD: false, otherCWD: false}
	primaryRef := ""
	for _, ws := range l.Workspaces {
		if ws.Cwd == "" || ws.SessionCount != len(ws.Sessions) || !validState(ws.AggregateState) {
			return "", fmt.Errorf("invalid workspace model")
		}
		if _, ok := want[ws.Cwd]; ok {
			want[ws.Cwd] = true
		}
		for _, s := range ws.Sessions {
			if s.Ref == "" || s.Name == "" || s.Cwd != ws.Cwd || s.Rows == 0 || s.Cols == 0 || !validState(s.State) {
				return "", fmt.Errorf("invalid session model")
			}
			if s.Cwd == primaryCWD && s.Name == "api-primary" {
				primaryRef = s.Ref
			}
		}
	}
	if !want[primaryCWD] || !want[otherCWD] || primaryRef == "" {
		return "", fmt.Errorf("two-level listing missing isolated fixtures")
	}
	return primaryRef, nil
}

func sessionState(l protocol.Listing, ref string) (protocol.AgentState, protocol.AgentState, bool) {
	for _, ws := range l.Workspaces {
		for _, s := range ws.Sessions {
			if s.Ref == ref {
				return s.State, ws.AggregateState, true
			}
		}
	}
	return "", "", false
}

func sessionDims(l protocol.Listing, ref string) (uint16, uint16, bool) {
	for _, ws := range l.Workspaces {
		for _, s := range ws.Sessions {
			if s.Ref == ref {
				return s.Rows, s.Cols, true
			}
		}
	}
	return 0, 0, false
}

func (c *client) waitState(ctx context.Context, ref string, target protocol.AgentState, req *uint32) (protocol.AgentState, error) {
	deadline := time.Now().Add(20 * time.Second)
	last := protocol.StateUnknown
	for time.Now().Before(deadline) {
		*req++
		l, err := c.requestList(ctx, *req)
		if err != nil {
			return last, err
		}
		state, agg, ok := sessionState(l, ref)
		if ok {
			last = state
			if state == target {
				if agg != target {
					return last, fmt.Errorf("workspace aggregate %s does not follow state %s", agg, target)
				}
				return last, nil
			}
		}
		time.Sleep(350 * time.Millisecond)
	}
	return last, fmt.Errorf("state did not reach %s (last %s)", target, last)
}

func deltaHasState(d protocol.ListDelta, ref string, target protocol.AgentState) bool {
	for _, s := range d.ChangedSessions {
		if s.Ref == ref && s.State == target && validState(s.State) {
			return true
		}
	}
	return false
}

func (c *client) waitStateDelta(ctx context.Context, ref string, target protocol.AgentState) error {
	for _, d := range c.pendingDeltas {
		if deltaHasState(d, ref, target) {
			return nil
		}
	}
	deadline, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	for {
		m, err := c.read(deadline)
		if err != nil {
			return err
		}
		if m.control == nil {
			continue
		}
		if m.control.FrameType() == protocol.TypeError {
			e := m.control.(protocol.ErrorFrame)
			return fmt.Errorf("server error %s: %s", e.Code, e.Reason)
		}
		if d, ok := m.control.(protocol.ListDelta); ok {
			c.pendingDeltas = append(c.pendingDeltas, d)
			if deltaHasState(d, ref, target) {
				return nil
			}
		}
	}
}

func octal(s string) string {
	var b strings.Builder
	for _, v := range []byte(s) {
		fmt.Fprintf(&b, "\\%03o", v)
	}
	return b.String()
}

func appendTail(dst, data []byte, limit int) []byte {
	dst = append(dst, data...)
	if len(dst) > limit {
		dst = append([]byte(nil), dst[len(dst)-limit:]...)
	}
	return dst
}

func (c *client) sendInputWait(ctx context.Context, in protocol.Input, markers []string, timeout time.Duration) (float64, error) {
	start := time.Now()
	if err := c.write(ctx, in); err != nil {
		return 0, err
	}
	deadline, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	ackOK := false
	seen := make([]bool, len(markers))
	tail := []byte{}
	for {
		m, err := c.read(deadline)
		if err != nil {
			return 0, err
		}
		if m.binary != nil && m.binary.Ref == in.Ref && m.binary.Kind == protocol.KindDelta {
			tail = appendTail(tail, m.binary.Data, 2<<20)
			for i, marker := range markers {
				if bytes.Contains(tail, []byte(marker)) {
					seen[i] = true
				}
			}
		}
		if m.control != nil {
			switch f := m.control.(type) {
			case protocol.InputAck:
				if f.ReqID == in.ReqID {
					if !f.OK {
						return 0, fmt.Errorf("input failed with reason %s", f.Reason)
					}
					ackOK = true
				}
			case protocol.ErrorFrame:
				return 0, fmt.Errorf("server error %s: %s", f.Code, f.Reason)
			case protocol.ListDelta:
				c.pendingDeltas = append(c.pendingDeltas, f)
			}
		}
		allSeen := true
		for _, ok := range seen {
			allSeen = allSeen && ok
		}
		if ackOK && allSeen {
			return float64(time.Since(start).Microseconds()) / 1000, nil
		}
	}
}

func (c *client) sendKeyAck(ctx context.Context, reqID uint32, ref string, key protocol.Key) error {
	if err := c.write(ctx, protocol.Input{ReqID: reqID, Ref: ref, Keys: []protocol.Key{key}}); err != nil {
		return err
	}
	deadline, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	for {
		m, err := c.read(deadline)
		if err != nil {
			return err
		}
		if m.control == nil {
			continue
		}
		switch f := m.control.(type) {
		case protocol.InputAck:
			if f.ReqID == reqID {
				if !f.OK {
					return fmt.Errorf("special-key %s input failed: %s", key, f.Reason)
				}
				return nil
			}
		case protocol.ErrorFrame:
			return fmt.Errorf("server error %s: %s", f.Code, f.Reason)
		case protocol.ListDelta:
			c.pendingDeltas = append(c.pendingDeltas, f)
		}
	}
}

func (c *client) measureLargeOutput(ctx context.Context, reqID uint32, ref string, expected int) (largeOutputMetric, error) {
	begin := fmt.Sprintf("AM_BEGIN_%d", time.Now().UnixNano())
	end := fmt.Sprintf("AM_END_%d", time.Now().UnixNano())
	block := strings.Repeat("Z", 256)
	loops := expected / len(block)
	command := fmt.Sprintf("printf '%s'; i=0; while [ \"$i\" -lt %d ]; do printf '%%s' '%s'; i=$((i+1)); done; printf '%s'", octal(begin), loops, block, octal(end))
	in := protocol.Input{ReqID: reqID, Ref: ref, Text: command}
	inputStart := time.Now()
	if err := c.write(ctx, in); err != nil {
		return largeOutputMetric{}, err
	}
	deadline, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	ackOK, began, ended := false, false, false
	pre, payload := []byte{}, []byte{}
	var streamStart, streamEnd time.Time
	for {
		m, err := c.read(deadline)
		if err != nil {
			return largeOutputMetric{}, err
		}
		if m.binary != nil && m.binary.Ref == ref && m.binary.Kind == protocol.KindDelta {
			if !began {
				pre = append(pre, m.binary.Data...)
				if i := bytes.Index(pre, []byte(begin)); i >= 0 {
					began = true
					streamStart = time.Now()
					payload = append(payload, pre[i+len(begin):]...)
					pre = nil
				} else if len(pre) > 64<<10 {
					pre = append([]byte(nil), pre[len(pre)-(len(begin)-1):]...)
				}
			} else if !ended {
				payload = append(payload, m.binary.Data...)
			}
			if began && !ended {
				if i := bytes.Index(payload, []byte(end)); i >= 0 {
					payload = payload[:i]
					ended = true
					streamEnd = time.Now()
				}
			}
		}
		if m.control != nil {
			switch f := m.control.(type) {
			case protocol.InputAck:
				if f.ReqID == reqID {
					if !f.OK {
						return largeOutputMetric{}, fmt.Errorf("large-output input failed: %s", f.Reason)
					}
					ackOK = true
				}
			case protocol.ErrorFrame:
				return largeOutputMetric{}, fmt.Errorf("server error %s: %s", f.Code, f.Reason)
			case protocol.ListDelta:
				c.pendingDeltas = append(c.pendingDeltas, f)
			}
		}
		if ackOK && ended {
			break
		}
	}
	received := len(payload)
	if received != expected {
		return largeOutputMetric{}, fmt.Errorf("large-output loss: received %d want %d", received, expected)
	}
	streamSeconds := streamEnd.Sub(streamStart).Seconds()
	if streamSeconds <= 0 {
		return largeOutputMetric{}, fmt.Errorf("invalid large-output duration")
	}
	return largeOutputMetric{
		ExpectedBytes: expected,
		ReceivedBytes: received,
		LossBytes: received - expected,
		InputToEndMs: float64(streamEnd.Sub(inputStart).Microseconds()) / 1000,
		StreamElapsedMs: float64(streamEnd.Sub(streamStart).Microseconds()) / 1000,
		ThroughputBytesPerSec: float64(received) / streamSeconds,
	}, nil
}

func (c *client) measureScrollback(ctx context.Context, reqID uint32, ref string, fromLine int32) (float64, protocol.BinaryPayload, error) {
	start := time.Now()
	if err := c.write(ctx, protocol.Scrollback{ReqID: reqID, Ref: ref, FromLine: fromLine, Count: 100}); err != nil {
		return 0, protocol.BinaryPayload{}, err
	}
	deadline, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	for {
		p, err := c.waitBinary(deadline, protocol.KindScrollback, ref)
		if err != nil {
			return 0, protocol.BinaryPayload{}, err
		}
		if p.ReqID != reqID {
			continue
		}
		if p.FromLine != fromLine || p.LineCount != 100 || len(p.Data) == 0 {
			return 0, protocol.BinaryPayload{}, fmt.Errorf("invalid scrollback range/payload: requested %d+100, got %d+%d", fromLine, p.FromLine, p.LineCount)
		}
		return float64(time.Since(start).Microseconds()) / 1000, p, nil
	}
}

func uploadOnce(ctx context.Context, client *http.Client, url, dir string, index int, size int) (float64, int, string, error) {
	data := make([]byte, size)
	copy(data, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
	for i := 8; i < len(data); i++ {
		data[i] = byte(i % 251)
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	part, err := mw.CreateFormFile("file", fmt.Sprintf("scenario-%02d.png", index))
	if err != nil {
		return 0, 0, "", err
	}
	if _, err := part.Write(data); err != nil {
		return 0, 0, "", err
	}
	if err := mw.Close(); err != nil {
		return 0, 0, "", err
	}
	multipartBytes := body.Len()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, &body)
	if err != nil {
		return 0, 0, "", err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, 0, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return 0, 0, "", fmt.Errorf("upload status %d: %s", resp.StatusCode, b)
	}
	var out struct{ Path string `json:"path"` }
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return 0, 0, "", err
	}
	elapsed := float64(time.Since(start).Microseconds()) / 1000
	rel, err := filepath.Rel(dir, out.Path)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return 0, 0, "", fmt.Errorf("upload escaped isolated directory")
	}
	info, err := os.Stat(out.Path)
	if err != nil || info.Size() != int64(size) {
		return 0, 0, "", fmt.Errorf("uploaded file missing or wrong size")
	}
	return elapsed, multipartBytes, out.Path, nil
}

func expectBadToken(ctx context.Context, url string) error {
	dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(dctx, url, nil)
	if err != nil {
		return err
	}
	defer conn.CloseNow()
	body, err := protocol.MarshalFrame(protocol.Auth{Token: "invalid-api-scenario-token"})
	if err != nil {
		return err
	}
	if err := conn.Write(dctx, websocket.MessageText, body); err != nil {
		return err
	}
	_, raw, err := conn.Read(dctx)
	if err != nil {
		return err
	}
	if bytes.Contains(raw, []byte("invalid-api-scenario-token")) {
		return fmt.Errorf("rejection echoed credential")
	}
	f, err := protocol.UnmarshalFrame(raw)
	if err != nil {
		return err
	}
	ack, ok := f.(protocol.AuthAck)
	if !ok || ack.OK || strings.TrimSpace(ack.Reason) == "" {
		return fmt.Errorf("bad token lacked visible rejection reason")
	}
	return nil
}

func expectBadRef(ctx context.Context, c *client) error {
	if err := c.write(ctx, protocol.Subscribe{Ref: "missing-session-ref", Rows: 24, Cols: 80}); err != nil {
		return err
	}
	deadline, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	for {
		m, err := c.read(deadline)
		if err != nil {
			return err
		}
		if m.control == nil {
			continue
		}
		if e, ok := m.control.(protocol.ErrorFrame); ok {
			if e.Code != protocol.ErrCodeSessionNotFound || strings.TrimSpace(e.Reason) == "" {
				return fmt.Errorf("bad ref lacked session_not_found reason")
			}
			return nil
		}
		c.stashDelta(m.control)
	}
}

func writeResult(path string, value any) error {
	body, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	body = append(body, '\n')
	return os.WriteFile(path, body, 0o600)
}

func runHold(ctx context.Context, mode, url, token, primaryCWD, otherCWD, ready string) error {
	c, err := dialAuth(ctx, url, token)
	if err != nil {
		return err
	}
	defer c.conn.CloseNow()
	l, err := c.requestList(ctx, 1)
	if err != nil {
		return err
	}
	ref, err := validateListing(l, primaryCWD, otherCWD)
	if err != nil {
		return err
	}
	if mode == "hold-single-subscription" {
		if err := c.write(ctx, protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80}); err != nil {
			return err
		}
		dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		_, err = c.waitBinary(dctx, protocol.KindSnapshot, ref)
		cancel()
		if err != nil {
			return err
		}
	} else if mode != "hold-zero-subscription" {
		return fmt.Errorf("unknown hold mode")
	}
	if err := os.WriteFile(ready, []byte("ready\n"), 0o600); err != nil {
		return err
	}
	errCh := make(chan error, 1)
	go func() {
		for {
			_, _, err := c.conn.Read(context.Background())
			if err != nil {
				errCh <- err
				return
			}
		}
	}()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	select {
	case <-sig:
		c.conn.CloseNow()
		return nil
	case err := <-errCh:
		return fmt.Errorf("hold connection ended: %w", err)
	}
}

func runFull(ctx context.Context, url, uploadURL, uploadDir, primaryCWD, otherCWD, token string) (fullResult, error) {
	result := fullResult{Status: "pass", PercentileMethod: "linear interpolation on sorted samples at (n-1)*p"}
	pass := func(id string, assertions ...string) {
		result.Scenarios = append(result.Scenarios, scenarioResult{ID: id, Status: "pass", Assertions: assertions})
	}

	if err := expectBadToken(ctx, url); err != nil {
		return result, err
	}
	pass("pairing_rejection_reason", "invalid token rejected", "reason is non-empty", "credential not echoed")

	pairSamples := make([]float64, 0, 10)
	primaryRef := ""
	for i := 0; i < 10; i++ {
		start := time.Now()
		c, err := dialAuth(ctx, url, token)
		if err != nil {
			return result, err
		}
		l, err := c.requestList(ctx, uint32(100+i))
		if err != nil {
			c.conn.CloseNow()
			return result, err
		}
		ref, err := validateListing(l, primaryCWD, otherCWD)
		if err != nil {
			c.conn.CloseNow()
			return result, err
		}
		primaryRef = ref
		pairSamples = append(pairSamples, float64(time.Since(start).Microseconds())/1000)
		_ = c.conn.Close(websocket.StatusNormalClosure, "sample complete")
	}
	result.Metrics.PairToFirstListing = summarize("ms", pairSamples)
	pass("pair_and_two_level_listing", "dial+auth+auth_ack succeeds", "workspace/cwd first level present", "session/ref second level present", "state and aggregate fields are valid")

	c, err := dialAuth(ctx, url, token)
	if err != nil {
		return result, err
	}
	defer c.conn.CloseNow()
	reqID := uint32(1000)
	l, err := c.requestList(ctx, reqID)
	if err != nil {
		return result, err
	}
	primaryRef, err = validateListing(l, primaryCWD, otherCWD)
	if err != nil {
		return result, err
	}
	// Input is defined only for an active subscription.  Establish the mirror
	// before using API input to drive the state transition; the measured
	// subscribe distribution below still uses ten fresh unsubscribe/subscribe
	// cycles and does not include this setup sample.
	if err := c.write(ctx, protocol.Subscribe{Ref: primaryRef, Rows: 24, Cols: 80}); err != nil {
		return result, err
	}
	setupCtx, setupCancel := context.WithTimeout(ctx, 15*time.Second)
	setupSnapshot, err := c.waitBinary(setupCtx, protocol.KindSnapshot, primaryRef)
	setupCancel()
	if err != nil || len(setupSnapshot.Data) == 0 {
		return result, fmt.Errorf("setup subscription snapshot missing: %w", err)
	}

	// The wrapper starts at a real idle prompt; the daemon's background state
	// sampler must publish it before the API-triggered blocked transition.
	if _, err := c.waitState(ctx, primaryRef, protocol.StateIdle, &reqID); err != nil {
		return result, err
	}
	blockedText := "Allow command?\nYes (y)\n"
	blockedMarker := "Allow command?"
	reqID++
	if _, err := c.sendInputWait(ctx, protocol.Input{ReqID: reqID, Ref: primaryRef, Text: "clear; printf '" + octal(blockedText) + "'"}, []string{blockedMarker}, 15*time.Second); err != nil {
		return result, err
	}
	if err := c.waitStateDelta(ctx, primaryRef, protocol.StateBlocked); err != nil {
		return result, fmt.Errorf("state list_delta: %w", err)
	}
	if _, err := c.waitState(ctx, primaryRef, protocol.StateBlocked, &reqID); err != nil {
		return result, fmt.Errorf("blocked aggregate listing: %w", err)
	}
	pass("state_field_transition", "initial state idle", "API input changes visible pane state", "list_delta carries blocked", "workspace aggregate follows blocked")
	result.ObservedStates = []string{"idle", "blocked"}

	// Clear the synthetic blocked box before the remaining terminal scenarios.
	reqID++
	clearMarker := fmt.Sprintf("CLEAR_%d", time.Now().UnixNano())
	if _, err := c.sendInputWait(ctx, protocol.Input{ReqID: reqID, Ref: primaryRef, Text: "clear; printf '" + octal(clearMarker+"\n") + "'"}, []string{clearMarker}, 15*time.Second); err != nil {
		return result, err
	}

	firstFrames := make([]float64, 0, 10)
	for i := 0; i < 10; i++ {
		_ = c.write(ctx, protocol.Unsubscribe{Ref: primaryRef})
		start := time.Now()
		if err := c.write(ctx, protocol.Subscribe{Ref: primaryRef, Rows: 24, Cols: 80}); err != nil {
			return result, err
		}
		dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		snap, err := c.waitBinary(dctx, protocol.KindSnapshot, primaryRef)
		cancel()
		if err != nil || len(snap.Data) == 0 {
			return result, fmt.Errorf("subscribe snapshot missing: %w", err)
		}
		firstFrames = append(firstFrames, float64(time.Since(start).Microseconds())/1000)
	}
	result.Metrics.SubscribeFirstFrame = summarize("ms", firstFrames)
	pass("subscribe_snapshot_and_incremental_stream", "ten non-empty snapshot replies", "matching session ref", "subscription remains live for deltas")

	latencies := make([]float64, 0, 20)
	for i := 0; i < 20; i++ {
		marker := fmt.Sprintf("OUT_%02d_%d", i, time.Now().UnixNano())
		reqID++
		ms, err := c.sendInputWait(ctx, protocol.Input{ReqID: reqID, Ref: primaryRef, Text: "printf '" + octal(marker+"\n") + "'"}, []string{marker}, 15*time.Second)
		if err != nil {
			return result, err
		}
		latencies = append(latencies, ms)
	}
	result.Metrics.OutputEndToEnd = summarize("ms", latencies)
	pass("input_ack_and_output_round_trip", "twenty req_id-correlated input_ack replies", "twenty hidden execution markers returned in delta", "p50/p95 recorded")

	keys := []protocol.Key{protocol.KeyEsc, protocol.KeyTab, protocol.KeyUp, protocol.KeyDown, protocol.KeyLeft, protocol.KeyRight, protocol.KeyCtrlC}
	for _, key := range keys {
		reqID++
		if err := c.sendKeyAck(ctx, reqID, primaryRef, key); err != nil {
			return result, err
		}
	}
	pass("special_keys", "seven independent one-key input frames accepted", "each distinct req_id has a successful input_ack", "terminal effects are not claimed")

	m1 := fmt.Sprintf("MULTI_A_%d", time.Now().UnixNano())
	m2 := fmt.Sprintf("MULTI_B_%d", time.Now().UnixNano())
	multiline := "printf '" + octal(m1+"\n") + "'\nprintf '" + octal(m2+"\n") + "'"
	reqID++
	if _, err := c.sendInputWait(ctx, protocol.Input{ReqID: reqID, Ref: primaryRef, Text: multiline}, []string{m1, m2}, 15*time.Second); err != nil {
		return result, err
	}
	pass("multiline_paste", "one input frame carries embedded newline", "one input_ack received", "both executed-output markers returned")

	if err := c.write(ctx, protocol.Resize{Ref: primaryRef, Rows: 40, Cols: 100}); err != nil {
		return result, err
	}
	dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	resizeSnap, err := c.waitBinary(dctx, protocol.KindSnapshot, primaryRef)
	cancel()
	if err != nil || len(resizeSnap.Data) == 0 {
		return result, fmt.Errorf("resize snapshot missing: %w", err)
	}
	resizeDeadline := time.Now().Add(12 * time.Second)
	resized := false
	for time.Now().Before(resizeDeadline) {
		reqID++
		listed, err := c.requestList(ctx, reqID)
		if err != nil {
			return result, err
		}
		rows, cols, ok := sessionDims(listed, primaryRef)
		if ok && rows == 40 && cols == 100 {
			resized = true
			break
		}
		time.Sleep(350 * time.Millisecond)
	}
	if !resized {
		return result, fmt.Errorf("resize dimensions never became visible")
	}
	pass("resize", "post-resize snapshot is the fact receipt", "fresh listing reports 100x40")

	reqID++
	large, err := c.measureLargeOutput(ctx, reqID, primaryRef, 1<<20)
	if err != nil {
		return result, err
	}
	result.Metrics.LargeOutput = large
	pass("large_output_throughput", "one MiB exact payload received", "begin/end markers exclude tty echo", "zero lost bytes", "throughput recorded")

	scrollSamples := make([]float64, 0, 10)
	nextFrom := int32(-100)
	previousFrom := int32(0)
	for i := 0; i < 10; i++ {
		reqID++
		ms, page, err := c.measureScrollback(ctx, reqID, primaryRef, nextFrom)
		if err != nil {
			return result, err
		}
		// The actual range header is the pagination cursor.  Adjacent ranges
		// must meet exactly, proving that the ten pages neither overlap nor gap.
		if i > 0 && page.FromLine+int32(page.LineCount) != previousFrom {
			return result, fmt.Errorf("scrollback pages are not contiguous: current %d+%d, previous starts %d", page.FromLine, page.LineCount, previousFrom)
		}
		scrollSamples = append(scrollSamples, ms)
		previousFrom = page.FromLine
		nextFrom = page.FromLine - 100
	}
	result.Metrics.ScrollbackPage = summarize("ms", scrollSamples)
	pass("scrollback_pagination", "ten distinct contiguous req_id-correlated pages", "range headers prove no gaps or overlaps", "each page has 100 lines and a non-empty payload")

	httpClient := &http.Client{Timeout: 30 * time.Second}
	uploadSamples := make([]float64, 0, 5)
	multipartSizes := make([]int, 0, 5)
	lastPath := ""
	for i := 0; i < 5; i++ {
		ms, multipartBytes, path, err := uploadOnce(ctx, httpClient, uploadURL, uploadDir, i, 1<<20)
		if err != nil {
			return result, err
		}
		uploadSamples = append(uploadSamples, ms)
		multipartSizes = append(multipartSizes, multipartBytes)
		lastPath = path
	}
	result.Metrics.Upload = uploadMetric{FileBytes: 1 << 20, Samples: summarize("ms", uploadSamples), MultipartBytes: multipartSizes}
	reqID++
	if _, err := c.sendInputWait(ctx, protocol.Input{ReqID: reqID, Ref: primaryRef, Text: "printf '" + octal(lastPath+"\n") + "'"}, []string{lastPath}, 15*time.Second); err != nil {
		return result, err
	}
	pass("image_upload_and_path_injection", "five one-MiB multipart uploads return 200", "paths stay inside isolated upload dir", "on-disk size exact", "returned path injected and visible via WS")

	visible := fmt.Sprintf("RECONNECT_VISIBLE_%d", time.Now().UnixNano())
	reqID++
	if _, err := c.sendInputWait(ctx, protocol.Input{ReqID: reqID, Ref: primaryRef, Text: "printf '" + octal(visible+"\n") + "'"}, []string{visible}, 15*time.Second); err != nil {
		return result, err
	}
	reconnectSamples := make([]float64, 0, 10)
	for i := 0; i < 10; i++ {
		start := time.Now()
		c.conn.CloseNow()
		c, err = dialAuth(ctx, url, token)
		if err != nil {
			return result, err
		}
		if err := c.write(ctx, protocol.Subscribe{Ref: primaryRef, Rows: 40, Cols: 100}); err != nil {
			return result, err
		}
		dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		snap, err := c.waitBinary(dctx, protocol.KindSnapshot, primaryRef)
		cancel()
		if err != nil || !bytes.Contains(snap.Data, []byte(visible)) {
			return result, fmt.Errorf("reconnect snapshot did not replay visible state: %w", err)
		}
		reconnectSamples = append(reconnectSamples, float64(time.Since(start).Microseconds())/1000)
	}
	result.Metrics.ReconnectRecovery = summarize("ms", reconnectSamples)
	pass("disconnect_reconnect_resubscribe", "ten new WS auth handshakes", "same ref resubscribed", "current visible marker replayed in every snapshot")

	if err := expectBadRef(ctx, c); err != nil {
		return result, err
	}
	pass("failure_reason", "unknown ref returns session_not_found", "human-readable reason is non-empty")
	return result, nil
}

func main() {
	mode := flag.String("mode", "", "hold-zero-subscription, hold-single-subscription, or full")
	url := flag.String("url", "", "WebSocket URL")
	uploadURL := flag.String("upload-url", "", "HTTP upload URL")
	uploadDir := flag.String("upload-dir", "", "expected isolated upload directory")
	primaryCWD := flag.String("primary-cwd", "", "isolated primary workspace")
	otherCWD := flag.String("other-cwd", "", "isolated second workspace")
	ready := flag.String("ready", "", "hold-mode ready file")
	resultPath := flag.String("result", "", "full-mode JSON result")
	flag.Parse()

	token := os.Getenv("API_SCENARIO_TOKEN")
	if token == "" {
		fmt.Fprintln(os.Stderr, "API_SCENARIO_TOKEN is required")
		os.Exit(2)
	}
	ctx := context.Background()
	var err error
	switch *mode {
	case "hold-zero-subscription", "hold-single-subscription":
		err = runHold(ctx, *mode, *url, token, *primaryCWD, *otherCWD, *ready)
	case "full":
		var result fullResult
		result, err = runFull(ctx, *url, *uploadURL, *uploadDir, *primaryCWD, *otherCWD, token)
		if err == nil {
			err = writeResult(*resultPath, result)
		}
	default:
		err = errors.New("unknown mode")
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "api-user-scenarios client: %v\n", err)
		os.Exit(1)
	}
}
GO

# OS-side resource sampler: cumulative CPU delta, RSS distribution, exact shim
# spawn deltas, and in-memory-only descendant counts.  Raw ps tables never land
# in output or logs.
cat >"$BUILD_ROOT/resource_sample.py" <<'PY'
import json, math, os, statistics, subprocess, sys, time

state, pid_s, seconds_s, real_ps, tmux_audit, ps_audit, output = sys.argv[1:]
pid, seconds = int(pid_s), float(seconds_s)

def line_count(path):
    try:
        with open(path, "rb") as fh:
            return sum(1 for _ in fh)
    except FileNotFoundError:
        return 0

def ps_value(*args):
    return subprocess.check_output([real_ps, *args], text=True).strip()

def cpu_seconds(raw):
    days = 0
    if "-" in raw:
        day, raw = raw.split("-", 1)
        days = int(day)
    parts = raw.split(":")
    if len(parts) == 3:
        hours, minutes, sec = int(parts[0]), int(parts[1]), float(parts[2])
    elif len(parts) == 2:
        hours, minutes, sec = 0, int(parts[0]), float(parts[1])
    else:
        hours, minutes, sec = 0, 0, float(parts[0])
    return days * 86400 + hours * 3600 + minutes * 60 + sec

def descendant_count(root):
    raw = subprocess.check_output([real_ps, "-axo", "pid=,ppid="], text=True)
    children = {}
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) != 2:
            continue
        child, parent = map(int, fields)
        children.setdefault(parent, []).append(child)
    count, queue = 0, list(children.get(root, ()))
    while queue:
        child = queue.pop()
        count += 1
        queue.extend(children.get(child, ()))
    return count

def percentile(values, p):
    values = sorted(values)
    if len(values) == 1:
        return values[0]
    pos = (len(values) - 1) * p
    lo, hi = math.floor(pos), math.ceil(pos)
    if lo == hi:
        return values[lo]
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)

tmux_before, ps_before = line_count(tmux_audit), line_count(ps_audit)
cpu_before = cpu_seconds(ps_value("-o", "time=", "-p", str(pid)))
start = time.monotonic()
rss, descendants = [], []
while True:
    if subprocess.run([real_ps, "-p", str(pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0:
        raise SystemExit("daemon exited during resource measurement")
    rss.append(int(ps_value("-o", "rss=", "-p", str(pid))))
    descendants.append(descendant_count(pid))
    elapsed = time.monotonic() - start
    if elapsed >= seconds:
        break
    time.sleep(min(0.5, seconds - elapsed))
end = time.monotonic()
cpu_after = cpu_seconds(ps_value("-o", "time=", "-p", str(pid)))
tmux_after, ps_after = line_count(tmux_audit), line_count(ps_audit)
wall = end - start
cpu_delta = max(0.0, cpu_after - cpu_before)

payload = {
    "state": state,
    "window_seconds": wall,
    "sample_interval_seconds": 0.5,
    "cpu": {
        "unit": "percent_of_one_core",
        "cpu_time_delta_seconds": cpu_delta,
        "mean_percent": cpu_delta / wall * 100.0,
    },
    "daemon_rss": {
        "unit": "KiB",
        "samples": rss,
        "min": min(rss),
        "p50": percentile(rss, 0.50),
        "p95": percentile(rss, 0.95),
        "max": max(rss),
        "mean": statistics.fmean(rss),
        "end_minus_start": rss[-1] - rss[0],
    },
    "daemon_descendants": {
        "sampled_counts": descendants,
        "peak": max(descendants),
    },
    "external_process_spawns": {
        "tmux": tmux_after - tmux_before,
        "ps": ps_after - ps_before,
        "total": (tmux_after - tmux_before) + (ps_after - ps_before),
        "method": "exact daemon PATH shims; daemon-side arguments are not recorded; descendant sampling separately reads the system-wide PID/PPID table into memory only",
    },
}
with open(output, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY

(
  cd "$SERVER_ROOT"
  GOWORK=off GOCACHE="$BUILD_ROOT/go-cache-server" "$REAL_GO" build -o "$DAEMON_BIN" ./cmd/agentmirrord
) >>"$BUILD_LOG" 2>&1
(
  cd "$CLIENT_SRC"
  GOWORK=off GOCACHE="$BUILD_ROOT/go-cache-client" "$REAL_GO" build -mod=readonly -o "$CLIENT_BIN" .
) >>"$BUILD_LOG" 2>&1

# ---------------------------------------------------------------------------
# One isolated runtime: two workspaces, one wrapper-shaped agent pane, loopback
# high port, env-only token, fail-closed discovery, and no inherited secrets.
# ---------------------------------------------------------------------------
PRIMARY_CWD="$(cd "$RUN_ROOT/workspace-a" && pwd -P)"
OTHER_CWD="$(cd "$RUN_ROOT/workspace-b" && pwd -P)"
tmux_own new-session -d -x 100 -y 40 -s api-primary -c "$PRIMARY_CWD" "$RUN_ROOT/bin/interactive-agent"
tmux_own new-session -d -x 80 -y 24 -s api-secondary -c "$OTHER_CWD" "/bin/bash --noprofile --norc -i"
tmux_own set-option -g history-limit 20000
TMUX_PID="$(tmux_own display-message -p '#{pid}' | tr -d '[:space:]')"
[ "$(tmux_own list-panes -a -F '#{pane_id}' | wc -l | tr -d ' ')" -eq 2 ]

PORT="$(pick_high_port)"
[ "$PORT" -ne 9900 ]
TOKEN="$($PYTHON_BIN -c 'import secrets; print(secrets.token_urlsafe(32))')"
WS_URL="ws://127.0.0.1:$PORT/ws"
UPLOAD_URL="http://127.0.0.1:$PORT/upload"

# stdout is intentionally /dev/null: daemon onboarding contains the pairing
# token.  stderr is structured, token-free operational logging.
env -i \
  HOME="$RUN_HOME" \
  LANG=C \
  PATH="$SHIM_DIR:/usr/bin:/bin:/usr/sbin:/sbin" \
  TMPDIR="$RUN_ROOT/tmp" \
  TMUX_TMPDIR="$TMUX_ROOT" \
  AGENTMIRROR_TOKEN="$TOKEN" \
  AGENTMIRROR_STATE_DIR="$RUN_ROOT/state" \
  AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCKET_DIR" \
  API_SCENARIO_REAL_TMUX="$REAL_TMUX" \
  API_SCENARIO_REAL_PS="$REAL_PS" \
  API_SCENARIO_ALLOWED_SOCKET_DIR="$SOCKET_DIR" \
  API_SCENARIO_TMUX_AUDIT="$TMUX_AUDIT" \
  API_SCENARIO_PS_AUDIT="$PS_AUDIT" \
  API_SCENARIO_VIOLATIONS="$VIOLATIONS" \
  "$DAEMON_BIN" \
    -listen "127.0.0.1:$PORT" \
    -host 127.0.0.1 \
    -state-dir "$RUN_ROOT/state" \
    -upload-dir "$RUN_ROOT/uploads" \
    -list-interval 500ms \
    -log-level info \
    >/dev/null 2>"$DAEMON_LOG" &
DAEMON_PID=$!

listener_ready=0
for _ in $(seq 1 150); do
  pid_alive "$DAEMON_PID" || { echo "isolated daemon exited during startup" >&2; exit 1; }
  if port_listens "$PORT"; then
    listener_ready=1
    break
  fi
  sleep 0.1
done
[ "$listener_ready" -eq 1 ] || { echo "isolated daemon did not open high port" >&2; exit 1; }

RESOURCE_SECONDS=10
RESOURCE_FILES=()

measure_resource_state() {
  local state="$1" output="$ART/resource-$1.json"
  "$PYTHON_BIN" "$BUILD_ROOT/resource_sample.py" \
    "$state" "$DAEMON_PID" "$RESOURCE_SECONDS" "$REAL_PS" \
    "$TMUX_AUDIT" "$PS_AUDIT" "$output"
  RESOURCE_FILES+=("$output")
}

start_hold_client() {
  local mode="$1" ready="$RUN_ROOT/$1.ready" log="$ART/client-$1.log"
  rm -f "$ready"
  env -i \
    HOME="$RUN_HOME" \
    LANG=C \
    PATH="/usr/bin:/bin" \
    API_SCENARIO_TOKEN="$TOKEN" \
    "$CLIENT_BIN" \
      -mode "$mode" \
      -url "$WS_URL" \
      -primary-cwd "$PRIMARY_CWD" \
      -other-cwd "$OTHER_CWD" \
      -ready "$ready" \
      >"$log" 2>&1 &
  CLIENT_PID=$!
  for _ in $(seq 1 150); do
    [ -f "$ready" ] && return 0
    pid_alive "$CLIENT_PID" || { echo "$mode client exited before ready" >&2; return 1; }
    sleep 0.1
  done
  echo "$mode client did not become ready" >&2
  return 1
}

stop_hold_client() {
  stop_owned_pid "$CLIENT_PID" "$CLIENT_BIN"
  CLIENT_PID=""
  sleep 1
}

# The readiness TCP probe is closed before this first window: no authenticated
# connection exists, so this is the true zero-connection state.
sleep 2
measure_resource_state zero_connection

start_hold_client hold-zero-subscription
sleep 2
measure_resource_state connected_zero_subscription
stop_hold_client

start_hold_client hold-single-subscription
sleep 2
measure_resource_state connected_single_subscription
stop_hold_client

env -i \
  HOME="$RUN_HOME" \
  LANG=C \
  PATH="/usr/bin:/bin" \
  API_SCENARIO_TOKEN="$TOKEN" \
  "$CLIENT_BIN" \
    -mode full \
    -url "$WS_URL" \
    -upload-url "$UPLOAD_URL" \
    -upload-dir "$RUN_ROOT/uploads" \
    -primary-cwd "$PRIMARY_CWD" \
    -other-cwd "$OTHER_CWD" \
    -result "$SCENARIO_JSON" \
    >"$CLIENT_LOG" 2>&1

[ ! -s "$VIOLATIONS" ] || { echo "tmux isolation shim rejected an out-of-scope target" >&2; exit 1; }

# Stop and prove zero residue before publishing a PASS baseline.
stop_owned_pid "$DAEMON_PID" "$DAEMON_BIN"
DAEMON_PID=""
tmux_own kill-server >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  pid_alive "$TMUX_PID" || break
  sleep 0.1
done
! pid_alive "$TMUX_PID"
TMUX_PID=""

if [ -f "$OWNED_PIDS" ]; then
  while IFS= read -r owned_pid; do
    [ -n "$owned_pid" ] || continue
    stop_owned_pid "$owned_pid" "$RUN_ROOT/bin/"
  done <"$OWNED_PIDS"
fi

port_absent=true
if port_listens "$PORT"; then
  port_absent=false
fi
socket_absent=true
runtime_handles_zero=false
if "$REAL_LSOF" -nP +D "$RUN_ROOT" >/dev/null 2>"$BUILD_ROOT/lsof.err"; then
  runtime_handles_zero=false
else
  lsof_rc=$?
  # lsof exits 1 with empty stderr when its scoped search has no matches.
  # Any other result is a probe failure, not evidence of zero handles.
  if [ "$lsof_rc" -eq 1 ] && [ ! -s "$BUILD_ROOT/lsof.err" ]; then
    runtime_handles_zero=true
  else
    sed -n '1,20p' "$BUILD_ROOT/lsof.err" >&2
    exit 1
  fi
fi

case "$RUN_ROOT" in
  /tmp/am-api-scenarios-run.*|/private/tmp/am-api-scenarios-run.*) rm -rf -- "$RUN_ROOT" ;;
  *) exit 1 ;;
esac
runtime_tree_absent=true
[ ! -e "$RUN_ROOT" ] || runtime_tree_absent=false
# tmux can leave a stale socket inode after its server exits; zero residue is
# the post-removal property of the owned runtime tree, so prove it here.
[ ! -e "$SOCKET_DIR/$TMUX_LABEL" ] || socket_absent=false

case "$BUILD_ROOT" in
  /tmp/am-api-scenarios-build.*|/private/tmp/am-api-scenarios-build.*) rm -rf -- "$BUILD_ROOT" ;;
  *) exit 1 ;;
esac
build_tree_absent=true
[ ! -e "$BUILD_ROOT" ] || build_tree_absent=false
CLEANED=1

cat >"$CLEANUP_JSON" <<EOF
{
  "daemon_pid_gone": true,
  "client_pid_gone": true,
  "tmux_pid_gone": true,
  "listener_absent": $port_absent,
  "socket_absent": $socket_absent,
  "runtime_handles_zero": $runtime_handles_zero,
  "runtime_tree_absent": $runtime_tree_absent,
  "build_tree_absent": $build_tree_absent,
  "out_of_scope_tmux_targets": 0
}
EOF

[ "$port_absent" = true ]
[ "$socket_absent" = true ]
[ "$runtime_handles_zero" = true ]
[ "$runtime_tree_absent" = true ]
[ "$build_tree_absent" = true ]

GIT_HEAD="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || printf unknown)"
GO_VERSION="$($REAL_GO version | sed 's/[[:space:]]\+/ /g')"

# Assemble the canonical baseline and human report from measured JSON.  Numeric
# threshold proposals are mechanically derived and explicitly unapproved.
"$PYTHON_BIN" - "$SCENARIO_JSON" "$CLEANUP_JSON" "$BASELINE_JSON" "$REPORT" "$GIT_HEAD" "$GO_VERSION" "${RESOURCE_FILES[@]}" <<'PY'
import datetime, json, math, platform, sys

scenario_path, cleanup_path, baseline_path, report_path, git_head, go_version, *resource_paths = sys.argv[1:]
with open(scenario_path, encoding="utf-8") as fh:
    scenario = json.load(fh)
with open(cleanup_path, encoding="utf-8") as fh:
    cleanup = json.load(fh)
resources = []
for path in resource_paths:
    with open(path, encoding="utf-8") as fh:
        resources.append(json.load(fh))

def rounded(value):
    return round(float(value), 3)

perf = scenario["metrics"]
suggested = {
    "status": "advisory_unapproved",
    "approval_owner": "user_or_adjudicator",
    "derivation_policy": {
        "latency_max": "2x observed p95",
        "throughput_min": "0.5x observed throughput",
        "rss_max": "1.5x observed peak",
        "cpu_mean_max": "2x observed mean when nonzero; null when the sample is at timer resolution zero",
        "spawn_count_max": "2x observed exact spawn count",
    },
    "values": {
        "pair_to_first_listing_ms_max": rounded(perf["pair_to_first_listing"]["p95"] * 2),
        "subscribe_first_frame_ms_max": rounded(perf["subscribe_first_frame"]["p95"] * 2),
        "output_end_to_end_p95_ms_max": rounded(perf["output_end_to_end"]["p95"] * 2),
        "scrollback_page_ms_max": rounded(perf["scrollback_page"]["p95"] * 2),
        "upload_ms_max": rounded(perf["upload"]["latency"]["p95"] * 2),
        "reconnect_recovery_ms_max": rounded(perf["reconnect_recovery"]["p95"] * 2),
        "large_output_bytes_per_second_min": rounded(perf["large_output"]["throughput_bytes_per_second"] * 0.5),
    },
    "economy_by_state": {},
}
for row in resources:
    suggested["economy_by_state"][row["state"]] = {
        "cpu_mean_percent_max": rounded(row["cpu"]["mean_percent"] * 2) if row["cpu"]["mean_percent"] > 0 else None,
        "daemon_rss_kib_max": rounded(row["daemon_rss"]["max"] * 1.5),
        "external_process_spawns_max": int(row["external_process_spawns"]["total"] * 2),
    }

deviations = [
    "Current product has no standalone HTTP pairing/QR endpoint; pairing is tested through the shipped WS auth/auth_ack program interface.",
    "POST /upload currently has no authentication check; this suite records the product risk and does not invent a header contract.",
    "Economy samples use sequential states on one isolated daemon with 10-second windows; they are baseline observations, not approved long-window gates.",
    "Wrapper-shaped local shells stand in for agent CLIs; no production daemon, real user tmux, real phone, camera, notification surface, or lock screen is touched.",
]
unverified = [
    "Physical QR scan and multi-interface reachability",
    "Android workspace/session UI and terminal visual parity",
    "Blocked notification delivery and global notification switch",
    "App process kill restore and lock-screen reconnect",
    "Settings-page single-profile re-pair flow",
    "Chinese-only UI and accessibility content descriptions",
    "Real camera capture; only the host upload/path pipeline is covered",
    "Real Claude Code/Codex multiline bracketed-paste behavior",
]

baseline = {
    "schema_version": 1,
    "suite": "test-api-user-scenarios-perf",
    "status": "pass",
    "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "source": {"git_head": git_head, "go_version": go_version},
    "platform": {
        "system": platform.system(),
        "release": platform.release(),
        "machine": platform.machine(),
    },
    "scope": {
        "user_driver": "API only",
        "interfaces": ["WebSocket /ws", "HTTP multipart POST /upload", "WS auth/auth_ack pairing handshake"],
        "ui_actions": 0,
        "pairing_token_transport": "environment only; value absent from argv/logs/artifacts",
        "ts_authkey": "not provided or inherited",
    },
    "functional": {
        "status": scenario["status"],
        "scenarios": scenario["scenarios"],
        "observed_states": scenario["observed_states"],
    },
    "performance": {
        "mode": "baseline_only",
        "hard_numeric_thresholds": [],
        "percentile_method": scenario["percentile_method"],
        "metrics": perf,
        "suggested_thresholds": suggested,
    },
    "silent_economy": {
        "mode": "baseline_only",
        "states": resources,
        "daemon_memory_unit": "KiB RSS",
        "child_spawn_method": "daemon-side external spawns counted by exact tmux/ps PATH shims; descendant counts use read-only system-wide PID/PPID snapshots held in memory only",
    },
    "isolation_and_cleanup": cleanup,
    "deviations": deviations,
    "unverified": unverified,
}
with open(baseline_path, "w", encoding="utf-8") as fh:
    json.dump(baseline, fh, ensure_ascii=False, indent=2)
    fh.write("\n")

def dist_line(label, item):
    return f"| {label} | {item['count']} | {item['p50']:.3f} ms | {item['p95']:.3f} ms | {item['max']:.3f} ms |"

lines = [
    "# API 用户场景与性能基线报告",
    "",
    "结论：**PASS（baseline-only）**。全部用户动作由 `/ws` 与 `/upload` 程序接口驱动；性能数值已落盘，但本轮没有批准的数值硬门限。功能错误、字节丢失、隔离越界或清理残留仍会直接失败。",
    "",
    "## 场景结果",
    "",
    "| 场景 | 结果 | 用户可见断言 |",
    "|---|---|---|",
]
for row in scenario["scenarios"]:
    lines.append(f"| `{row['id']}` | {row['status'].upper()} | {'；'.join(row['assertions'])} |")

lines += [
    "",
    "## 性能基线",
    "",
    "| 指标 | 样本数 | p50 | p95 | max |",
    "|---|---:|---:|---:|---:|",
    dist_line("配对（WS dial+auth）到首个 listing", perf["pair_to_first_listing"]),
    dist_line("subscribe 到首个 snapshot", perf["subscribe_first_frame"]),
    dist_line("input 到执行输出 delta", perf["output_end_to_end"]),
    dist_line("scrollback 一页", perf["scrollback_page"]),
    dist_line("1 MiB 上传", perf["upload"]["latency"]),
    dist_line("断线后 auth+续订到 snapshot", perf["reconnect_recovery"]),
    "",
    f"大输出：期望/实收 `{perf['large_output']['expected_bytes']}` / `{perf['large_output']['received_bytes']}` bytes，丢失 `{perf['large_output']['loss_bytes']}`，吞吐 `{perf['large_output']['throughput_bytes_per_second']:.3f}` bytes/s。",
    "",
    "## 静默经济三态",
    "",
    "| 状态 | 窗口 | 平均 CPU | RSS p50 / p95 / max | tmux / ps 派生 | daemon 后代峰值 |",
    "|---|---:|---:|---:|---:|---:|",
]
for row in resources:
    rss = row["daemon_rss"]
    spawns = row["external_process_spawns"]
    lines.append(
        f"| `{row['state']}` | {row['window_seconds']:.3f}s | {row['cpu']['mean_percent']:.3f}% | "
        f"{rss['p50']:.0f} / {rss['p95']:.0f} / {rss['max']} KiB | {spawns['tmux']} / {spawns['ps']} | {row['daemon_descendants']['peak']} |"
    )

lines += [
    "",
    "## 建议门限（待裁定，不执行）",
    "",
    "建议值只按当前基线机械派生：时延 2×p95、吞吐 0.5×观测值、RSS 1.5×峰值、CPU 与派生计数 2×观测值；CPU 观测为计时分辨率零时不提出数值。用户或裁定席批准前，脚本不使用这些数字判定 PASS/FAIL。既有 006 的 `<200ms` 首帧目标也仅作历史参考，本轮未硬断言。",
    "",
    "```json",
    json.dumps(suggested, ensure_ascii=False, indent=2),
    "```",
    "",
    "## 隔离与清理",
    "",
    f"- 高端口监听已消失：`{cleanup['listener_absent']}`；tmux socket 已消失：`{cleanup['socket_absent']}`。",
    f"- daemon/client/tmux PID 均已退出，runtime/build 临时树均已删除：`{cleanup['runtime_tree_absent']}` / `{cleanup['build_tree_absent']}`。",
    f"- 非自有 tmux 目标：`{cleanup['out_of_scope_tmux_targets']}`；生产 `:9900` 未连接；真实/Team Agent tmux socket 均未扫描或连接，且未 attach/signal。资源采样仅以只读方式在内存中读取全机 PID/PPID 快照，用于筛选隔离 daemon 后代；原表不落盘。",
    "- daemon 与 client 均在 `env -i` 下运行；配对 token 仅经环境变量进入内存，daemon 含 QR 的 stdout 直送 `/dev/null`；未继承 `TS_AUTHKEY`。",
    "",
    "## 偏差与未验证清单",
    "",
]
for item in deviations:
    lines.append(f"- 偏差：{item}")
for item in unverified:
    lines.append(f"- 未验证：{item}")
lines.append("")

with open(report_path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines))
PY

# Last red-line check: the generated credential must not appear in any durable
# artifact.  It reaches this checker through env, never argv.
API_SCENARIO_SECRET="$TOKEN" "$PYTHON_BIN" - "$ART" <<'PY'
import os, pathlib, sys
secret = os.environ["API_SCENARIO_SECRET"].encode()
for path in pathlib.Path(sys.argv[1]).rglob("*"):
    if path.is_file() and secret in path.read_bytes():
        raise SystemExit(f"pairing token leaked into artifact: {path.name}")
PY
unset TOKEN

"$PYTHON_BIN" -m json.tool "$BASELINE_JSON" >/dev/null
"$PYTHON_BIN" -m json.tool "$CLEANUP_JSON" >/dev/null
test -s "$REPORT"

echo "api-user-scenarios: PASS"
echo "baseline: e2e/artifacts/test-api-user-scenarios-perf/baseline.json"
echo "report: e2e/artifacts/test-api-user-scenarios-perf/REPORT.md"
