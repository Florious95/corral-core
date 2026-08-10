// Package drift declares that it consumes config but does not import it.
// @consumes internal/config
package drift

// Value returns the drift value.
func Value() int { return 1 }
