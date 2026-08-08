package api

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/remote-agent/agentmirror/internal/protocol"
)

func TestDebugBadToken(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelDebug}))
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}, ListInterval: 50 * time.Millisecond, Log: logger})
	e.sendFrame(&protocol.Auth{Token: "wrong-token"})
	fmt.Println("sent auth, reading...")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	typ, data, err := e.conn.Read(ctx)
	fmt.Printf("read: type=%v data=%q err=%v\n", typ, data, err)
}
