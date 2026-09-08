package api

import "testing"

func TestScrollbackRangeForBoundaries(t *testing.T) {
	minInt32 := -1 << 31
	maxInt32 := 1<<31 - 1
	maxUint32 := int(^uint32(0))
	tests := []struct {
		name               string
		history, height    int
		from, count        int
		wantStart, wantEnd int
		wantErr            bool
	}{
		{name: "above history", history: 26, height: 10, from: -500, count: 100, wantStart: -26, wantEnd: -1},
		{name: "oldest page", history: 26, height: 10, from: -30, count: 5, wantStart: -26, wantEnd: -22},
		{name: "crosses screen top", history: 26, height: 10, from: -2, count: 5, wantStart: -2, wantEnd: 2},
		{name: "screen bottom clamp", history: 26, height: 10, from: 8, count: 5, wantStart: 8, wantEnd: 9},
		{name: "below screen", history: 26, height: 10, from: 100, count: 5, wantStart: 5, wantEnd: 9},
		{name: "empty history request", history: 0, height: 10, from: -20, count: 10, wantStart: 0, wantEnd: -1},
		{name: "empty history screen", history: 0, height: 10, from: 0, count: 10, wantStart: 0, wantEnd: 9},
		{name: "minimum from line", history: 26, height: 10, from: minInt32, count: 1, wantStart: -26, wantEnd: -26},
		{name: "maximum from line", history: 26, height: 10, from: maxInt32, count: 1, wantStart: 9, wantEnd: 9},
		{name: "maximum count spans all", history: 26, height: 10, from: minInt32, count: maxUint32, wantStart: -26, wantEnd: 9},
		{name: "negative history", history: -1, height: 10, from: 0, count: 1, wantErr: true},
		{name: "zero height", history: 0, height: 0, from: 0, count: 1, wantErr: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			start, end, err := scrollbackRangeFor(tt.history, tt.height, tt.from, tt.count)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("scrollbackRangeFor(%d,%d,%d,%d) error = nil, want error", tt.history, tt.height, tt.from, tt.count)
				}
				return
			}
			if err != nil {
				t.Fatalf("scrollbackRangeFor(%d,%d,%d,%d) error: %v", tt.history, tt.height, tt.from, tt.count, err)
			}
			if start != tt.wantStart || end != tt.wantEnd {
				t.Fatalf("scrollbackRangeFor(%d,%d,%d,%d) = (%d,%d), want (%d,%d)", tt.history, tt.height, tt.from, tt.count, start, end, tt.wantStart, tt.wantEnd)
			}
		})
	}
}

func TestScrollbackTrimPreservesTrailingBlankRows(t *testing.T) {
	tests := []struct {
		name  string
		in    string
		want  string
		lines int
	}{
		{name: "separator only", in: "BODY\n", want: "BODY", lines: 1},
		{name: "one real blank row", in: "BODY\n\n", want: "BODY\n", lines: 2},
		{name: "missing separator", in: "BODY", want: "BODY", lines: 1},
		{name: "empty capture", in: "", want: "", lines: 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := trimScrollbackTerminator([]byte(tt.in))
			if string(got) != tt.want {
				t.Fatalf("trimScrollbackTerminator(%q) = %q, want %q", tt.in, got, tt.want)
			}
			if lines := countLines(got); lines != tt.lines {
				t.Fatalf("countLines(trim(%q)) = %d, want %d", tt.in, lines, tt.lines)
			}
		})
	}
}
