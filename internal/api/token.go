package api

// token.go defines the pairing-token seam. The auth frame carries the token
// once, server-side it is compared and forgotten: it is never echoed in any
// reply and never written to a log (docs/protocol.md §9, requirement 011
// route (a)).

import (
	"context"
	"crypto/subtle"
)

// TokenValidator decides whether an auth frame's token is accepted for a
// connection. The pairing-security task replaces the default static-token
// implementation with a real pairing flow; until then the daemon validates
// against the token configured at startup.
type TokenValidator interface {
	// ValidateToken reports whether token authenticates this connection.
	ValidateToken(ctx context.Context, token string) bool
}

// staticToken validates against the token wired in Options. Constant-time
// comparison avoids a timing side channel on token length or content.
type staticToken struct {
	token string
}

func (s staticToken) ValidateToken(_ context.Context, token string) bool {
	return subtle.ConstantTimeCompare([]byte(s.token), []byte(token)) == 1
}
