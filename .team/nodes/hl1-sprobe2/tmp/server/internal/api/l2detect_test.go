package api

import (
	"bytes"
	"log/slog"
	"os"
	"strings"
	"testing"
	"unicode"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestL2NoGlyphIsIdle(t *testing.T) {
	for _, title := range []string{"修滚动的 bug", "hello", ""} {
		st, first, known := classifyFallback(title)
		if st != protocol.SessionStatusIdle || !known {
			t.Fatalf("fallback title=%q first=U+%04X: status=%q known=%v → want idle known=true",
				title, first, st, known)
		}
		st2, _, known2 := classifyFallback(title)
		if st2 != protocol.SessionStatusIdle || !known2 {
			t.Fatalf("fallback title=%q: status=%q known=%v → want idle known=true",
				title, st2, known2)
		}
	}
	data, err := os.ReadFile("detect.go")
	if err != nil {
		t.Fatalf("read shared layer: %v", err)
	}
	lower := strings.ToLower(string(data))
	if strings.Contains(lower, "grok") || strings.Contains(lower, "claude") {
		t.Fatal("shared layer contains a CLI literal")
	}
}

func TestL2UnknownGlyphOnlyWhenGlyphPresent(t *testing.T) {
	title := "※probe-unknown-full-title"
	st, first, known := classifyForProvider("claude_code", title)
	if st != protocol.SessionStatusUnknown {
		t.Fatalf("status=%q, want unknown (known family, unclaimed title)", st)
	}
	if known {
		t.Fatal("unclaimed leading glyph must set known=false")
	}
	if first != '※' {
		t.Fatalf("first=%U, want ※ U+203B", first)
	}
	if unicode.IsLetter(first) || unicode.IsNumber(first) {
		t.Fatalf("first U+%04X is_letter=%v is_number=%v → should be a leading glyph",
			first, unicode.IsLetter(first), unicode.IsNumber(first))
	}
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	logUnknownForProvider(log, "claude_code", title, first)
	logs := buf.String()
	if !strings.Contains(logs, "U+203B") || !strings.Contains(logs, title) || !strings.Contains(logs, "claude_code") {
		t.Fatalf("unknown log missing operands: want provider + U+203B + title=%q; got %q", title, logs)
	}
}
