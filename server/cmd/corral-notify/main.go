// Command corral-notify submits one explicit Agent task notification to the
// local agentmirrord daemon. It never scans Agent output or installs hooks.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/agentmirror/agentmirror/internal/api"
	"github.com/agentmirror/agentmirror/internal/pairing"
)

const usage = `Usage: corral-notify "消息正文" [--title "标题"] [--level info|success|warning|error] [--session <ref>] [--agent-name <name>]
       corral-notify --stdin [options]

Options may appear before or after the body. --socket and --request-id are
available for isolated tests and deliberate same-intent retries.`

type options struct {
	body, title, level, session, agentName, socket, requestID string
	stdin, help                                               bool
}

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	opts, err := parseArgs(args)
	if err != nil {
		fmt.Fprintln(stderr, "corral-notify: usage:", err)
		return 2
	}
	if opts.help {
		fmt.Fprintln(stdout, usage)
		return 0
	}
	if opts.session == "" {
		opts.session = os.Getenv("CORRAL_SESSION_REF")
	}
	if opts.session == "" {
		opts.session = tmuxSessionRef()
	}
	if opts.session == "" {
		fmt.Fprintln(stderr, "warning: no session link; notification will remain readable without a pane target")
	}
	if err := validateBody(opts.body); err != nil {
		fmt.Fprintln(stderr, "corral-notify: usage:", err)
		return 2
	}
	if opts.title == "" {
		opts.title = "Agent 任务通知"
	}
	if opts.level == "" {
		opts.level = "success"
	}
	if opts.level != "info" && opts.level != "success" && opts.level != "warning" && opts.level != "error" {
		fmt.Fprintln(stderr, "corral-notify: usage: invalid level")
		return 2
	}
	if err := validateText(opts.title, 256, false); err != nil {
		fmt.Fprintln(stderr, "corral-notify: usage: invalid title")
		return 2
	}
	if opts.agentName != "" {
		if err := validateText(opts.agentName, 128, false); err != nil {
			fmt.Fprintln(stderr, "corral-notify: usage: invalid agent-name")
			return 2
		}
	}
	if opts.socket == "" {
		opts.socket = os.Getenv("CORRAL_NOTIFY_SOCKET")
	}
	if opts.socket == "" {
		dir, err := effectiveStateDir()
		if err != nil {
			fmt.Fprintln(stderr, "corral-notify: daemon unavailable: ", err)
			return 3
		}
		opts.socket = api.NotificationSocketPath(dir)
	}
	if opts.requestID != "" && (len(opts.requestID) > 128 || strings.ContainsAny(opts.requestID, "\x00\r\n")) {
		fmt.Fprintln(stderr, "corral-notify: usage: invalid request-id")
		return 2
	}
	if opts.requestID == "" {
		opts.requestID, err = uuidv4()
		if err != nil {
			fmt.Fprintln(stderr, "corral-notify: daemon unavailable: request id")
			return 3
		}
	}
	result, code, err := postNotification(opts)
	if err != nil {
		fmt.Fprintln(stderr, "corral-notify:", err)
		return code
	}
	_ = json.NewEncoder(stdout).Encode(result)
	return 0
}

func parseArgs(args []string) (options, error) {
	var o options
	var positional []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		if a == "--" {
			positional = append(positional, args[i+1:]...)
			break
		}
		if a == "--help" || a == "-h" {
			o.help = true
			continue
		}
		if a == "--stdin" {
			o.stdin = true
			continue
		}
		name, value, hasValue := splitOption(a)
		if !hasValue && strings.HasPrefix(a, "--") {
			if i+1 >= len(args) {
				return o, fmt.Errorf("%s requires a value", a)
			}
			value = args[i+1]
			i++
		}
		switch name {
		case "--title":
			o.title = value
		case "--level":
			o.level = value
		case "--session":
			o.session = value
		case "--agent-name":
			o.agentName = value
		case "--socket":
			o.socket = value
		case "--request-id":
			o.requestID = value
		case "":
			if strings.HasPrefix(a, "-") {
				return o, fmt.Errorf("unknown option %s", a)
			}
			positional = append(positional, a)
		default:
			return o, fmt.Errorf("unknown option %s", name)
		}
	}
	if o.help {
		return o, nil
	}
	if o.stdin == (len(positional) > 0) {
		return o, fmt.Errorf("use exactly one body argument or --stdin")
	}
	if o.stdin {
		data, err := io.ReadAll(io.LimitReader(os.Stdin, 16385))
		if err != nil {
			return o, err
		}
		o.body = string(data)
	} else if len(positional) == 1 {
		o.body = positional[0]
	} else {
		return o, fmt.Errorf("use exactly one body argument or --stdin")
	}
	return o, nil
}

