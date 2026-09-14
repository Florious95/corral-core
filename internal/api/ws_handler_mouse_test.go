package api

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

func TestMouseModePrefixUsesActiveTrackingAndSGR(t *testing.T) {
	tests := []struct {
		name string
		mode bridge.MouseMode
		want string
	}{
		{name: "off", want: ""},
		{name: "standard", mode: bridge.MouseMode{Any: true, Standard: true}, want: "\x1b[?1000h"},
		{name: "button sgr", mode: bridge.MouseMode{Any: true, Button: true, SGR: true}, want: "\x1b[?1002h\x1b[?1006h"},
		{name: "all sgr", mode: bridge.MouseMode{Any: true, All: true, SGR: true}, want: "\x1b[?1003h\x1b[?1006h"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := string(mouseModePrefix(tt.mode)); got != tt.want {
				t.Fatalf("mouseModePrefix(%+v) = %q, want %q", tt.mode, got, tt.want)
			}
		})
	}
}
