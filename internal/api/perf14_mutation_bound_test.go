package api

// Independent race/mutation bound oracle for Issue #14. The fixture changes
// metadata between the metadata query and capture, then verifies the request
// still completes with at most one bounded recheck of either operation.
import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type perf14MutationCommand struct {
	Op    string `json:"op"`
	Args  []string `json:"args"`
	Bytes int    `json:"bytes"`
	Start int    `json:"start"`
	End   int    `json:"end"`
	Error string `json:"error"`
}

const perf14MutationTmuxPython = `import json, os, sys
trace=os.environ['PERF14_MUTATION_TRACE']
a=sys.argv[1:]
event={'args':a,'op':'invalid','bytes':0,'start':0,'end':0,'error':''}
def prior(op):
 try:
  lines=open(trace).read().splitlines()
 except FileNotFoundError:
  return 0
 return sum(1 for line in lines if line and json.loads(line).get('op')==op)
try:
 if len(a)<3 or a[:2]!=['-S','/perf14-mutation-only']:
  raise ValueError('non-fixture socket rejected')
 op=a[2]; event['op']=op; rest=a[3:]
 if op=='display-message':
  if rest[-1] != '#{history_size} #{pane_height}':
   raise ValueError('unexpected metadata format')
  # The pane mutates after this query: history grows and height shrinks.
  out=('26 10\n' if prior('display-message') == 0 else '30 8\n').encode()
 elif op=='capture-pane':
  if '-t' not in rest or rest[rest.index('-t')+1] != '%14' or '-S' not in rest or '-E' not in rest:
   raise ValueError('capture missing exact target/range')
  start=int(rest[rest.index('-S')+1]); end=int(rest[rest.index('-E')+1])
  event['start']=start; event['end']=end
  if start == -2147483648:
   raise ValueError('unbounded capture forbidden by mutation fixture')
  # Actual pane after mutation: history=30, height=8, bottom=7.
  lo=max(start,-30); hi=min(end,7)
  if hi >= lo:
   out=''.join('M%+011d\n'%i for i in range(lo,hi+1)).encode()
  else:
   out=b''
 else:
  raise ValueError('unexpected tmux operation '+op)
except Exception as e:
 event['error']=str(e)
with open(trace,'a') as f:
 f.write(json.dumps(event)+'\n')
if event['error']:
 sys.stderr.write(event['error']); sys.exit(1)
sys.stdout.buffer.write(out)
`

func perf14InstallMutationFixture(t *testing.T) string {
	t.Helper()
	python, err := exec.LookPath("python3")
	if err != nil {
		t.Fatalf("required fixture dependency python3: %v (not a skip)", err)
	}
	dir := t.TempDir()
	trace := filepath.Join(dir, "trace.jsonl")
	if err := os.WriteFile(trace, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	if err := os.WriteFile(filepath.Join(dir, "fixture.py"), []byte(perf14MutationTmuxPython), 0o600); err != nil {
		t.Fatal(err)
	}
	wrapper := "#!/bin/sh\nexec " + quote(python) + " " + quote(filepath.Join(dir, "fixture.py")) + " \"$@\"\n"
	if err := os.WriteFile(filepath.Join(dir, "tmux"), []byte(wrapper), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PERF14_MUTATION_TRACE", trace)
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	return trace
}

func perf14ReadMutationCommands(t *testing.T, trace string) []perf14MutationCommand {
	t.Helper()
	data, err := os.ReadFile(trace)
	if err != nil {
		t.Fatal(err)
	}
	var commands []perf14MutationCommand
	for _, line := range bytes.Split(bytes.TrimSpace(data), []byte("\n")) {
		if len(line) == 0 {
			continue
		}
		var command perf14MutationCommand
		if err := json.Unmarshal(line, &command); err != nil {
			t.Fatal(err)
		}
		commands = append(commands, command)
	}
	return commands
}

func TestPerf14MetadataCaptureChangeDoesNotRetryForever(t *testing.T) {
	trace := perf14InstallMutationFixture(t)
	pane := discovery.Pane{Socket: "/perf14-mutation-only", PaneID: "%14", Height: 40, Width: 80}
	msg := perf14PaneRequest(t, pane, 20, 3) // catalog height deliberately stale
	if msg.typ != wsBinary {
		t.Fatalf("controlled metadata/capture change returned non-binary response: %s", msg.data)
	}
	payload, err := decodeScrollback(msg.data)
	if err != nil {
		t.Fatal(err)
	}
	wantData := fmt.Sprintf("M%+011d", 7)
	if payload.FromLine != 7 || payload.LineCount != 1 || string(payload.Data) != wantData {
		t.Fatalf("actual mutated geometry reply from=%d lines=%d data=%q; want 7/1/%q", payload.FromLine, payload.LineCount, payload.Data, wantData)
	}

	commands := perf14ReadMutationCommands(t, trace)
	metadata, captures := 0, 0
	for _, command := range commands {
		switch command.Op {
		case "display-message":
			metadata++
		case "capture-pane":
			captures++
			if command.Start == -2147483648 {
				t.Fatalf("unbounded capture during controlled mutation: %+v", command)
			}
		default:
			t.Fatalf("unexpected tmux op during scrollback: %s", command.Op)
		}
		if command.Error != "" {
			t.Fatalf("fixture rejected bounded request: %s", command.Error)
		}
	}
	if metadata < 1 || metadata > 2 || captures < 1 || captures > 2 {
		t.Fatalf("metadata=%d captures=%d; want one query each and at most one bounded recheck", metadata, captures)
	}
	if len(commands) > 4 {
		t.Fatalf("request retried without bound: %d tmux operations", len(commands))
	}
	t.Logf("controlled mutation completed metadata=%d captures=%d operations=%d", metadata, captures, len(commands))
}

func decodeScrollback(data []byte) (protocol.BinaryPayload, error) {
	payload, err := protocol.DecodeBinary(data)
	if err != nil {
		return protocol.BinaryPayload{}, fmt.Errorf("decode scrollback: %w", err)
	}
	return payload, nil
}
