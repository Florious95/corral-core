// Package config registers the fixture daemon's CLI flag so the T3-2 flag check is live.
package config

import "flag"

// Register builds the fixture's flag set. The only registered flag is --listen.
func Register() *flag.FlagSet {
	fs := flag.NewFlagSet("fixture", flag.ContinueOnError)
	fs.String("listen", "0.0.0.0:9900", "listen address")
	return fs
}
