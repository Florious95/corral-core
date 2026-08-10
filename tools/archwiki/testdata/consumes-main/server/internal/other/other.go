// Package other provides a real import target for the foreign-file guard case:
// cmd/agentmirrord/mixed.go claims @consumes internal/other, and that claim
// must never be attributed to cmd/agentmirrord. internal/other itself imports
// nothing.
package other

// Value returns the other value.
func Value() int { return 1 }
