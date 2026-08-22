package api

// token.go defines the pairing-token seam. The auth frame carries the token
// once, server-side it is compared and forgotten: it is never echoed in any
// reply and never written to a log (docs/protocol.md §9, requirement 011
// route (a)).

import (
	"context"
	"crypto/subtle"
)

// TokenValidator decides whether an auth frame's token is accepted for a
// connection. The default is staticToken, validating in constant time against
// the token configured at startup (Options.Token); the seam stays so a future
// pairing flow can plug in without touching auth frame handling. pairing
// generates the token itself (task pairing-security); it does not replace this
// validator — cmd/agentmirrord wires the resolved token in via Options.Token.
type TokenValidator interface {
	// ValidateToken reports whether token authenticates this connection.
	// @contract
	// @pre token 为 auth 帧携带的串（可为空）
	// @post 返回是否接受该连接；token 不被记录或回显
	// @err none
	// @inv token 值绝不被回显或写入日志（默认实现 staticToken 另以常数时间比较防时序侧信道）
	ValidateToken(ctx context.Context, token string) bool
}

// staticToken validates against the token wired in Options. Constant-time
// comparison avoids a timing side channel on token length or content.
type staticToken struct {
	token string
}

func (s staticToken) ValidateToken(_ context.Context, token string) bool {
	return subtle.ConstantTimeCompare([]byte(s.token), []byte(token)) == 1
}
