package nodeprobe

import (
	"reflect"
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

func TestJoinReportProjectsWithoutMutatingSharedRows(t *testing.T) {
	report := Report{Socket: "/fixture/socket", Nodes: []Node{
		{Session: "A", WindowIndex: 1, PaneID: "%1", Provider: "claude_code", Activity: "working", Health: "normal"},
		{Session: "B", WindowIndex: 2, PaneID: "%2", Provider: "pi", Activity: "idle", Health: "normal"},
		{Session: "A", WindowIndex: 1, PaneID: "%3", Provider: "codex", Activity: "working", Health: "unknown"},
		{Session: "A", WindowIndex: 1, PaneID: "%3", Provider: "codex", Activity: "working", Health: "unknown"},
	}}
	before := append([]Node(nil), report.Nodes...)
	out := map[string]Observation{}
	JoinReport(report, []discovery.Pane{
		{Socket: report.Socket, Session: "A", WindowIndex: 1, PaneID: "%1"},
		{Socket: report.Socket, Session: "A", WindowIndex: 1, PaneID: "%3"},
		{Socket: report.Socket, Session: "A", WindowIndex: 1, PaneID: "%4"},
	}, out)
	if len(out) != 3 || out[report.Socket+"\x1f%1"].Provider != "claude_code" || out[report.Socket+"\x1f%3"] != Unknown() || out[report.Socket+"\x1f%4"] != Unknown() {
		t.Fatalf("workspace A projection=%+v", out)
	}
	JoinReport(report, []discovery.Pane{{Socket: report.Socket, Session: "B", WindowIndex: 2, PaneID: "%2"}}, out)
	if len(out) != 4 || out[report.Socket+"\x1f%2"].Provider != "pi" || !reflect.DeepEqual(report.Nodes, before) {
		t.Fatalf("shared report or other workspace changed: out=%+v rows=%+v", out, report.Nodes)
	}
}
