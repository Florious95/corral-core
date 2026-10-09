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
	if m.SourceCommit != "f9e896bf1c884a53c555b661160d4b3f1093315f" || m.SourceTree != "24c62183a29df79d90fab7a997019ddc179372b3" {
		t.Fatalf("source=%s tree=%s", m.SourceCommit, m.SourceTree)
	}
	if m.Platform != "darwin/arm64" || m.Binary.SHA256 != "4c2cd0cd420c9e78e82980cbab5d03957d73a8e4745e596de84860d48066f934" || m.Binary.Size != 860640 {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.SHA256 != "c28855ea4ac6f411044fb9a8066c2e5c3e5580197c1ae412e88d23d07467b714" || m.PiExtension.Size != 6844 {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "962b7abf5e0fe3abdc1b8673e8a4109b1ce336fded97c21b815ff7e3b8d86429" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "737a527de80c9dd6a82fe7dfdcfef7f4a0efb63d1080bbec21a5e4e095e9a9d0" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
