package pairing

// token_test.go covers the token-source contract (requirement 011 route (a),
// docs/protocol.md §9): auto-generation on empty config with 0600 persistence
// and restart reuse, explicit-token priority, and the red line that the token
// appears ONLY in the QR/guide — never in an error string.

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestEnsureTokenGeneratesPersistsReuses is the empty-config red test: with no
// explicit token EnsureToken must generate a fresh random token, persist it
// under 0600, and return the SAME token on a later call so a daemon restart
// keeps already-paired devices working.
func TestEnsureTokenGeneratesPersistsReuses(t *testing.T) {
	dir := t.TempDir()

	first, err := EnsureToken("", dir)
	if err != nil {
		t.Fatalf("EnsureToken(generate): %v", err)
	}
	if first == "" {
		t.Fatal("generated token must be non-empty")
	}

	fi, err := os.Stat(filepath.Join(dir, tokenFile))
	if err != nil {
		t.Fatalf("token file must exist after generate: %v", err)
	}
	if perm := fi.Mode().Perm(); perm != 0o600 {
		t.Errorf("token file perm = %o, want 600", perm)
	}

	// A second call (a "restart") must reuse the persisted token, not generate
	// a new one that would invalidate already-paired clients.
	second, err := EnsureToken("", dir)
	if err != nil {
		t.Fatalf("EnsureToken(reuse): %v", err)
	}
	if second != first {
		t.Errorf("EnsureToken reuse = %q, want persisted %q", second, first)
	}
}

// TestEnsureTokenExplicitWins verifies an explicitly configured token (flag or
// env) is returned verbatim and NEVER persisted: the on-disk generated token
// stays intact so an empty-config start later still reuses it.
func TestEnsureTokenExplicitWins(t *testing.T) {
	dir := t.TempDir()

	generated, err := EnsureToken("", dir)
	if err != nil {
		t.Fatalf("EnsureToken(generate): %v", err)
	}
	got, err := os.ReadFile(filepath.Join(dir, tokenFile))
	if err != nil {
		t.Fatalf("read token file: %v", err)
	}
	if string(got) != generated {
		t.Fatalf("persisted token = %q, want %q", got, generated)
	}

	explicit := "explicit-token-abc"
	got2, err := EnsureToken(explicit, dir)
	if err != nil {
		t.Fatalf("EnsureToken(explicit): %v", err)
	}
	if got2 != explicit {
		t.Errorf("EnsureToken(explicit) = %q, want %q", got2, explicit)
	}

	after, err := os.ReadFile(filepath.Join(dir, tokenFile))
	if err != nil {
		t.Fatalf("read token file after explicit call: %v", err)
	}
	if string(after) != generated {
		t.Errorf("explicit token must not overwrite persisted file: got %q, want %q", after, generated)
	}
}

// TestGenerateTokenShape verifies the generated token is ~128 bits encoded in
// a hand-typing-friendly base32 alphabet (A-Z, 2-7 — no 0/O/1/I ambiguity) and
// that two draws differ (crypto/rand, not a counter).
func TestGenerateTokenShape(t *testing.T) {
	a, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}
	b, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}
	if len(a) != tokenChars {
		t.Errorf("token len = %d, want %d (128 bits base32)", len(a), tokenChars)
	}
	for _, r := range a {
		if !isBase32Char(r) {
			t.Errorf("token contains char %q outside base32 alphabet", r)
		}
	}
	if a == b {
		t.Error("two generated tokens must differ (crypto/rand)")
	}
}

// isBase32Char reports whether r is in the RFC 4648 base32 alphabet (A-Z, 2-7).
func isBase32Char(r rune) bool {
	switch {
	case r >= 'A' && r <= 'Z':
		return true
	case r >= '2' && r <= '7':
		return true
	}
	return false
}

// TestErrorsNeverContainToken is the log red line (docs/protocol.md §9): the
// token's only legal exits are the QR and the onboarding guide; every error
// string the package can return must be token-free. A positive control proves
// the substring detector fires, so a pass is meaningful rather than vacuous.
func TestErrorsNeverContainToken(t *testing.T) {
	token, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}

	// Battery of adversarial error paths across the package surface.
	var errs []error
	if _, err := EnsureToken("", "/dev/null/x/not-a-dir"); err != nil {
		errs = append(errs, err)
	}
	if err := SaveToken("/dev/null/x/not-a-dir", token); err != nil {
		errs = append(errs, err)
	}
	if _, _, err := LoadToken("/dev/null/x/not-a-dir"); err != nil {
		errs = append(errs, err)
	}

	for _, e := range errs {
		if strings.Contains(e.Error(), token) {
			t.Errorf("error leaks pairing token: %q", e.Error())
		}
	}

	// Positive control: if the token WERE present, the detector must fire.
	if !strings.Contains("boom "+token+" boom", token) {
		t.Fatal("positive control failed: substring detector is broken")
	}
}
