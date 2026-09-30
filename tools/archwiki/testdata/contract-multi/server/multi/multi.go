// Package multi holds two contracted symbols in one file.
package multi

// Full is complete: all four contract tags present.
// @contract
// @pre input positive
// @post result positive
// @err none
// @inv no side effects
func Full(input int) int { return input }

// Half is missing @err and @inv — must be caught even though Full is complete.
// @contract
// @pre input nonempty
// @post result joined
func Half(input string) string { return input }
