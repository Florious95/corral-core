package guirpc

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func interactionToken(t *testing.T, w *worker, raw string) string {
	t.Helper()
	w.ingest([]byte(raw))
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.interactions) != 1 {
		t.Fatalf("pending=%d", len(w.interactions))
	}
	for token, p := range w.interactions {
		if token == p.native || len(token) != 32 {
			t.Fatal("native ID leaked as GUI token")
		}
		return token
	}
	return ""
}

func TestNativeDialogsOpaqueExactlyOnceAndSwitchGate(t *testing.T) {
	for _, tc := range []struct{ method, extra, decision, want string }{
		{"confirm", "", `"confirmed":false`, `"confirmed":false`},
		{"select", `,"options":["Allow","Block"]`, `"value":"Block"`, `"value":"Block"`},
		{"input", "", `"value":""`, `"value":""`},
		{"editor", `,"prefill":"a\nb"`, `"value":"Alpha\nBeta"`, `"value":"Alpha\nBeta"`},
	} {
		t.Run(tc.method, func(t *testing.T) {
			out := &syncBuffer{}
			w := newWorker(out)
			defer w.shutdown()
			_, _, client := w.attach(Hello{}, true)
			defer w.detach(client)
			w.sessionID = "sid"
			token := interactionToken(t, w, `{"type":"extension_ui_request","id":"native-id","method":"`+tc.method+`"`+tc.extra+`}`)
			if out.String() != "" {
				t.Fatal("automatic decision")
			}
			w.setSwitching(true) // before-fork extension replies must pass its own input barrier.
			reply := []byte(`{"requestId":"` + token + `",` + tc.decision + `}`)
			if err := w.replyInteraction(reply); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(out.String(), `"id":"native-id"`) || !strings.Contains(out.String(), tc.want) {
				t.Fatalf("native reply=%s", out.String())
			}
			before := out.String()
			if err := w.replyInteraction(reply); err == nil {
				t.Fatal("duplicate accepted")
			}
			if out.String() != before {
				t.Fatal("duplicate reached native")
			}
		})
	}
}

func TestDialogInvalidStaleExpiryAndOwnerLoss(t *testing.T) {
	out := &syncBuffer{}
	w := newWorker(out)
	defer w.shutdown()
	_, _, client := w.attach(Hello{}, true)
	w.sessionID = "sid"
	token := interactionToken(t, w, `{"type":"extension_ui_request","id":"native-id","method":"select","options":["known"]}`)
	if err := w.replyInteraction([]byte(`{"requestId":"` + token + `","value":"not-native"}`)); err == nil {
		t.Fatal("unadvertised choice accepted")
	}
	if out.String() != "" {
		t.Fatal("invalid choice wrote native")
	}
	w.sessionID = "other"
	if err := w.replyInteraction([]byte(`{"requestId":"` + token + `","value":"known"}`)); err == nil {
		t.Fatal("stale session accepted")
	}
	w.sessionID = "sid"
	w.expireInteraction(token, "expired")
	w.detach(client)
	deadline := time.Now().Add(time.Second)
	for out.String() == "" && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !strings.Contains(out.String(), `"cancelled":true`) {
		t.Fatalf("expiry=%s", out.String())
	}
	_, _, client = w.attach(Hello{}, true)
	token = interactionToken(t, w, `{"type":"extension_ui_request","id":"owner-loss","method":"confirm"}`)
	w.detach(client)
	deadline = time.Now().Add(time.Second)
	for !strings.Contains(out.String(), "owner-loss") && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !strings.Contains(out.String(), "owner-loss") {
		t.Fatal("owner loss left callback waiting")
	}
	if err := w.replyInteraction([]byte(`{"requestId":"` + token + `","confirmed":true}`)); err == nil {
		t.Fatal("owner-lost request accepted")
	}
}

func TestGrokPermissionNumericZeroNativeOptionAndPermanentConsent(t *testing.T) {
	native := &syncBuffer{}
	w := newWorker(nil)
	defer w.shutdown()
	_, _, client := w.attach(Hello{}, true)
	defer w.detach(client)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	g := newGrokACP(ctx, func(b []byte) error { _, err := native.Write(b); return err }, w.ingest, func() {})
	w.setInput(g)
	g.session = "sid"
	w.sessionID = "sid"
	g.ingest([]byte(`{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"sid","toolCall":{"toolCallId":"tool","title":"Bash"},"options":[{"optionId":"always-first","name":"Entire native scope","kind":"allow_always"},{"optionId":"deny-native","name":"Reject once","kind":"reject_once"}]}}`))
	if native.String() != "" {
		t.Fatal("first/native permission auto-approved")
	}
	w.mu.Lock()
	var token string
	for k := range w.interactions {
		token = k
	}
	w.mu.Unlock()
	if token == "" {
		t.Fatal("numeric-zero callback lost")
	}
	if err := w.replyInteraction([]byte(`{"requestId":"` + token + `","value":"always-first"}`)); err == nil {
		t.Fatal("permanent scope accepted without consent")
	}
	if native.String() != "" {
		t.Fatal("unconfirmed permanent choice reached native")
	}
	if err := w.replyInteraction([]byte(`{"requestId":"` + token + `","value":"deny-native"}`)); err != nil {
		t.Fatal(err)
	}
	var reply struct {
		ID     json.RawMessage `json:"id"`
		Result struct {
			Outcome struct {
				Outcome, OptionID string `json:"outcome"`
			}
		}
	}
	var payload map[string]json.RawMessage
	_ = json.Unmarshal([]byte(native.String()), &payload)
	if string(payload["id"]) != "0" || !strings.Contains(string(payload["result"]), `"optionId":"deny-native"`) || !strings.Contains(string(payload["result"]), `"outcome":"selected"`) {
		t.Fatalf("native reply=%s %+v", native.String(), reply)
	}
}
