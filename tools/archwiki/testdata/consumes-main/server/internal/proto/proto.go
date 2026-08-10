// Package proto provides a second real import target for cmd/agentmirrord,
// so its doc block can declare multiple @consumes on separate lines (the
// repeated-tag collapse path). proto itself imports nothing.
package proto

// Value returns the proto value.
func Value() int { return 1 }
