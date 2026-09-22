//go:build darwin && arm64

package nodeprobe

import _ "embed"

//go:embed accepted-source.json
var manifestBytes []byte
