// Package godoc documents claims that are deliberately false so T3-2 turns red.
package godoc

// [exto-interface] 外骨骼标签谎称存在 `MissingInterface`。
// Ghost names a helper that does not exist: `GhostHelper`.
func Ghost() int { return 1 }

// BadPath names a file that does not exist: docs/never-created.md
func BadPath() int { return 2 }

// FlagRef names a CLI flag that is not registered: --no-such-flag
func FlagRef() int { return 3 }
