package protocol

import (
	"encoding/json"
	"fmt"
)

// MarshalFrame serializes a control-frame payload into one complete JSON
// message ready to send as a WebSocket text message. It derives the wire
// "type" discriminator from the payload, stamps Version, and validates the
// payload first — an invalid frame never leaves this process. The payload
// must implement Typed (all control frames do; see uploadresp note).
//
// @contract
// @pre payload 实现 Typed 且 payload.Validate() 返回 nil
// @post 返回含 "v"=Version、type=payload.FrameType()、payload 为 JSON 编码正文的完整信封
// @err payload.Validate() 的失败原样返回；json.Marshal 失败返回编码错误
// @inv 纯函数，无外部副作用；输出的 "type" 与 payload 的 FrameType() 一致
func MarshalFrame(payload Typed) ([]byte, error) {
	if err := payload.Validate(); err != nil {
		return nil, err
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	env := Envelope{
		V:       Version,
		Type:    payload.FrameType(),
		Payload: json.RawMessage(body),
	}
	return json.Marshal(env)
}

// UnmarshalFrame parses one JSON control message back into its typed payload.
// It enforces the envelope contract (version present and supported, known
// type) and validates the payload before returning. Unknown envelope or
// payload fields are ignored for forward compatibility; a rejected version,
// unknown type, or invalid payload is an error. The concrete payload type is
// derived from the wire "type" and returned as a Typed interface value whose
// Validate has already passed. On failure it returns (nil, err); it never
// returns (nil, nil).
//
// @contract
// @pre data 是一个完整的 WebSocket text message（JSON）
// @post 返回的 Typed 的 FrameType() 等于 wire "type" 且 Validate() 已通过；出错时返回 (nil, err)
// @err ErrBadPayload / ErrMissingVersion / ErrUnsupportedVersion / ErrUnknownType / 各 payload 的 Validate 错误
// @inv 纯函数，无外部副作用；绝不返回 (nil, nil)
func UnmarshalFrame(data []byte) (Typed, error) {
	env, err := decodeEnvelope(data)
	if err != nil {
		return nil, err
	}
	return decodePayload(env)
}

// decodeEnvelope parses and checks the outer envelope only. It is separate so
// Envelope is available to callers that route on Type without decoding the
// payload body.
func decodeEnvelope(data []byte) (Envelope, error) {
	var env Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return Envelope{}, fmt.Errorf("%w: %v", ErrBadPayload, err)
	}
	if env.V == 0 {
		return Envelope{}, fmt.Errorf("%w", ErrMissingVersion)
	}
	if env.V != Version {
		return Envelope{}, fmt.Errorf("%w: got %d want %d", ErrUnsupportedVersion, env.V, Version)
	}
	return env, nil
}

// decodePayload derives the concrete payload type from env.Type and decodes
// the body into it, then validates.
func decodePayload(env Envelope) (Typed, error) {
	if env.Type == "" {
		return nil, fmt.Errorf("%w: empty frame type", ErrInvalidField)
	}
	// UploadResp is deliberately absent: it is an HTTP response, not a frame.
	switch env.Type {
	case TypeAuth:
		return decodeTyped[Auth](env)
	case TypeAuthAck:
		return decodeTyped[AuthAck](env)
	case TypeList:
		return decodeTyped[List](env)
	case TypeListing:
		return decodeTyped[Listing](env)
	case TypeListDelta:
		return decodeTyped[ListDelta](env)
	case TypeSubscribe:
		return decodeTyped[Subscribe](env)
	case TypeUnsubscribe:
		return decodeTyped[Unsubscribe](env)
	case TypeInput:
		return decodeTyped[Input](env)
	case TypeInputAck:
		return decodeTyped[InputAck](env)
	case TypeScrollback:
		return decodeTyped[Scrollback](env)
	case TypeResize:
		return decodeTyped[Resize](env)
	case TypeError:
		return decodeTyped[ErrorFrame](env)
	case TypeScrollWheel:
		return decodeTyped[ScrollWheel](env)
	case TypeAttachPreview:
		return decodeTyped[AttachPreview](env)
	case TypeLevel2Subscribe:
		return decodeTyped[Level2Subscribe](env)
	case TypeLevel2Unsubscribe:
		return decodeTyped[Level2Unsubscribe](env)
	case TypeLevel2Frame:
		return decodeTyped[Level2Frame](env)
	case TypeLevel2Heartbeat:
		return decodeTyped[Level2Heartbeat](env)
	case TypeOverlaySubscribe:
		return decodeTyped[OverlaySubscribe](env)
	case TypeOverlayUnsubscribe:
		return decodeTyped[OverlayUnsubscribe](env)
	case TypeOverlayFrame:
		return decodeTyped[OverlayFrame](env)
	case TypeCloseSession:
		return decodeTyped[CloseSession](env)
	case TypeCloseSessionAck:
		return decodeTyped[CloseSessionAck](env)
	case TypeCreateSession:
		return decodeTyped[CreateSession](env)
	case TypeCreateSessionAck:
		return decodeTyped[CreateSessionAck](env)
	// TypePaneModeChanged is S→C only; a client sending it is a protocol error.
	default:
		return nil, fmt.Errorf("%w: %q", ErrUnknownType, env.Type)
	}
}

// decodeTyped decodes the envelope payload into the concrete frame T and
// validates it. Unknown JSON fields inside the payload are dropped silently
// (forward compatibility); missing required fields surface in Validate.
func decodeTyped[T Typed](env Envelope) (Typed, error) {
	var p T
	if len(env.Payload) > 0 {
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			return nil, fmt.Errorf("%w: %v", ErrBadPayload, err)
		}
	}
	if err := p.Validate(); err != nil {
		return nil, err
	}
	return p, nil
}
