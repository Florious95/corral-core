package api

import (
	"context"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/notify"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// A notifications_v1 client receives a live notification frame for a publish
// accepted after its handshake; the golden mirror send path carries it.
func TestPublishedNotificationReachesNegotiatedClient(t *testing.T) {
	pane := discovery.Pane{Socket: "/tmp/notify-test.sock", PaneID: "%7", PanePID: 123, WindowName: "developer", PaneTitle: "π - developer - project", CWD: "/work/project", Command: "node", Width: 80, Height: 24}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}
	e := startWS(t, Options{
		Token: "test-token", Discoverer: scriptedDiscoverer{model: model},
		ListInterval: time.Hour,
	})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["notifications_v1"]}}`)
	if ack.Type != "auth_ack" {
		t.Fatalf("auth response type=%q", ack.Type)
	}
	e.sendFrame(&protocol.List{ReqID: 1})
	mustListing(t, e, 1)
	record, _, err := e.srv.PublishNotification(context.Background(), notify.Request{RequestID: "req-1", Title: "完成", Body: "检查全部通过", SessionRef: sessionRef(pane), Level: "success"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	for {
		f := e.readControl()
		if got, ok := f.(protocol.NotificationRecord); ok {
			if got.ID != record.ID || got.Body != "检查全部通过" || got.Level != "success" {
				t.Fatalf("notification=%+v, want %+v", got, record)
			}
			if got.AgentName == nil || *got.AgentName != "developer" || got.SessionRef == nil || *got.SessionRef != sessionRef(pane) {
				t.Fatalf("notification lost server-derived sender/link: %+v", got)
			}
			return
		}
	}
}

func TestNotificationAgentNameIsDerivedAndPersisted(t *testing.T) {
	for _, tc := range []struct {
		window, title, session, want string
	}{
		{"developer", "π - developer - project", "team", "developer"},
		{"tester", "", "team", "tester"},
		{"pi", "π - leader - project", "team", "Leader"},
		{"pi", "π - 安卓开发leader - project", "team", "Leader"},
		{"Leader", "", "team", "Leader"},
		{"pi", "", "team", "pi"},
		{"", "", "fallback-agent", "fallback-agent"},
	} {
		t.Run(tc.want+"/"+tc.title, func(t *testing.T) {
			dir := t.TempDir()
			store, err := notify.New(dir)
			if err != nil {
				t.Fatal(err)
			}
			pane := discovery.Pane{Socket: "/tmp/sender-test.sock", PaneID: "%7", PanePID: 123, WindowName: tc.window, PaneTitle: tc.title, Session: tc.session, CWD: "/work/project", Command: "node"}
			catalog := newSessionCatalog()
			catalog.rebuild(&discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}, nil)
			s := &Server{catalog: catalog, notifications: store}
			record, _, err := s.PublishNotification(context.Background(), notify.Request{Body: "test", SessionRef: sessionRef(pane), AgentName: "caller-spoof"})
			if err != nil {
				t.Fatal(err)
			}
			if record.AgentName == nil || *record.AgentName != tc.want {
				t.Fatalf("sender=%v want=%q", record.AgentName, tc.want)
			}
			reloaded, err := notify.New(dir)
			if err != nil {
				t.Fatal(err)
			}
			view := reloaded.NewView(nil)
			page := view.Page(50)
			if len(page.Items) != 1 || page.Items[0].AgentName == nil || *page.Items[0].AgentName != tc.want {
				t.Fatalf("persisted sender lost: %+v", page.Items)
			}
		})
	}
}

func TestNotificationTMUXAliasesResolveToCatalogIdentity(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("/tmp and /private/tmp are macOS aliases")
	}
	for _, socket := range []string{"/tmp/tmux-notify-test/default", "/private/tmp/tmux-notify-test/default"} {
		t.Run(socket, func(t *testing.T) {
			pane := discovery.Pane{Socket: socket, PaneID: "%7", WindowName: "developer", CWD: "/work/project"}
			catalog := newSessionCatalog()
			catalog.rebuild(&discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}, nil)
			store, err := notify.New("")
			if err != nil {
				t.Fatal(err)
			}
			s := &Server{catalog: catalog, notifications: store}
			alias := "/private" + socket
			if strings.HasPrefix(socket, "/private/") {
				alias = strings.TrimPrefix(socket, "/private")
			}
			record, _, err := s.PublishNotification(context.Background(), notify.Request{Body: "test", SessionRef: alias + "\x1f%7"})
			if err != nil {
				t.Fatal(err)
			}
			if record.SessionRef == nil || *record.SessionRef != sessionRef(pane) || record.SessionInstance == nil || record.AgentName == nil || *record.AgentName != "developer" {
				t.Fatalf("alias did not resolve to catalog identity: %+v", record)
			}
			unknown, _, err := s.PublishNotification(context.Background(), notify.Request{Body: "test", SessionRef: alias + "\x1f%999", AgentName: "spoof"})
			if err != nil {
				t.Fatal(err)
			}
			if unknown.SessionRef != nil || unknown.SessionInstance != nil || unknown.AgentName != nil {
				t.Fatalf("unknown pane received forged identity: %+v", unknown)
			}
		})
	}
}
