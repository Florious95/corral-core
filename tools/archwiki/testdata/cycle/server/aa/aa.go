// Package aa is the cycle fixture's left half; it imports bb.
package aa

import "github.com/remote-agent/fixture-cycle/bb"

// Help delegates to bb.
func Help() { bb.Help() }
