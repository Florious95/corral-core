package guirpc

import (
	"bytes"
	"strings"
	"testing"
)

func TestUserInputCannotRaceModeSwitchButInternalStateCan(t *testing.T) {
	var input bytes.Buffer
	w := newWorker(&input)
	w.setSwitching(true)
	if err := w.sendUser([]byte(`{"type":"prompt","message":"must not run"}`)); err == nil {
		t.Fatal("user input was accepted while switching")
	}
	if input.Len() != 0 {
		t.Fatal("rejected prompt reached the agent")
	}
	if err := w.send([]byte(`{"type":"get_state"}`)); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(input.String(), `"get_state"`) {
		t.Fatal("switch's own state request was blocked")
	}
	w.setSwitching(false)
	if err := w.sendUser([]byte(`{"type":"prompt","message":"after switch"}`)); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(input.String(), "after switch") {
		t.Fatal("input did not resume after the switch")
	}
}
