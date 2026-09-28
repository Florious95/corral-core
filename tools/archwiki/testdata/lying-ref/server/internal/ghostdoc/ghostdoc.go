// Package ghostdoc is the T3-2 expanded-surface red fixture: lying refs hidden in
// plain body comments (Go side) that the old KDoc-only scanner missed.
package ghostdoc

// Real is a real symbol.
func Real() int { return 1 }

// Body hides a lying comment in the function body — the exact shape the verify
// seat proved was invisible when only doc/KDoc lines were scanned.
func Body() int {
	// 谎称 `GhostBody`、docs/fake-body.md、--no-such-body-flag
	return Real()
}
