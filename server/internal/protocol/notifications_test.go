package protocol

import (
	"encoding/json"
	"testing"
)

func TestNotificationFramesRoundTrip(t *testing.T) {
	record := NotificationRecord{ID: "720a303f-c883-445f-a3df-c316d901a065", HostID: "host_0123456789abcdef", StreamID: "4e4ca540-df59-4b79-a285-7d1f7523c7ab", Seq: "42", Timestamp: "2026-10-01T01:00:00.123Z", Title: "验收完成", Body: "第一行\n第二行", Level: "success"}
	wire, err := MarshalFrame(record)
	if err != nil {
		t.Fatal(err)
	}
	got, err := UnmarshalFrame(wire)
	if err != nil {
		t.Fatal(err)
	}
	decoded, ok := got.(NotificationRecord)
	if !ok || decoded.ID != record.ID || decoded.Body != record.Body {
		t.Fatalf("decoded=%#v", got)
	}
}

func TestAuthCapabilitiesIntersectionShape(t *testing.T) {
	wire, err := MarshalFrame(Auth{Token: "token", Capabilities: []string{"notifications_v1", "notifications_v1"}})
	if err != nil {
		t.Fatal(err)
	}
	var env Envelope
	if err := json.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	if _, err := UnmarshalFrame(wire); err != nil {
		t.Fatal(err)
	}
	if env.Type != TypeAuth {
		t.Fatalf("type=%q", env.Type)
	}
}
