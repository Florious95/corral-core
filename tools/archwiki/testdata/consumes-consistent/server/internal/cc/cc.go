// Package cc imports and declares config.
// @consumes internal/config
package cc

import "github.com/remote-agent/fixture-consumes-consistent/internal/config"

// Value returns the imported config.
func Value() int { return config.Load() }
