// Package contractcomplete holds symbols with complete contracts.
package contractcomplete

// Div has all four contract tags.
// @contract
// @pre b != 0
// @post result * b <= a
// @err division by zero
// @inv a and b are unchanged
func Div(a, b int) int { return a / b }

// NoInv has no invariant, which is fine: explicit none is a complete contract.
// @contract
// @pre a > 0
// @post result > 0
// @err none
// @inv none
func NoInv(a int) int { return a }
