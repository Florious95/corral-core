package bridge

// ACP session identity has no CLI argv slot after session/new. Persist only
// the native session ID and exact process birth in a tmux pane option; never
// infer it from terminal text or from another session's newest file.
// @contract
// @pre verified native process identity and an official reported session ID
// @post daemon reattachment can resume only that exact owned session
// @err corrupt provenance refuses attachment
// @inv no transcript, credentials, filesystem IPC or wrapper is introduced

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
)

const nativeSessionOption = "@corral_native_session"

type nativeSession struct {
	PID                        int
	Started, Provider, Session string
}

// NativeSession returns a birth-bound journal or an explicit native resume ID.
func (p *Pane) NativeSession(ctx context.Context, process NativeProcess) (string, error) {
	data, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{"+nativeSessionOption+"}")
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(string(data)) == "" {
		return process.Session, nil
	}
	var record nativeSession
	if len(data) > 1024 || json.Unmarshal(data, &record) != nil || record.Session == "" || record.PID <= 1 || record.Started == "" {
		return "", errors.New("native session provenance invalid")
	}
	if record.PID == process.PID && record.Started == process.Started && record.Provider == process.Provider {
		return record.Session, nil
	}
	return process.Session, nil
}

// RememberNativeSession records no private launcher state, only native identity.
func (p *Pane) RememberNativeSession(ctx context.Context, process NativeProcess, session string) error {
	if session == "" || len(session) > 256 {
		return errors.New("native session identity unavailable")
	}
	data, _ := json.Marshal(nativeSession{PID: process.PID, Started: process.Started, Provider: process.Provider, Session: session})
	_, err := runTmux(ctx, p.socket, p.timeout, "set-option", "-p", "-t", p.target, nativeSessionOption, string(data))
	return err
}
