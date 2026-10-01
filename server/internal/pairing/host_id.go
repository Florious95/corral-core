package pairing

// host_id.go owns the daemon's public host identity. It has an independent
// lifetime from the pairing token: rotating/replacing the token must not make
// a discovered host look like a new machine.

import (
	"crypto/rand"
	"encoding/base32"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	hostIDFile  = "host_id"
	hostIDBytes = 16
	hostIDChars = (hostIDBytes*8 + 4) / 5
)

// GenerateHostID returns a fresh 128-bit, unpadded base32 identity.
// @contract
// @pre none
// @post 返回 26 字符、无填充、base32 大写 host_id
// @err crypto/rand 失败时返回包装错误
func GenerateHostID() (string, error) {
	buf := make([]byte, hostIDBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("pairing: generate host id: %w", err)
	}
	return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(buf), nil
}

// LoadHostID reads dir/host_id. Missing or blank files return ok=false.
func LoadHostID(dir string) (id string, ok bool, err error) {
	b, err := os.ReadFile(filepath.Join(dir, hostIDFile))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", false, nil
		}
		return "", false, fmt.Errorf("pairing: read host id file: %w", err)
	}
	id = strings.TrimSpace(string(b))
	if id == "" {
		return "", false, nil
	}
	if !ValidHostID(id) {
		return "", false, errors.New("pairing: invalid host id file")
	}
	return id, true, nil
}

// SaveHostID atomically persists an identity with owner-only permissions.
func SaveHostID(dir, id string) error {
	if !ValidHostID(id) {
		return errors.New("pairing: invalid host id")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("pairing: create host id dir: %w", err)
	}
	path := filepath.Join(dir, hostIDFile)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(id), 0o600); err != nil {
		return fmt.Errorf("pairing: write host id file: %w", err)
	}
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("pairing: protect host id file: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("pairing: finalize host id file: %w", err)
	}
	return nil
}

// EnsureHostID loads the stable identity or creates it once. It does not read
// or alter the token file, so token rotation preserves this identity.
func EnsureHostID(dir string) (string, error) {
	if id, ok, err := LoadHostID(dir); err != nil {
		return "", err
	} else if ok {
		return id, nil
	}
	id, err := GenerateHostID()
	if err != nil {
		return "", err
	}
	if err := SaveHostID(dir, id); err != nil {
		return "", err
	}
	return id, nil
}

// ValidHostID accepts only the exact generated representation.
func ValidHostID(id string) bool {
	if len(id) != hostIDChars {
		return false
	}
	for _, r := range id {
		if !((r >= 'A' && r <= 'Z') || (r >= '2' && r <= '7')) {
			return false
		}
	}
	decoded, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(id)
	return err == nil && len(decoded) == hostIDBytes
}
