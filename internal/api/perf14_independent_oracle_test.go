package api

// Independent #14 oracle. This drives the production JSON dispatch/handler and
// bridge exec seam; only the tmux executable is replaced. The executable records
// actual argv and stdout byte counts. It cannot forward to a real tmux server.
// Run only on hosted CI, without cache. No t.Parallel: PATH is test-local state.
import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type perf14Fixture struct {
	History         int64   `json:"history"`
	Height          int     `json:"height"`
	MetadataFailure bool    `json:"metadata_failure"`
	MetadataRaw     *string `json:"metadata_raw"`
	BlankRows       []int   `json:"blank_rows"`
	NoFinalLF       bool    `json:"no_final_lf"`
}

type perf14Command struct {
	Op    string   `json:"op"`
	Args  []string `json:"args"`
	Bytes int      `json:"bytes"`
	Start int64    `json:"start"`
	End   int64    `json:"end"`
	Error string   `json:"error"`
}

const perf14Socket = "/perf14-synthetic-only"
const perf14Pane = "%14"

// The stub accepts format delimiter choices, not different metadata semantics.
// No range oracle is implemented here: capture is a coordinate-addressed source.
const perf14TmuxPython = `import json, os, sys
c=json.load(open(os.environ['PERF14_CONFIG']))
a=sys.argv[1:]
out=b''
event={'args':a,'op':'invalid','bytes':0,'start':0,'end':0,'error':''}
try:
 if len(a)<3 or a[:2]!=['-S','/perf14-synthetic-only']:
  raise ValueError('non-fixture socket rejected')
 op=a[2]; event['op']=op; a=a[3:]
 if '-t' not in a or a[a.index('-t')+1]!='%14':
  raise ValueError('non-fixture target rejected')
 if op=='display-message':
  if c['metadata_failure']:
   raise ValueError("can't find pane: %14")
  if c['metadata_raw'] is not None:
   out=c['metadata_raw'].encode()
  else:
   f=a[-1]
   if f.count('#{history_size}')!=1 or f.count('#{pane_height}')!=1:
    raise ValueError('metadata must query history_size and pane_height together')
   f=f.replace('#{history_size}',str(c['history'])).replace('#{pane_height}',str(c['height']))
   if '#{' in f: raise ValueError('unexpected metadata field')
   out=(f+'\n').encode()
 elif op=='capture-pane':
  if '-p' not in a or '-e' not in a or '-S' not in a or '-E' not in a:
   raise ValueError('capture must specify ANSI and exact start/end')
  start=int(a[a.index('-S')+1]); end=int(a[a.index('-E')+1])
  event['start']=start; event['end']=end
  lo=max(start,-c['history']); hi=min(end,c['height']-1)
  if hi-lo+1>100001: raise ValueError('unbounded extreme capture rejected by fixture safety cap')
  if start==0 and end==-1:
   out=b'VISIBLE_SCREEN_MUST_NOT_LEAK\n'
  else:
   blank=set(c['blank_rows'] or [])
   out=''.join(('' if i in blank else 'R%+011d'%i)+'\n' for i in range(lo,hi+1)).encode()
   if c['no_final_lf'] and out.endswith(b'\n'): out=out[:-1]
 else:
  raise ValueError('unexpected tmux operation '+op)
except Exception as e:
 event['error']=str(e)
event['bytes']=len(out)
with open(os.environ['PERF14_TRACE'],'a') as f:
 f.write(json.dumps(event)+'\n')
if event['error']:
 sys.stderr.write(event['error']); sys.exit(1)
sys.stdout.buffer.write(out)
`

func perf14InstallFixture(t *testing.T, f perf14Fixture) string {
	t.Helper()
	python, err := exec.LookPath("python3")
	if err != nil {
		t.Fatalf("required fixture dependency python3: %v (not a skip)", err)
	}
	dir := t.TempDir()
	config, err := json.Marshal(f)
	if err != nil {
		t.Fatal(err)
	}
	write := func(name string, data []byte, mode os.FileMode) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(dir, name), data, mode); err != nil {
			t.Fatal(err)
		}
	}
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	write("config.json", config, 0600)
	write("fixture.py", []byte(perf14TmuxPython), 0600)
	write("tmux", []byte("#!/bin/sh\nexec "+quote(python)+" "+quote(filepath.Join(dir, "fixture.py"))+" \"$@\"\n"), 0700)
	trace := filepath.Join(dir, "trace.jsonl")
	write("trace.jsonl", nil, 0600)
	t.Setenv("PERF14_CONFIG", filepath.Join(dir, "config.json"))
	t.Setenv("PERF14_TRACE", trace)
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	return trace
}

