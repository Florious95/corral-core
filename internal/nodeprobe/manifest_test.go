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
	if wantPlatform == "linux/amd64" {
		wantCommit = "a7185aa2b93440ac9fd289c0de63d7767f8ef749"
		wantTree = "28e3aab4fac89374c0ac4b8c80ba7f13aaae2c75"
		wantBinaryHash = "7a9d0d3c3c02b736307afdd92af66feaf51e328b9fcf9c502471b06a363bf847"
		wantBinarySize = 642168
	}
	if m.SourceCommit != wantCommit || m.SourceTree != wantTree {
		t.Fatalf("source=%s tree=%s platform=%s", m.SourceCommit, m.SourceTree, wantPlatform)
	}
	if m.Platform != wantPlatform || m.Binary.SHA256 != wantBinaryHash || m.Binary.Size != wantBinarySize {
		t.Fatalf("binary=%+v platform=%s", m.Binary, m.Platform)
	}
	if m.PiExtension.SHA256 != "e747b844eddd2672b3fef7eb2873530ffc8e296dcae8fd7c4b9a8b9a4b7662c6" || m.PiExtension.Size != 5798 {
		t.Fatalf("extension=%+v", m.PiExtension)
	}
	if len(m.Corpora) != 2 || m.Corpora[0].Path != "tools/nodeprobe/fixtures/titles.tsv" || m.Corpora[0].SHA256 != "cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58" || m.Corpora[1].Path != "tools/nodeprobe/fixtures/providers.tsv" || m.Corpora[1].SHA256 != "c9e02d01821df7d7afe2292fefb211cefea7e3abecde8b35bd9ffa2a0721ee7e" {
		t.Fatalf("corpora=%+v", m.Corpora)
	}
}
