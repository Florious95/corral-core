package pairing

// token.go implements the pairing-token source (requirement 011 route (a),
// docs/protocol.md §9): an auto-generated, persisted token that reuses across
// restarts so already-paired devices keep working.
//
// The token is write-only from the server's perspective: it appears only in
// the QR and the printed onboarding guide (the two legal exits), and must
// never surface in a log line or error string. Callers hand it to the
// api.TokenValidator seam and log only a boolean/source, never the value.

import (
	"crypto/rand"
	"encoding/base32"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// tokenFile is the name of the persisted token file inside the pairing dir.
const tokenFile = "token"

// tokenBytes is the entropy of a generated token: 16 bytes = 128 bits.
const tokenBytes = 16

// tokenChars is the base32-encoded length of tokenBytes (ceil(128/5) = 26).
const tokenChars = (tokenBytes*8 + 4) / 5

// TokenDir returns the directory that holds pairing state
// (os.UserConfigDir()/agentmirror), the same per-user config root tsnetd uses,
// so all daemon state lives under one tree. The token file is TokenDir()/token.
// @contract
// @pre none
// @post 返回 os.UserConfigDir()/agentmirror
// @err os.UserConfigDir 失败返回包装错误
// @inv none
func TokenDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("pairing: resolve user config dir: %w", err)
	}
	return filepath.Join(base, "agentmirror"), nil
}

// GenerateToken returns a fresh 128-bit token, base32-encoded so it is safe to
// type by hand (alphabet A-Z, 2-7 — no 0/O/1/I ambiguity) and short enough for
// a QR. crypto/rand guarantees unpredictability: the token is a credential.
// @contract
// @pre none
// @post 返回 128 位随机、base32（A-Z, 2-7）编码、26 字符的无填充 token
// @err crypto/rand 读失败时返回包装错误
// @inv none
func GenerateToken() (string, error) {
	buf := make([]byte, tokenBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("pairing: generate token: %w", err)
	}
	return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(buf), nil
}

// LoadToken reads the persisted token from dir/token. ok is false when the
// file is absent or blank (the daemon has never auto-generated a token); a
// read failure on an existing file is an error. Whitespace is trimmed so a
// hand-edited file with a trailing newline still authenticates.
// @contract
// @pre none
// @post 文件不存在或 trim 后为空时 ok=false；否则 ok=true 返回 trim 后的 token
// @err 已存在文件的读失败返回包装错误
// @inv none
func LoadToken(dir string) (token string, ok bool, err error) {
	b, err := os.ReadFile(filepath.Join(dir, tokenFile))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", false, nil
		}
		return "", false, fmt.Errorf("pairing: read token file: %w", err)
	}
	s := strings.TrimSpace(string(b))
	if s == "" {
		return "", false, nil
	}
	return s, true, nil
}

// SaveToken persists token to dir/token with owner-only 0600 permission, via a
// temp-file rename so a crash mid-write never leaves a partial token behind.
// The error never embeds the token value (§9).
// @contract
// @pre none
// @post token 以 0600 权限写入 dir/token（临时文件改名，不留半成品）
// @err 建目录、写临时文件、改名任一步失败返回包装错误；错误串不含 token 值（§9）
// @inv none
func SaveToken(dir, token string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("pairing: create token dir: %w", err)
	}
	path := filepath.Join(dir, tokenFile)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(token), 0o600); err != nil {
		return fmt.Errorf("pairing: write token file: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("pairing: finalize token file: %w", err)
	}
	return nil
}

// EnsureToken resolves the effective pairing token. An explicit token (flag or
// env, config.Token) wins verbatim and is never persisted; otherwise an
// auto-generated token is loaded from dir or created and persisted there, so a
// daemon restart reuses the same token and paired devices stay paired.
// @contract
// @pre none
// @post explicit 非空时原样返回且不落盘；否则返回 dir 下已持久化 token 或新生成并持久化
// @err LoadToken/GenerateToken/SaveToken 失败时传播包装错误
// @inv token 值不出现于错误串（§9）
func EnsureToken(explicit, dir string) (string, error) {
	if explicit != "" {
		return explicit, nil
	}
	if tok, ok, err := LoadToken(dir); err != nil {
		return "", err
	} else if ok {
		return tok, nil
	}
	tok, err := GenerateToken()
	if err != nil {
		return "", err
	}
	if err := SaveToken(dir, tok); err != nil {
		return "", err
	}
	return tok, nil
}
