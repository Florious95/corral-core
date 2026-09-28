// Package undoc documents the package but leaves one exported symbol undocumented.
package undoc

// Documented is fully documented.
func Documented() int { return 1 }

func Undocumented() int { return 2 }
