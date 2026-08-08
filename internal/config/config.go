// Package config loads the daemon configuration from command-line flags and
// environment variables.
//
// Per requirement 001's sidecar philosophy the daemon is a single binary with
// no config-file dependency: every setting resolves from flags first, then
// environment-variable fallback, then a hard-coded default. Zero third-party
// dependencies by design.
package config

import (
	"flag"
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config holds the resolved runtime settings for the agentmirror daemon.
type Config struct {
	// ListenAddr is the host:port the daemon's WebSocket API listens on.
	ListenAddr string

	// QRListenAddr is the host:port serving the pairing QR page. Empty
	// means pairing-QR serving is disabled.
	QRListenAddr string

	// LogLevel is the slog severity level (debug|info|warn|error).
	LogLevel string

	// Token is the static pairing token every WebSocket connection must
	// present in its auth frame (docs/protocol.md §9). It is consumed by the
	// api.Options.Token seam; pairing-security later replaces static validation
	// with a real pairing flow, at which point this field becomes the seed for
	// the token store. The token is write-only: it is never echoed in any
	// reply and never written to a log.
	Token string

	// UploadDir is where POST /upload writes images (docs/protocol.md §8),
	// consumed by api.Options.UploadDir. Empty defaults to
	// ~/Downloads/agentmirror-uploads.
	UploadDir string

	// MaxUploadBytes caps one uploaded image. Consumed by
	// api.Options.MaxUploadBytes. Zero defaults to 20 MiB.
	MaxUploadBytes int64

	// MaxInputBytes caps the text of one input frame. Consumed by
	// api.Options.MaxInputBytes. Zero defaults to 1 MiB.
	MaxInputBytes int64

	// ListInterval is how often the daemon re-scans tmux and pushes list_delta.
	// Consumed by api.Options.ListInterval. Zero defaults to 2s.
	ListInterval time.Duration
}

// parsePositiveInt64 parses a non-negative integer string (e.g. a byte cap).
// Zero is allowed (it means "use the api default" downstream); a negative or
// non-numeric value is an error so a typo fails fast.
func parsePositiveInt64(name, v string) (int64, error) {
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("config: %s: invalid value %q: %w", name, v, err)
	}
	if n < 0 {
		return 0, fmt.Errorf("config: %s: must be >= 0, got %d", name, n)
	}
	return n, nil
}

// parsePositiveDuration parses a duration string (e.g. "2s"). Zero is allowed
// (it means "use the api default" downstream); a negative or unparsable value
// is an error.
func parsePositiveDuration(name, v string) (time.Duration, error) {
	d, err := time.ParseDuration(v)
	if err != nil {
		return 0, fmt.Errorf("config: %s: invalid value %q: %w", name, v, err)
	}
	if d < 0 {
		return 0, fmt.Errorf("config: %s: must be >= 0, got %s", name, d)
	}
	return d, nil
}

// resolution holds the three sources a setting may come from, in precedence
// order: flag (highest), then environment variable, then default.
type resolution struct {
	flagName string
	envKey   string
	def      string
}

// resolve picks the effective value for one setting. An explicitly-set flag
// (recorded in setFlags by the flag package) wins; otherwise a present
// environment variable; otherwise the default.
func (r resolution) resolve(fs *flag.FlagSet, setFlags map[string]bool) string {
	if setFlags[r.flagName] {
		return fs.Lookup(r.flagName).Value.String()
	}
	if v, ok := os.LookupEnv(r.envKey); ok {
		return v
	}
	return r.def
}

// resolveNonEmpty is resolve for settings where an empty env value is
// indistinguishable from "unset" (a numeric cap of "" cannot parse). It falls
// back to the default when the env variable is present but empty, so a user
// can "clear" a setting by exporting it to empty.
func (r resolution) resolveNonEmpty(fs *flag.FlagSet, setFlags map[string]bool) string {
	if setFlags[r.flagName] {
		return fs.Lookup(r.flagName).Value.String()
	}
	if v, ok := os.LookupEnv(r.envKey); ok && v != "" {
		return v
	}
	return r.def
}

// Load parses args (typically os.Args[1:]) and returns the resolved Config.
// Unrecognized flags are rejected by the flag package's default error
// behavior; the returned error is non-nil in that case.
func Load(args []string) (Config, error) {
	fs := flag.NewFlagSet("agentmirrord", flag.ContinueOnError)

	// Declare flags with sensible defaults so -h renders them usefully.
	// The real defaults live in the resolution table below; flag defaults
	// are only documentation and are overridden during resolution.
	fs.String("listen", "0.0.0.0:9900", "WebSocket listen address, e.g. 0.0.0.0:9900")
	fs.String("qr-listen", "", "pairing QR listen address, e.g. 0.0.0.0:9901 (empty disables)")
	fs.String("log-level", "info", "log severity level: debug|info|warn|error")
	fs.String("token", "", "static pairing token; connections must present it in auth (never logged)")
	fs.String("upload-dir", "", "directory for POST /upload images (default ~/Downloads/agentmirror-uploads)")
	fs.String("max-upload-bytes", "20971520", "max uploaded image bytes (default 20 MiB)")
	fs.String("max-input-bytes", "1048576", "max input frame text bytes (default 1 MiB)")
	fs.String("list-interval", "2s", "tmux re-scan / list_delta interval (default 2s)")

	if err := fs.Parse(args); err != nil {
		return Config{}, err
	}

	// setFlags records which flags were explicitly provided on the command
	// line, so flag defaults never shadow environment variables.
	setFlags := make(map[string]bool)
	fs.Visit(func(f *flag.Flag) { setFlags[f.Name] = true })

	resolve := func(r resolution) string { return r.resolve(fs, setFlags) }
	resolveNonEmpty := func(r resolution) string { return r.resolveNonEmpty(fs, setFlags) }

	cfg := Config{
		ListenAddr:   resolve(resolution{flagName: "listen", envKey: "AGENTMIRROR_LISTEN", def: "0.0.0.0:9900"}),
		QRListenAddr: resolve(resolution{flagName: "qr-listen", envKey: "AGENTMIRROR_QR_LISTEN", def: ""}),
		LogLevel:     resolve(resolution{flagName: "log-level", envKey: "AGENTMIRROR_LOG_LEVEL", def: "info"}),
		Token:        resolve(resolution{flagName: "token", envKey: "AGENTMIRROR_TOKEN", def: ""}),
		UploadDir:    resolve(resolution{flagName: "upload-dir", envKey: "AGENTMIRROR_UPLOAD_DIR", def: ""}),
	}

	// Numeric/duration settings resolve as strings first (the resolution table
	// is string-typed), then parse. An invalid value is a hard error so a
	// misconfigured daemon fails fast instead of silently clamping.
	var err error
	if cfg.MaxUploadBytes, err = parsePositiveInt64("max-upload-bytes",
		resolveNonEmpty(resolution{flagName: "max-upload-bytes", envKey: "AGENTMIRROR_MAX_UPLOAD_BYTES", def: "20971520"})); err != nil {
		return Config{}, err
	}
	if cfg.MaxInputBytes, err = parsePositiveInt64("max-input-bytes",
		resolveNonEmpty(resolution{flagName: "max-input-bytes", envKey: "AGENTMIRROR_MAX_INPUT_BYTES", def: "1048576"})); err != nil {
		return Config{}, err
	}
	if cfg.ListInterval, err = parsePositiveDuration("list-interval",
		resolveNonEmpty(resolution{flagName: "list-interval", envKey: "AGENTMIRROR_LIST_INTERVAL", def: "2s"})); err != nil {
		return Config{}, err
	}

	return cfg, nil
}
