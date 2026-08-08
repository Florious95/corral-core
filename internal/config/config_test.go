package config

// config_test.go covers the ws-api-added configuration surface (token, upload
// dir, byte caps, list interval) and the flag/env/default precedence for it,
// so a future regression in the daemon's runtime settings is caught here.

import (
	"testing"
	"time"
)

// TestDefaults verifies the effective defaults for the ws-api settings when
// neither flag nor env overrides them. The token defaulting to empty is the
// important one: an unconfigured daemon refuses every auth (no empty-token
// bypass) rather than accepting anonymous connections.
func TestDefaults(t *testing.T) {
	t.Setenv("AGENTMIRROR_TOKEN", "")
	t.Setenv("AGENTMIRROR_UPLOAD_DIR", "")
	t.Setenv("AGENTMIRROR_MAX_UPLOAD_BYTES", "")
	t.Setenv("AGENTMIRROR_MAX_INPUT_BYTES", "")
	t.Setenv("AGENTMIRROR_LIST_INTERVAL", "")

	cfg, err := Load(nil)
	if err != nil {
		t.Fatalf("Load defaults: %v", err)
	}
	if cfg.Token != "" {
		t.Errorf("default Token = %q, want empty (no anonymous bypass)", cfg.Token)
	}
	if cfg.UploadDir != "" {
		t.Errorf("default UploadDir = %q, want empty (api falls back to ~/Downloads)", cfg.UploadDir)
	}
	if cfg.MaxUploadBytes != 20971520 {
		t.Errorf("default MaxUploadBytes = %d, want 20971520 (20 MiB)", cfg.MaxUploadBytes)
	}
	if cfg.MaxInputBytes != 1048576 {
		t.Errorf("default MaxInputBytes = %d, want 1048576 (1 MiB)", cfg.MaxInputBytes)
	}
	if cfg.ListInterval != 2*time.Second {
		t.Errorf("default ListInterval = %v, want 2s", cfg.ListInterval)
	}
}

// TestFlagWinsOverEnv verifies flag > env precedence for the numeric settings.
func TestFlagWinsOverEnv(t *testing.T) {
	t.Setenv("AGENTMIRROR_MAX_UPLOAD_BYTES", "999")

	cfg, err := Load([]string{"-max-upload-bytes", "12345"})
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.MaxUploadBytes != 12345 {
		t.Errorf("flag MaxUploadBytes = %d, want 12345 (flag beats env)", cfg.MaxUploadBytes)
	}
}

// TestEnvFallback verifies env > default when no flag is given.
func TestEnvFallback(t *testing.T) {
	t.Setenv("AGENTMIRROR_TOKEN", "env-token")
	t.Setenv("AGENTMIRROR_LIST_INTERVAL", "750ms")

	cfg, err := Load(nil)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Token != "env-token" {
		t.Errorf("Token = %q, want env-token", cfg.Token)
	}
	if cfg.ListInterval != 750*time.Millisecond {
		t.Errorf("ListInterval = %v, want 750ms", cfg.ListInterval)
	}
}

// TestInvalidNumericRejected verifies a malformed numeric setting is a hard
// error rather than a silent clamp.
func TestInvalidNumericRejected(t *testing.T) {
	if _, err := Load([]string{"-max-upload-bytes", "notanumber"}); err == nil {
		t.Fatal("Load with bad max-upload-bytes must fail")
	}
	if _, err := Load([]string{"-list-interval", "notaduration"}); err == nil {
		t.Fatal("Load with bad list-interval must fail")
	}
	if _, err := Load([]string{"-max-upload-bytes", "-5"}); err == nil {
		t.Fatal("Load with negative max-upload-bytes must fail")
	}
}
