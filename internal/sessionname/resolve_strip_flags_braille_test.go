package sessionname

import "testing"

func TestResolveStripsWindowFlagsAndBrailleTitleState(t *testing.T) {
	tests := []struct {
		name, window, title, cwd string
	}{
		{"dot-one", "eight-rust-luna*", "⠠ Aaron", "/Volumes/nvme/Projects/Aaron"},
		{"dot-six", "eight-rust-luna", "⠦ Aaron", "/Volumes/nvme/Projects/Aaron"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Resolve(tt.window, tt.title, tt.cwd, "bash")
			want := Resolved{Value: "eight-rust-luna", Source: SourceWindow}
			if got != want {
				t.Fatalf("Resolve(%q, %q, %q, bash) = %+v; want %+v", tt.window, tt.title, tt.cwd, got, want)
			}
		})
	}
}
