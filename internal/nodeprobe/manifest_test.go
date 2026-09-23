package nodeprobe

import (
	"encoding/json"
	"runtime"
	"testing"
)

func TestAcceptedManifestPinsNSourceAndArtifacts(t *testing.T) {
	var m manifest
	if err := json.Unmarshal(manifestBytes, &m); err != nil {
		t.Fatal(err)
	}
	wantPlatform := runtime.GOOS + "/" + runtime.GOARCH
	wantCommit := "bf85a4d10"
	wantTree := "0e12c1464b4898429b6ae2717911b3b23d591175"
	wantBinaryHash := "57073fd42d7bdcfbd339687c09531d01aaaa02e02271257a5dd2a9ce72ffb2a3"
	wantBinarySize := int64(861088)
	wantExtensionPath := "/Users/alauda/.pi/agent/extensions/nodeprobe-pi-activity.js"
	wantExtensionSourcePath := "src-tauri/resources/nodeprobe-pi-activity.js"
	wantExtensionSourceCommit := "ccf8324ebe29e35038412ffad03157e269029232"
	wantExtensionSourceTree := "6c5e782393741fe229216320fd14e2b5d1100231"
	if wantPlatform == "linux/amd64" {
		wantCommit = "a7185aa2b93460ac9fd289c0de63d7767f8ef749"
		wantTree = "28e3aab4fac89374c0ac4b8c80ba7f13aaae2c75"
		wantBinaryHash = "61d6dd99e7d7135e20b885b00724fb1359c1643c634857702239a092faa4e958"
		wantBinarySize = 1108816
		wantExtensionPath = "/home/alaudalancy/.pi/agent/extensions/nodeprobe-pi-activity.js"
	}
	if m.SourceCommit != wantCommit || m.SourceTree != wantTree {
		t.Fatalf("source=%s tree=%s platform=%s", m.SourceCommit, m.SourceTree, wantPlatform)
	}
	if m.Platform != wantPlatform || m.Binary.SHA256 != wantBinaryHash || m.Binary.Size != wantBinarySize {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.Path != wantExtensionPath || m.PiExtension.SHA256 != "c28855ea4ac6f411044fb9a8066c2e5c3e5580197c1ae412e88d23d07467b714" || m.PiExtension.Size != 6844 || m.PiExtension.SourcePath != wantExtensionSourcePath || m.PiExtension.SourceCommit != wantExtensionSourceCommit || m.PiExtension.SourceTree != wantExtensionSourceTree {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "c9e02d01821df7d7afe2292fefb211cefea7e3abecde8b35bd9ffa2a0721ee7e" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
