package pairing

import (
	"os"
	"path/filepath"
	"testing"
)

func TestHostIDPersistsAcrossTokenRotation(t *testing.T) {
	dir := t.TempDir()
	id, err := EnsureHostID(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !ValidHostID(id) {
		t.Fatalf("invalid generated host id %q", id)
	}
	st, err := os.Stat(filepath.Join(dir, hostIDFile))
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("host id mode %o", st.Mode().Perm())
	}
	if err := SaveToken(dir, "replacement-token"); err != nil {
		t.Fatal(err)
	}
	again, err := EnsureHostID(dir)
	if err != nil {
		t.Fatal(err)
	}
	if again != id {
		t.Fatalf("host id changed after token rotation: %q != %q", again, id)
	}
}

func TestHostIDRejectsMalformedPersistedValue(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, hostIDFile), []byte("not-an-id"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := EnsureHostID(dir); err == nil {
		t.Fatal("malformed host id must fail closed")
	}
}
