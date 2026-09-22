//go:build !((darwin && arm64) || (linux && amd64))

package nodeprobe

import _ "embed"

// Keep unsupported targets fail-closed against the canonical desktop
// coordinate; supported builds select an exact target manifest above.
//
//go:embed accepted-source.json
var manifestBytes []byte
