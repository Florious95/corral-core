// Package godoc documents claims that are all true so T3-2 stays green.
package godoc

// [exto-interface] 外骨骼标签引用的 `Configure` 真实存在。
// Configure uses the real `--listen` flag registered by package config.
func Configure() int { return 1 }

// Seen real symbols: `Configure`, `Register`, `--listen`.
func Seen() int { return 2 }
