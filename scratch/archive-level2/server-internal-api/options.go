package api

// options.go declares the configuration surface of the WebSocket API server:
// every tunable cmd/agentmirrord wires in (pairing token, upload directory,
// size limits, listing cadence) plus the two extension seams — the token
// validator (default staticToken, pairing generates the token itself) and the
// agent-state provider (default unknownState, production wiredStateProvider
// via NewStateProvider). Zero values fall back to the defaults documented on
// each field, so a minimal NewServer(&Options{Token: t}) is enough to bring up
// a fully functional service.

import (
	"log/slog"
	"time"
)

// Defaults for every tunable that can be left zero. They mirror what
// cmd/agentmirrord's flags default to, so the daemon and the library agree
// even when a caller wires neither.
const (
	// defaultListInterval is how often the server re-scans tmux and pushes
	// list_delta frames.
	defaultListInterval = 2 * time.Second

	// defaultMaxUploadBytes caps one uploaded image (docs/protocol.md §8).
	defaultMaxUploadBytes = 20 << 20 // 20 MiB

	// defaultMaxUploadDirBytes caps all regular files in the flat upload
	// directory. The server rejects an upload that would cross the cap rather
	// than deleting files from a user-configured directory.
	defaultMaxUploadDirBytes = 1 << 30 // 1 GiB

	// defaultMaxInputBytes caps the text of one input frame; a larger text is
	// rejected with input_ack reason too_large.
	defaultMaxInputBytes = 1 << 20 // 1 MiB

	// defaultUploadSubdir is the default directory (under ~/Downloads) where
	// uploaded images are written.
	defaultUploadSubdir = "agentmirror-uploads"

	// uploadHeaderSlack is the extra body budget allowed for multipart
	// framing on top of the file itself, so a file exactly at the byte limit
	// is not rejected by MaxBytesReader before its size is even inspected.
	uploadHeaderSlack = 1 << 20
)

// Options configures the API server. Zero values mean "use the documented
// default" so callers only set what they care about.
type Options struct {
	// Token is the static pairing token every connection must present in its
	// auth frame. It is compared constant-time and never logged or echoed
	// (docs/protocol.md §9). Ignored when TokenValidator is set.
	Token string

	// TokenValidator decides whether an auth frame's token is accepted. The
	// default is staticToken, validating against Options.Token in constant
	// time; the seam stays for a future pairing flow. pairing generates the
	// token itself (task pairing-security), it does not replace this
	// validator.
	TokenValidator TokenValidator

	// StateProvider maps each discovered pane to its normalized agent state.
	// The default is unknownState, always returning protocol.StateUnknown
	// (requirement 008 first-class value); the production wiring is
	// wiredStateProvider via NewStateProvider, assembled by cmd/agentmirrord
	// (task fix-state-wiring). State failures must never affect mirroring or
	// input — this interface is the seam that guarantees it.
	StateProvider StateProvider

	// Discoverer produces the tmux workspace snapshot the listing loop
	// consumes. The default scans every tmux server socket on the host
	// (discovery.Discover); tests inject a scoped or scripted discoverer.
	Discoverer Discoverer

	// DiscoverySocketDirs narrows the default discoverer to exactly these
	// directories. Nil preserves production discovery.Discover behavior;
	// non-nil (including an empty slice) is an explicit fail-closed scope for
	// isolated tests and e2e probes that must not enumerate host tmux sockets.
	// It is ignored when Discoverer is supplied.
	DiscoverySocketDirs []string

	// ListInterval is how often the server re-scans tmux and pushes list_delta
	// frames. Zero defaults to 2s.
	ListInterval time.Duration

	// UploadDir is where POST /upload files are written. Zero defaults to
	// $HOME/Downloads/agentmirror-uploads (created on demand).
	UploadDir string

	// MaxUploadBytes caps a single uploaded file. Zero defaults to 20 MiB.
	MaxUploadBytes int64

	// MaxInputBytes caps the text of one input frame. Zero defaults to 1 MiB.
	MaxInputBytes int

	// Log is the logger for connection lifecycle and errors. Nil discards.
	Log *slog.Logger
}