func perf14ReadCommands(t *testing.T, trace string) []perf14Command {
	t.Helper()
	data, err := os.ReadFile(trace)
	if err != nil {
		t.Fatal(err)
	}
	var result []perf14Command
	for _, line := range bytes.Split(bytes.TrimSpace(data), []byte("\n")) {
		if len(line) == 0 {
			continue
		}
		var c perf14Command
		if err := json.Unmarshal(line, &c); err != nil {
			t.Fatal(err)
		}
		result = append(result, c)
		t.Logf("tmux op=%s argv=%q stdout_bytes=%d range=[%d,%d] error=%q", c.Op, c.Args, c.Bytes, c.Start, c.End, c.Error)
	}
	return result
}

// Seed only the already-discovered catalog. Discovery/nodeprobe are outside #14
// and must not execute against a real pane just to prepare this paging oracle.
func perf14Request(t *testing.T, catalogHeight int, from int32, count uint32) wsMsg {
	t.Helper()
	pane := discovery.Pane{Socket: perf14Socket, PaneID: perf14Pane, Height: catalogHeight, Width: 80}
	return perf14PaneRequest(t, pane, from, count)
}

func perf14PaneRequest(t *testing.T, pane discovery.Pane, from int32, count uint32) wsMsg {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ref := sessionRef(pane)
	s := &Server{log: discardLogger(), catalog: newSessionCatalog(), snapshot: &modelSnapshot{}}
	s.catalog.byRef[ref] = &sessionEntry{ref: ref, pane: pane, bridge: bridge.NewPane(pane.Socket, pane.PaneID)}
	c := &wsConn{s: s, ctx: ctx, cancel: cancel, sendCh: make(chan wsMsg, 4)}
	c.authed.Store(true)
	req := protocol.Scrollback{Ref: ref, ReqID: 0x10203040, FromLine: from, Count: count}
	wire, err := json.Marshal(map[string]any{"v": 1, "type": "scrollback", "payload": req})
	if err != nil {
		t.Fatal(err)
	}
	c.handleFrame(wire, 0)
	select {
	case msg := <-c.sendCh:
		if len(c.sendCh) != 0 {
			t.Fatal("more than one reply for one scrollback request")
		}
		return msg
	case <-ctx.Done():
		t.Fatal("scrollback handler did not produce bounded reply")
		return wsMsg{}
	}
}

func perf14AssertReply(t *testing.T, msg wsMsg, start int32, rows []string) {
	t.Helper()
	perf14AssertRefReply(t, msg, perf14Socket+"\x1f"+perf14Pane, start, rows)
}

func perf14AssertRefReply(t *testing.T, msg wsMsg, ref string, start int32, rows []string) {
	t.Helper()
	if msg.typ != wsBinary {
		t.Fatalf("wanted binary page, got type=%v data=%s", msg.typ, msg.data)
	}
	p, err := protocol.DecodeBinary(msg.data)
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != protocol.KindScrollback || p.Ref != ref || p.ReqID != 0x10203040 || p.FromLine != start || p.LineCount != uint32(len(rows)) {
		t.Fatalf("header got kind=%v ref=%q req=%x from=%d lines=%d; want from=%d lines=%d", p.Kind, p.Ref, p.ReqID, p.FromLine, p.LineCount, start, len(rows))
	}
	want := strings.Join(rows, "\n")
	// Preserve the existing protocol's nonzero one-empty-line representation.
	if len(rows) == 1 && rows[0] == "" {
		want = "\n"
	}
	if string(p.Data) != want {
		t.Errorf("page body=%q want=%q", p.Data, want)
	}
	// Independent raw header layout oracle; DecodeBinary alone is insufficient.
	off := 5 + len(ref)
	if len(msg.data) < off+12 || !bytes.Equal(msg.data[:4], []byte{'R', 'A', 1, 3}) || int(msg.data[4]) != len(ref) {
		t.Fatal("binary outer header mismatch")
	}
	if binary.BigEndian.Uint32(msg.data[off:]) != 0x10203040 || int32(binary.BigEndian.Uint32(msg.data[off+4:])) != start || binary.BigEndian.Uint32(msg.data[off+8:]) != uint32(len(rows)) {
		t.Fatal("raw 12-byte range header mismatch")
	}
}

