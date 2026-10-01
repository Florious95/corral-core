package api

import (
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/notify"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// A notifications_v1 client receives a live notification frame for a publish
// accepted after its handshake; the golden mirror send path carries it.
func TestPublishedNotificationReachesNegotiatedClient(t *testing.T) {
	e := startWS(t, Options{
		Token: "test-token", Discoverer: scriptedDiscoverer{model: &discovery.Model{}},
		ListInterval: time.Hour,
	})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["notifications_v1"]}}`)
	if ack.Type != "auth_ack" {
		t.Fatalf("auth response type=%q", ack.Type)
	}
	record, _, err := e.srv.PublishNotification(context.Background(), notify.Request{RequestID: "req-1", Title: "完成", Body: "检查全部通过", Level: "success"})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	for {
		f := e.readControl()
		if got, ok := f.(protocol.NotificationRecord); ok {
			if got.ID != record.ID || got.Body != "检查全部通过" || got.Level != "success" {
				t.Fatalf("notification=%+v, want %+v", got, record)
			}
			return
		}
	}
}
