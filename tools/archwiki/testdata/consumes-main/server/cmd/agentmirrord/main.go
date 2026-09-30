// Command agentmirrord is the daemon entry of the fixture.
//
// It is a command package: the directory is named agentmirrord but the Go
// declaration is `package main`. This is the red-test target — the @consumes
// below must be readable by _declared_consumes() so T3-4 sees declarations
// consistent with the import graph.
//
// It declares TWO @consumes targets on separate lines — this exercises the
// repeated-tag path in _extract_tags() (a single doc block declaring multiple
// consumers), which is exactly how the real cmd/agentmirrord/main.go declares
// its four consumers.
// @consumes internal/config
// @consumes internal/proto
package main

import (
	"github.com/remote-agent/fixture-consumes-main/internal/config"
	"github.com/remote-agent/fixture-consumes-main/internal/proto"
)

func main() { _ = config.Load(); _ = proto.Value() }
