// Package nodeprobe is the sole Go consumer of the accepted nodeprobe report.
// It verifies the rolled-out capability, executes it once per allowed socket,
// and joins observations only by tmux structural identity.
//
// @consumes internal/discovery
package nodeprobe

import (
	"bytes"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

//go:embed accepted-source.json
var manifestBytes []byte

type manifest struct {
	SourceCommit string             `json:"source_commit"`
	SourceTree   string             `json:"source_tree"`
	Platform     string             `json:"platform"`
	Binary       fileCoordinate     `json:"binary"`
	PiExtension  fileCoordinate     `json:"pi_extension"`
	Corpora      []corpusCoordinate `json:"corpora"`
}
type fileCoordinate struct {
	Path   string `json:"path"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}
type corpusCoordinate struct {
	Path     string `json:"path"`
	BaseBlob string `json:"base_blob"`
	SHA256   string `json:"sha256"`
}

// Capability is the complete version-bound runtime coordinate.
type Capability struct {
	Binary           string
	PiExtension      string
	Titles           string
	Providers        string
	PiExtensionFault error
}

// ResolveCapability verifies the accepted binary and canonical repository inputs.
func ResolveCapability() (Capability, error) {
	var m manifest
	if err := json.Unmarshal(manifestBytes, &m); err != nil {
		return Capability{}, fmt.Errorf("nodeprobe manifest: %w", err)
	}
	if runtime.GOOS+"/"+runtime.GOARCH != m.Platform {
		return Capability{}, fmt.Errorf("nodeprobe: unsupported production platform %s/%s (accepted %s)", runtime.GOOS, runtime.GOARCH, m.Platform)
	}
	binary := os.Getenv("AGENTMIRROR_NODEPROBE_BIN")
	if binary == "" {
		var err error
		binary, err = exec.LookPath("nodeprobe")
		if err != nil {
			binary = m.Binary.Path
		}
	}
	binary, err := verifyAbsoluteRegular(binary, m.Binary.Size, m.Binary.SHA256)
	if err != nil {
		return Capability{}, fmt.Errorf("nodeprobe binary: %w", err)
	}
	if len(m.Corpora) != 2 {
		return Capability{}, fmt.Errorf("nodeprobe manifest: want exactly two canonical corpora, got %d", len(m.Corpora))
	}
	root, rootErr := repositoryRoot()
	paths := make(map[string]string, len(m.Corpora))
	for _, c := range m.Corpora {
		if c.Path != "tools/nodeprobe/fixtures/titles.tsv" && c.Path != "tools/nodeprobe/fixtures/providers.tsv" {
			return Capability{}, fmt.Errorf("nodeprobe manifest: unexpected corpus path %q", c.Path)
		}
		env := corpusEnv(c.Path)
		configured, explicit := os.LookupEnv(env)
		if explicit && configured != "" {
			abs, err := filepath.Abs(configured)
			if err != nil {
				return Capability{}, fmt.Errorf("nodeprobe corpus %s: %w", c.Path, err)
			}
			if _, err := verifyAbsoluteRegular(abs, -1, c.SHA256); err != nil {
				return Capability{}, fmt.Errorf("nodeprobe corpus %s: %w", c.Path, err)
			}
			paths[c.Path] = abs
			continue
		}
		if rootErr != nil {
			return Capability{}, fmt.Errorf("nodeprobe corpus %s: %w (set %s to the exact canonical file)", c.Path, rootErr, env)
		}
		abs := filepath.Join(root, filepath.FromSlash(c.Path))
		if _, err := verifyAbsoluteRegular(abs, -1, c.SHA256); err != nil {
			return Capability{}, fmt.Errorf("nodeprobe corpus %s: %w", c.Path, err)
		}
		paths[c.Path] = abs
	}
	ext := os.Getenv("AGENTMIRROR_NODEPROBE_PI_EXTENSION")
	if ext == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return Capability{}, fmt.Errorf("nodeprobe extension home: %w", err)
		}
		ext = filepath.Join(home, ".pi", "agent", "extensions", "nodeprobe-pi-activity.js")
	}
	ext, extErr := verifyAbsoluteRegular(ext, m.PiExtension.Size, m.PiExtension.SHA256)
	titles, titlesOK := paths["tools/nodeprobe/fixtures/titles.tsv"]
	providers, providersOK := paths["tools/nodeprobe/fixtures/providers.tsv"]
	if !titlesOK || !providersOK {
		return Capability{}, errors.New("nodeprobe manifest: canonical titles/providers corpus missing")
	}
	return Capability{Binary: binary, PiExtension: ext, Titles: titles, Providers: providers, PiExtensionFault: extErr}, nil
}

func corpusEnv(path string) string {
	if strings.HasSuffix(path, "titles.tsv") {
		return "NODEPROBE_FIXTURES"
	}
	return "NODEPROBE_PROVIDERS"
}

func repositoryRoot() (string, error) {
	starts := []string{}
	if _, f, _, ok := runtime.Caller(0); ok {
		starts = append(starts, filepath.Dir(f))
	}
	if wd, err := os.Getwd(); err == nil {
		starts = append(starts, wd)
	}
	for _, start := range starts {
		for dir, n := start, 0; n < 12; n++ {
			if st, err := os.Stat(filepath.Join(dir, "tools", "nodeprobe", "fixtures", "titles.tsv")); err == nil && st.Mode().IsRegular() {
				abs, _ := filepath.Abs(dir)
				return abs, nil
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	return "", errors.New("nodeprobe: repository root with canonical corpora not found")
}

func verifyAbsoluteRegular(path string, size int64, wantHash string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	st, err := os.Stat(abs)
	if err != nil {
		return abs, err
	}
	if !st.Mode().IsRegular() {
		return abs, fmt.Errorf("%s is not a regular file", abs)
	}
	if size >= 0 && st.Size() != size {
		return abs, fmt.Errorf("%s size=%d want=%d", abs, st.Size(), size)
	}
	data, err := os.ReadFile(abs)
	if err != nil {
		return abs, err
	}
	sum := sha256.Sum256(data)
	if got := hex.EncodeToString(sum[:]); got != wantHash {
		return abs, fmt.Errorf("%s sha256=%s want=%s", abs, got, wantHash)
	}
	return abs, nil
}

// Report is nodeprobe report-envelope schema v1.
type Report struct {
	SchemaVersion int         `json:"schema_version"`
	Socket        string      `json:"socket"`
	SampledAt     string      `json:"sampled_at"`
	Nodes         []Node      `json:"nodes"`
	Error         *ProbeError `json:"error,omitempty"`
}

// ProbeError is the accepted report's visible whole-socket failure.
type ProbeError struct {
	Kind    string `json:"kind"`
	Message string `json:"message"`
}

// Node is one typed schema-v1 node observation from the accepted binary.
type Node struct {
	Socket          string          `json:"socket"`
	WorkspacePath   string          `json:"workspace_path"`
	ProjectName     string          `json:"project_name"`
	Session         string          `json:"session"`
	WindowIndex     int             `json:"window_index"`
	WindowName      string          `json:"window_name"`
	PaneID          string          `json:"pane_id"`
	Name            string          `json:"name"`
	Provider        string          `json:"provider"`
	State           string          `json:"state"`
	Activity        string          `json:"activity"`
	SessionName     *string         `json:"session_name"`
	Health          string          `json:"health"`
	BackgroundTasks json.RawMessage `json:"background_tasks"`
	FooterError     *string         `json:"footer_error"`
	Evidence        json.RawMessage `json:"evidence"`
}

// Observation is the independent four-axis status carried into protocol DTOs.
type Observation struct {
	Provider, Activity string
	SessionName        *string
	Health             string
	// DisplayName is optional display-only metadata, never provider/status identity.
	DisplayName string
}

// Unknown returns the honest four-axis result for a zero/duplicate join.
func Unknown() Observation {
	return Observation{Provider: "unknown", Activity: "unknown", Health: "unknown"}
}

// Sampler obtains one accepted report for one already-allowed socket.
type Sampler interface {
	Sample(context.Context, string) (Report, error)
}

// Runner directly executes the version-verified accepted binary.
type Runner struct {
	capability Capability
	timeout    time.Duration
	log        *slog.Logger
}

// NewRunner binds a verified capability to the typed sampler.
func NewRunner(c Capability, logs ...*slog.Logger) *Runner {
	r := &Runner{capability: c, timeout: 5 * time.Second}
	if len(logs) > 0 {
		r.log = logs[0]
	}
	return r
}

func (r *Runner) Sample(ctx context.Context, socket string) (Report, error) {
	started := time.Now()
	rows := 0
	succeeded := false
	defer func() {
		if r.log != nil {
			r.log.Debug("nodeprobe: sample phases", "sample_ms", time.Since(started).Milliseconds(), "processed_panes", rows, "success", succeeded)
		}
	}()
	if !filepath.IsAbs(socket) {
		return Report{}, fmt.Errorf("nodeprobe: socket is not absolute: %q", socket)
	}
	ctx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, r.capability.Binary, "-S", socket)
	cmd.WaitDelay = 250 * time.Millisecond
	cmd.Env = acceptedEnv(r.capability)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return Report{}, fmt.Errorf("nodeprobe socket=%s: %w stdout=%s stderr=%s", socket, err, strings.TrimSpace(stdout.String()), strings.TrimSpace(stderr.String()))
	}
	out := stdout.Bytes()
	var report Report
	dec := json.NewDecoder(strings.NewReader(string(out)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&report); err != nil {
		return Report{}, fmt.Errorf("nodeprobe decode socket=%s: %w", socket, err)
	}
	if report.SchemaVersion != 1 {
		return Report{}, fmt.Errorf("nodeprobe socket=%s schema_version=%d want=1", socket, report.SchemaVersion)
	}
	if report.Error != nil {
		return Report{}, fmt.Errorf("nodeprobe socket=%s kind=%s: %s", socket, report.Error.Kind, report.Error.Message)
	}
	if report.Socket != socket {
		return Report{}, fmt.Errorf("nodeprobe socket=%s report_socket=%s", socket, report.Socket)
	}
	for i := range report.Nodes {
		if err := validateNode(report.Nodes[i]); err != nil {
			return Report{}, fmt.Errorf("nodeprobe socket=%s node=%d: %w", socket, i, err)
		}
	}
	rows = len(report.Nodes)
	succeeded = true
	return report, nil
}

func acceptedEnv(c Capability) []string {
	env := []string{"NODEPROBE_FIXTURES=" + c.Titles, "NODEPROBE_PROVIDERS=" + c.Providers}
	// tmux emits the pane inventory separator according to the active locale.
	// Keep the child environment sanitized, while preserving only the locale
	// operands needed for its UTF-8 output (never the arbitrary parent env).
	for _, key := range []string{"LANG", "LC_CTYPE", "LC_ALL", "PATH", "HOME", "TMPDIR", "NODEPROBE_PI_ACTIVITY_DIR"} {
		if v, ok := os.LookupEnv(key); ok {
			env = append(env, key+"="+v)
		}
	}
	return env
}
func validateNode(n Node) error {
	if n.Session == "" || n.PaneID == "" {
		return errors.New("missing structural identity")
	}
	if n.Provider == "" {
		return errors.New("empty provider")
	}
	if n.Activity != "working" && n.Activity != "idle" && n.Activity != "unknown" {
		return fmt.Errorf("invalid activity %q", n.Activity)
	}
	if n.State != n.Activity {
		return fmt.Errorf("state/activity divergence %q/%q", n.State, n.Activity)
	}
	if n.Health != "normal" && n.Health != "abnormal" && n.Health != "unknown" {
		return fmt.Errorf("invalid health %q", n.Health)
	}
	return nil
}

type key struct {
	Socket, Session, PaneID string
	WindowIndex             int
}

// SampleModel invokes the sampler once per discovered socket and joins only by
// socket+session+window_index+pane_id. Zero/duplicate rows remain unknown.
func SampleModel(ctx context.Context, model *discovery.Model, sampler Sampler) (map[string]Observation, error) {
	bySocket := map[string][]discovery.Pane{}
	for _, ws := range model.Workspaces {
		for _, p := range ws.Panes {
			bySocket[p.Socket] = append(bySocket[p.Socket], p)
		}
	}
	sockets := make([]string, 0, len(bySocket))
	for socket := range bySocket {
		sockets = append(sockets, socket)
	}
	sort.Strings(sockets)
	out := map[string]Observation{}
	for _, socket := range sockets {
		report, err := sampler.Sample(ctx, socket)
		if err != nil {
			return nil, err
		}
		rows := map[key][]Node{}
		for _, n := range report.Nodes {
			k := key{report.Socket, n.Session, n.PaneID, n.WindowIndex}
			rows[k] = append(rows[k], n)
		}
		for _, p := range bySocket[socket] {
			obs := Unknown()
			matches := rows[key{p.Socket, p.Session, p.PaneID, p.WindowIndex}]
			if len(matches) == 1 {
				n := matches[0]
				obs = Observation{Provider: n.Provider, Activity: n.Activity, SessionName: n.SessionName, Health: n.Health}
			}
			out[p.Socket+"\x1f"+p.PaneID] = obs
		}
	}
	enrichCodexNames(ctx, model, sampler, out)
	return out, nil
}
