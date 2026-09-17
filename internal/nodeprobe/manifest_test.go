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
	if m.SourceCommit != "dbe81822928449825ab77c0cca9285735e5baf5a" || m.SourceTree != "6d2d98d47a1a322e17cf38a01dde84c57a513f20" {
		t.Fatalf("source=%s tree=%s", m.SourceCommit, m.SourceTree)
	}
	if m.Platform != "darwin/arm64" || m.Binary.SHA256 != "57073fd42d7bdcfbd339687c09531d01aaaa02e02271257a5dd2a9ce72ffb2a3" || m.Binary.Size != 861088 {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.SHA256 != "51ffcad3f68ac22330d98ba0240b81f41b210939709a91637c917e1555494c27" || m.PiExtension.Size != 3572 {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