func splitOption(a string) (string, string, bool) {
	if !strings.HasPrefix(a, "--") {
		return "", "", false
	}
	if i := strings.IndexByte(a, '='); i >= 0 {
		return a[:i], a[i+1:], true
	}
	return a, "", false
}

func postNotification(o options) (api.NotificationIPCResponse, int, error) {
	payload, err := json.Marshal(api.NotificationIPCRequest{RequestID: o.requestID, Title: o.title, Body: o.body, SessionRef: o.session, AgentName: o.agentName, Level: o.level})
	if err != nil {
		return api.NotificationIPCResponse{}, 2, err
	}
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", o.socket)
	}}
	client := &http.Client{Transport: transport, Timeout: 3 * time.Second}
	defer transport.CloseIdleConnections()
	req, err := http.NewRequest(http.MethodPost, "http://localhost/v1/notifications", strings.NewReader(string(payload)))
	if err != nil {
		return api.NotificationIPCResponse{}, 3, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "timeout") {
			return api.NotificationIPCResponse{}, 5, fmt.Errorf("outcome_unknown: %w", err)
		}
		return api.NotificationIPCResponse{}, 3, fmt.Errorf("daemon unavailable: %w", err)
	}
	defer resp.Body.Close()
	var result api.NotificationIPCResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 128<<10)).Decode(&result); err != nil {
		return result, 5, fmt.Errorf("outcome_unknown: invalid daemon response")
	}
	if resp.StatusCode != http.StatusOK || !result.Accepted {
		return result, 4, fmt.Errorf("not_accepted: %s", result.Error)
	}
	return result, 0, nil
}

func effectiveStateDir() (string, error) {
	if dir := strings.TrimSpace(os.Getenv("AGENTMIRROR_STATE_DIR")); dir != "" {
		return dir, nil
	}
	return pairing.TokenDir()
}

func tmuxSessionRef() string {
	tmux, pane := os.Getenv("TMUX"), os.Getenv("TMUX_PANE")
	if tmux == "" || pane == "" {
		return ""
	}
	last := strings.LastIndexByte(tmux, ',')
	if last < 0 {
		return ""
	}
	prefix := tmux[:last]
	prev := strings.LastIndexByte(prefix, ',')
	if prev < 0 {
		return ""
	}
	return prefix[:prev] + "\x1f" + pane
}

func validateBody(body string) error { return validateText(body, 16384, false) }
func validateText(value string, max int, _ bool) error {
	if !utf8.ValidString(value) || len(value) == 0 || len(value) > max || strings.TrimSpace(value) == "" {
		return fmt.Errorf("empty, invalid UTF-8, or too long")
	}
	for _, r := range value {
		if r == 0 || r < 0x20 && r != '\n' && r != '\r' && r != '\t' {
			return fmt.Errorf("control character is not allowed")
		}
	}
	return nil
}

func uuidv4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0xf) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	out := make([]byte, 36)
	hex.Encode(out[0:8], b[0:4])
	out[8] = '-'
	hex.Encode(out[9:13], b[4:6])
	out[13] = '-'
	hex.Encode(out[14:18], b[6:8])
	out[18] = '-'
	hex.Encode(out[19:23], b[8:10])
	out[23] = '-'
	hex.Encode(out[24:36], b[10:16])
	return string(out), nil
}
