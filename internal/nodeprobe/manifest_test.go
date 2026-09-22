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
	if m.SourceCommit != "bf85a4d10" || m.SourceTree != "0e12c1464b4898429b6ae2717911b3b23d591175" {
		t.Fatalf("source=%s tree=%s", m.SourceCommit, m.SourceTree)
	}
	if m.Platform != "darwin/arm64" || m.Binary.SHA256 != "57073fd42d7bdcfbd339687c09531d01aaaa02e02271257a5dd2a9ce72ffb2a3" || m.Binary.Size != 861088 {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.SHA256 != "e747b844eddd2672b3fef7eb2873530ffc8e296dcae8fd7c4b9a8b9a4b7662c6" || m.PiExtension.Size != 5798 {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "c9e02d01821df7d7afe2292fefb211cefea7e3abecde8b35bd9ffa2a0721ee7e" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
