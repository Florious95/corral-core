package nodeprobe

import (
	"encoding/json"
	"testing"
)

func TestAcceptedManifestPinsNSourceAndArtifacts(t *testing.T) {
	var m manifest
	if err := json.Unmarshal(manifestBytes, &m); err != nil {
		t.Fatal(err)
	}
	if m.SourceCommit != "23f2345da605d66cf85cefc9bd4b8d972d6bfb4c" || m.SourceTree != "a6f16f70cf5df927b744affc1414bc1f1e5689d7" {
		t.Fatalf("source=%s tree=%s", m.SourceCommit, m.SourceTree)
	}
	if m.Platform != "darwin/arm64" || m.Binary.SHA256 != "0b63b60c9f8e33d626699779fe29f0520d42b66a20f0cef9ddb62af47f9452ea" || m.Binary.Size != 880960 {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.SHA256 != "c28855ea4ac6f411044fb9a8066c2e5c3e5580197c1ae412e88d23d07467b714" || m.PiExtension.Size != 6844 {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "962b7abf5e0fe3abdc1b8673e8a4109b1ce336fded97c21b815ff7e3b8d86429" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "337ac49d69e6df5ccf5db13a8bbd58ca51b16ab66e477b16cafcee4f0aa8525c" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