func perf14Rows(start, end int64) []string {
	var out []string
	for i := start; i <= end; i++ {
		out = append(out, fmt.Sprintf("R%+011d", i))
	}
	return out
}

func TestPerf14HandlerBoundedCaptureWork(t *testing.T) {
	// Fixed width/ANSI density: total captured bytes must be exactly P*13,
	// independent of H. Metadata can grow only by its decimal digit count.
	for _, history := range []int64{400, 10000, 100000} {
		t.Run(fmt.Sprintf("H%d_P400", history), func(t *testing.T) {
			trace := perf14InstallFixture(t, perf14Fixture{History: history, Height: 24})
			msg := perf14Request(t, 24, -400, 400)
			commands := perf14ReadCommands(t, trace)
			meta, captures, captured := 0, 0, 0
			for _, c := range commands {
				switch c.Op {
				case "display-message":
					meta++
					if c.Bytes > 96 {
						t.Errorf("metadata is not constant-size: %d", c.Bytes)
					}
				case "capture-pane":
					captures++
					captured += c.Bytes
					if c.Start != -400 || c.End != -1 {
						t.Errorf("unbounded/non-page capture [%d,%d], want [-400,-1]", c.Start, c.End)
					}
				default:
					t.Errorf("unrelated request-stage tmux operation: %s", c.Op)
				}
				if c.Error != "" {
					t.Errorf("fixture rejected command: %s", c.Error)
				}
			}
			if meta != 1 || captures != 1 || captured != 400*13 {
				t.Errorf("H=%d P=400 metadata=%d captures=%d captured_bytes=%d; want1/1/5200", history, meta, captures, captured)
			}
			perf14AssertReply(t, msg, -400, perf14Rows(-400, -1))
		})
	}
}

func TestPerf14HandlerRangeTable(t *testing.T) {
	cases := []struct {
		name       string
		h          int64
		g, catalog int
		from       int32
		count      uint32
		start, end int64
	}{
		{"above_top", 26, 10, 10, -40, 5, -26, -22},
		{"ends_at_oldest_keeps_page_shift", 26, 10, 10, -30, 5, -26, -22},
		{"crosses_oldest", 26, 10, 10, -28, 5, -26, -24},
		{"history_to_screen", 26, 10, 10, -2, 5, -2, 2},
		{"history_only", 26, 10, 10, -4, 4, -4, -1},
		{"screen_top", 26, 10, 10, 0, 3, 0, 2},
		{"crosses_bottom", 26, 10, 10, 7, 8, 7, 9},
		{"below_bottom", 26, 10, 10, 20, 3, 7, 9},
		{"below_bottom_huge_page", 26, 10, 10, 20, 100, -26, 9},
		{"actual_smaller_than_catalog", 26, 3, 40, 20, 4, -1, 2},
		{"actual_larger_than_catalog", 26, 40, 3, 35, 8, 35, 39},
		{"zero_history_screen", 0, 10, 10, 0, 3, 0, 2},
		{"zero_history_cross_zero", 0, 10, 10, -2, 4, 0, 1},
		{"int32_min_uint32_max", 26, 10, 10, math.MinInt32, math.MaxUint32, -26, 9},
		{"int32_max_uint32_max", 26, 10, 10, math.MaxInt32, math.MaxUint32, -26, 9},
		{"one_oldest_row", 26, 10, 10, math.MinInt32, 1, -26, -26},
		{"one_screen_row", 26, 10, 10, 0, 1, 0, 0},
		{"oldest_int32_wire_boundary", 2147483648, 10, 10, math.MinInt32, 1, math.MinInt32, math.MinInt32},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			trace := perf14InstallFixture(t, perf14Fixture{History: tc.h, Height: tc.g})
			msg := perf14Request(t, tc.catalog, tc.from, tc.count)
			metadata, captures := 0, 0
			for _, command := range perf14ReadCommands(t, trace) {
				if command.Op == "display-message" {
					metadata++
				}
				if command.Op == "capture-pane" {
					captures++
					if command.Start != tc.start || command.End != tc.end {
						t.Errorf("capture interval=[%d,%d], want[%d,%d]", command.Start, command.End, tc.start, tc.end)
					}
				}
			}
			if metadata != 1 || captures != 1 {
				t.Errorf("metadata=%d captures=%d want1/1", metadata, captures)
			}
			perf14AssertReply(t, msg, int32(tc.start), perf14Rows(tc.start, tc.end))
		})
	}
}

