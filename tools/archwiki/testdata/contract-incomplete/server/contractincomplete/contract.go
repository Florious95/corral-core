// Package contractincomplete holds symbols with incomplete contracts.
package contractincomplete

// Add has a contract but is missing the error and invariant tags.
// @contract
// @pre a >= 0
// @post result >= 0
func Add(a, b int) int { return a + b }
