// Package bb is the cycle fixture's right half; it imports aa.
package bb

import "github.com/remote-agent/fixture-cycle/aa"

// Help delegates to aa.
func Help() { aa.Help() }
