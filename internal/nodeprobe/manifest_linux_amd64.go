//go:build linux && amd64

package nodeprobe

import _ "embed"

//go:embed accepted-source-linux-amd64.json
var manifestBytes []byte
