package protocol

import "errors"

// Sentinel errors returned by the codec (MarshalFrame / UnmarshalFrame /
// EncodeBinary / DecodeBinary) and by payload validation. They are wrapped
// with context by the codec, so callers MUST compare with errors.Is, not ==.
//
// A codec error means THIS side failed to parse or refused to emit a frame.
// It is distinct from an ErrorFrame value, which is a valid frame a peer sent
// us describing a failure.
var (
	// ErrMissingVersion means a JSON frame had no "v" field.
	ErrMissingVersion = errors.New("protocol: missing protocol version")
	// ErrUnsupportedVersion means the "v" field (or binary version byte) is
	// not supported by this build.
	ErrUnsupportedVersion = errors.New("protocol: unsupported protocol version")
	// ErrUnknownType means the frame "type" discriminator is not known.
	ErrUnknownType = errors.New("protocol: unknown frame type")
	// ErrBadPayload means the frame payload does not match the declared type.
	ErrBadPayload = errors.New("protocol: malformed frame payload")
	// ErrInvalidField means a required field is missing or a value is outside
	// its closed set.
	ErrInvalidField = errors.New("protocol: invalid or missing required field")
	// ErrBadMagic means a binary frame did not start with BinaryMagic.
	ErrBadMagic = errors.New("protocol: bad binary frame magic")
	// ErrUnknownKind means a binary frame carried an unknown kind byte.
	ErrUnknownKind = errors.New("protocol: unknown binary frame kind")
	// ErrTruncated means a binary frame ended before its declared header was
	// complete.
	ErrTruncated = errors.New("protocol: truncated binary frame")
	// ErrInvalidRef means a session ref is empty (JSON frames) or has zero
	// length (binary frames).
	ErrInvalidRef = errors.New("protocol: invalid or empty session ref")
	// ErrRefTooLong means a binary session ref exceeds 255 bytes.
	ErrRefTooLong = errors.New("protocol: session ref exceeds 255 bytes")
	// ErrInvalidGeometry is a reserved sentinel for a zero terminal dimension
	// (rows/cols). This package never constructs it — bad geometry is rejected
	// via ErrInvalidField by the Resize/Subscribe Validate methods and the
	// binary codec. It is retained only so internal/api/ws_conn.go's
	// classifyCodecError can errors.Is-match it.
	ErrInvalidGeometry = errors.New("protocol: invalid terminal geometry")
	// ErrInvalidCount is a reserved sentinel for a zero line count. As with
	// ErrInvalidGeometry it is never constructed by this package (scrollback
	// count validation uses ErrInvalidField) and exists only for ws_conn.go's
	// errors.Is matching.
	ErrInvalidCount = errors.New("protocol: invalid line count")
)

// ErrorCode is the machine-readable code of an ErrorFrame (S→C). The client
// switches on it to decide recovery; it is a closed set.
type ErrorCode string

const (
	// ErrCodeUnauthorized means the connection acted before, or without, valid
	// authentication.
	ErrCodeUnauthorized ErrorCode = "unauthorized"
	// ErrCodeBadFrame means the server could not parse a control frame.
	ErrCodeBadFrame ErrorCode = "bad_frame"
	// ErrCodeInvalidField means a required field is missing or out of range.
	// Distinct from bad_frame (true decode failure). Reason must name the field.
	ErrCodeInvalidField ErrorCode = "invalid_field"
	// ErrCodeUnsupportedVersion means the client's protocol version is not
	// supported; the server closes after sending this.
	ErrCodeUnsupportedVersion ErrorCode = "unsupported_version"
	// ErrCodeUnsupportedType means the client sent an unknown frame type.
	ErrCodeUnsupportedType ErrorCode = "unsupported_type"
	// ErrCodeSessionNotFound means a ref referenced no live session.
	ErrCodeSessionNotFound ErrorCode = "session_not_found"
	// ErrCodeInternal means an unexpected server-side failure.
	ErrCodeInternal ErrorCode = "internal"
)

// InputFailReason is the machine-readable reason of a rejected InputAck
// (S→C). It is present if and only if OK=false; it is a closed set.
type InputFailReason string

const (
	// InputFailSessionNotFound: the target session no longer exists.
	InputFailSessionNotFound InputFailReason = "session_not_found"
	// InputFailNotSubscribed: the client is not subscribed to the session
	// (input injection requires an active subscription).
	InputFailNotSubscribed InputFailReason = "not_subscribed"
	// InputFailInjectFailed: tmux rejected the send-keys.
	InputFailInjectFailed InputFailReason = "inject_failed"
	// InputFailTooLarge: the text exceeds the server's input size limit.
	InputFailTooLarge InputFailReason = "too_large"
	// InputFailInternal: an unexpected server-side failure while injecting.
	InputFailInternal InputFailReason = "internal"
)
