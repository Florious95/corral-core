// Package truthbody is the T3-2 expanded-surface positive control (Go side):
// body comments reference real symbols/paths/flags.
package truthbody

// Real is a real symbol.
func Real() int { return 1 }

// Body references only real things in a body comment.
func Body() int {
	// 引用真实符号 `Real`、真实路径 docs/real-body.md、真实 flag --listen
	return Real()
}
