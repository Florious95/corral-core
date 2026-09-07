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
	if m.SourceCommit != "6e47021a7e3502843d490a2eeb0342c78c0231f3" || m.SourceTree != "047bfb0539047f592ef64d4d8bf9dfaba4278e20" {
		t.Fatalf("source=%s tree=%s", m.SourceCommit, m.SourceTree)
	}
	if m.Platform != "darwin/arm64" || m.Binary.SHA256 != "e5667b9ebe931de7805a87538e03b9a6ee1fb9cb9250c25282f8b054268226b5" || m.Binary.Size != 885504 {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.SHA256 != "51ffcad3f68ac22330d98ba0240b81f41b210939709a91637c917e1555494c27" || m.PiExtension.Size != 3572 {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