func TestPerf14EmptyHistoryDoesNotCaptureScreen(t *testing.T) {
	trace := perf14InstallFixture(t, perf14Fixture{History: 0, Height: 10})
	msg := perf14Request(t, 10, -400, 400)
	for _, c := range perf14ReadCommands(t, trace) {
		if c.Op == "capture-pane" {
			t.Errorf("empty history must not capture screen: argv=%q", c.Args)
		}
	}
	perf14AssertReply(t, msg, 0, []string{""})
}

// Leader's explicit #14 contract: remove one capture delimiter LF, preserve
// actual blank rows, and count a nonempty unterminated single row as one.
func TestPerf14PageLineContentOracle(t *testing.T) {
	cases := []struct {
		name  string
		from  int32
		count uint32
		blank []int
		noLF  bool
		rows  []string
	}{
		{"one_nonempty_terminated", -1, 1, nil, false, perf14Rows(-1, -1)},
		{"one_nonempty_unterminated", -1, 1, nil, true, perf14Rows(-1, -1)},
		{"two_nonempty_unterminated", -2, 2, nil, true, perf14Rows(-2, -1)},
		{"one_actual_blank", -1, 1, []int{-1}, false, []string{""}},
		{"three_actual_blanks", -3, 3, []int{-3, -2, -1}, false, []string{"", "", ""}},
		{"three_trailing_blank_rows", -4, 4, []int{-3, -2, -1}, false, []string{fmt.Sprintf("R%+011d", -4), "", "", ""}},
		{"interior_blank_row", -3, 3, []int{-2}, false, []string{fmt.Sprintf("R%+011d", -3), "", fmt.Sprintf("R%+011d", -1)}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			trace := perf14InstallFixture(t, perf14Fixture{History: 26, Height: 10, BlankRows: tc.blank, NoFinalLF: tc.noLF})
			msg := perf14Request(t, 10, tc.from, tc.count)
			perf14ReadCommands(t, trace)
			perf14AssertReply(t, msg, tc.from, tc.rows)
		})
	}
}

func TestPerf14ZeroCountRejectedBeforeTmux(t *testing.T) {
	trace := perf14InstallFixture(t, perf14Fixture{History: 26, Height: 10})
	msg := perf14Request(t, 10, -1, 0)
	if len(perf14ReadCommands(t, trace)) != 0 {
		t.Fatal("invalid count contacted tmux")
	}
	if msg.typ == wsBinary || !bytes.Contains(msg.data, []byte(`"type":"error"`)) {
		t.Fatalf("count=0 not rejected: %s", msg.data)
	}
}

func TestPerf14MetadataFailureDoesNotCapture(t *testing.T) {
	for _, name := range []string{"pane_missing", "malformed", "negative_history", "zero_height"} {
		t.Run(name, func(t *testing.T) {
			f := perf14Fixture{History: 26, Height: 10}
			switch name {
			case "pane_missing":
				f.MetadataFailure = true
			case "malformed":
				raw := "not-integers\n"
				f.MetadataRaw = &raw
			case "negative_history":
				f.History = -1
			case "zero_height":
				f.Height = 0
			}
			trace := perf14InstallFixture(t, f)
			msg := perf14Request(t, 10, -4, 4)
			commands := perf14ReadCommands(t, trace)
			meta := 0
			for _, c := range commands {
				if c.Op == "capture-pane" {
					t.Errorf("capture after invalid metadata: %q", c.Args)
				}
				if c.Op == "display-message" {
					meta++
				}
			}
			if meta != 1 {
				t.Errorf("metadata calls=%d want1", meta)
			}
			var reply struct {
				Type    string              `json:"type"`
				Payload protocol.ErrorFrame `json:"payload"`
			}
			if err := json.Unmarshal(msg.data, &reply); err != nil {
				t.Fatalf("expected visible error: %v bytes=%q", err, msg.data)
			}
			if reply.Type != "error" || reply.Payload.Code == "" {
				t.Fatalf("missing protocol error: %s", msg.data)
			}
			if name == "pane_missing" && reply.Payload.Code != protocol.ErrCodeSessionNotFound {
				t.Errorf("pane missing code=%s", reply.Payload.Code)
			}
		})
	}
}
