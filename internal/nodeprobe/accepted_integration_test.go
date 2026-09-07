package nodeprobe

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestAcceptedInstalledBinaryOnIsolatedFakeSocket(t *testing.T) {
	if runtime.GOOS != "darwin" || runtime.GOARCH != "arm64" {
		t.Skip("accepted production coordinate is darwin/arm64")
	}
	root := t.TempDir()
	binDir := filepath.Join(root, "bin")
	if err := os.Mkdir(binDir, 0o700); err != nil {
		t.Fatal(err)
	}
	// Accepted source invokes only `tmux -S <socket> list-panes -a -F ...`.
	// The fake emits one structurally valid row without touching a host socket.
	fake := "#!/bin/sh\nprintf 'accepted-host\\0370\\037win\\037%%0\\037999999\\037sh\\037plain-title\\037/tmp/project\\n'\n"
	if err := os.WriteFile(filepath.Join(binDir, "tmux"), []byte(fake), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))
	socket := filepath.Join(root, "tmux-accepted", "default")
	capability, err := ResolveCapability()
	if err != nil {
		t.Fatal(err)
	}
	got, err := NewRunner(capability).Sample(context.Background(), socket)
	if err != nil {
		t.Fatal(err)
	}
	if got.SchemaVersion != 1 || got.Socket != socket || len(got.Nodes) != 1 {
		t.Fatalf("report=%+v", got)
	}
	if got.Nodes[0].Provider != "unknown" || got.Nodes[0].Activity != got.Nodes[0].State {
		t.Fatalf("accepted unknown-provider row was altered/dropped: %+v", got.Nodes[0])
	}
}
