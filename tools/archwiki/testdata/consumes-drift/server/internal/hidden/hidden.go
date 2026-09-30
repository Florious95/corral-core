// Package hidden imports config without declaring it.
package hidden

import "github.com/remote-agent/fixture-consumes-drift/internal/config"

// Config returns the imported config.
func Config() int { return config.Load() }
