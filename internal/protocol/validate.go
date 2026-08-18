package protocol

import "fmt"

// FrameType methods map each payload Go type to its wire discriminator, and
// Validate methods enforce the contract invariants. Together they let the
// codec (json.go / binary.go) route and check frames in one place.

func (Auth) FrameType() FrameType            { return TypeAuth }
func (AuthAck) FrameType() FrameType         { return TypeAuthAck }
func (List) FrameType() FrameType            { return TypeList }
func (Listing) FrameType() FrameType         { return TypeListing }
func (ListDelta) FrameType() FrameType       { return TypeListDelta }
func (Subscribe) FrameType() FrameType       { return TypeSubscribe }
func (Unsubscribe) FrameType() FrameType     { return TypeUnsubscribe }
func (Input) FrameType() FrameType           { return TypeInput }
func (InputAck) FrameType() FrameType        { return TypeInputAck }
func (Scrollback) FrameType() FrameType      { return TypeScrollback }
func (Resize) FrameType() FrameType          { return TypeResize }
func (ErrorFrame) FrameType() FrameType      { return TypeError }
func (ScrollWheel) FrameType() FrameType     { return TypeScrollWheel }
func (PaneModeChanged) FrameType() FrameType { return TypePaneModeChanged }
func (AttachPreview) FrameType() FrameType   { return TypeAttachPreview }

// Validate reports whether the auth frame is well-formed: a non-empty token.
func (a Auth) Validate() error {
	if a.Token == "" {
		return fmt.Errorf("%w: auth token must be non-empty", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the ack is unambiguous: a rejection must carry a
// reason and an acceptance must not. Reason means failure only — one field,
// one meaning.
func (a AuthAck) Validate() error {
	if !a.OK && a.Reason == "" {
		return fmt.Errorf("%w: rejected auth_ack must carry a reason", ErrInvalidField)
	}
	if a.OK && a.Reason != "" {
		return fmt.Errorf("%w: accepted auth_ack must not carry a reason", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the request is well-formed: ReqID >= 1.
func (l List) Validate() error {
	if l.ReqID == 0 {
		return fmt.Errorf("%w: list req_id must be >= 1", ErrInvalidField)
	}
	return nil
}

// Validate checks a workspace: non-empty cwd, a non-negative count, and every
// member session valid.
func (w Workspace) Validate() error {
	if w.Cwd == "" {
		return fmt.Errorf("%w: workspace cwd must be non-empty", ErrInvalidField)
	}
	if w.SessionCount < 0 {
		return fmt.Errorf("%w: workspace session_count must be >= 0", ErrInvalidField)
	}
	for _, s := range w.Sessions {
		if err := s.Validate(); err != nil {
			return err
		}
	}
	return nil
}

// Validate checks a session entry: a non-empty ref and cwd, and nonzero
// dimensions.
func (s Session) Validate() error {
	if s.Ref == "" {
		return fmt.Errorf("%w: session ref must be non-empty", ErrInvalidField)
	}
	if s.Cwd == "" {
		return fmt.Errorf("%w: session cwd must be non-empty", ErrInvalidField)
	}
	if s.Rows == 0 || s.Cols == 0 {
		return fmt.Errorf("%w: session rows/cols must be >= 1", ErrInvalidField)
	}
	return nil
}

// Validate checks the full listing: valid request correlation and sequence,
// and every workspace (and thus every session) valid.
func (l Listing) Validate() error {
	if l.ReqID == 0 {
		return fmt.Errorf("%w: listing req_id must be >= 1", ErrInvalidField)
	}
	if l.Seq == 0 {
		return fmt.Errorf("%w: listing seq must be >= 1", ErrInvalidField)
	}
	for _, w := range l.Workspaces {
		if err := w.Validate(); err != nil {
			return err
		}
	}
	return nil
}

// Validate checks a delta: valid sequence, and every added/changed session,
// removed ref, and changed workspace valid.
func (d ListDelta) Validate() error {
	if d.Seq == 0 {
		return fmt.Errorf("%w: list_delta seq must be >= 1", ErrInvalidField)
	}
	for _, s := range d.AddedSessions {
		if err := s.Validate(); err != nil {
			return err
		}
	}
	for _, s := range d.ChangedSessions {
		if err := s.Validate(); err != nil {
			return err
		}
	}
	for _, r := range d.RemovedRefs {
		if r == "" {
			return fmt.Errorf("%w: removed ref must be non-empty", ErrInvalidField)
		}
	}
	for _, w := range d.ChangedWorkspaces {
		if err := w.Validate(); err != nil {
			return err
		}
	}
	return nil
}

// Validate reports whether the subscription is well-formed: a non-empty ref
// and nonzero client dimensions.
func (s Subscribe) Validate() error {
	if s.Ref == "" {
		return fmt.Errorf("%w: subscribe ref must be non-empty", ErrInvalidField)
	}
	if s.Rows == 0 || s.Cols == 0 {
		return fmt.Errorf("%w: subscribe rows/cols must be >= 1", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the unsubscription is well-formed: a non-empty ref.
func (u Unsubscribe) Validate() error {
	if u.Ref == "" {
		return fmt.Errorf("%w: unsubscribe ref must be non-empty", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the input request is well-formed: a ReqID >= 1, a
// non-empty ref, and at most one of (Text/AttachmentPath) / Keys (a frame
// carrying both a key press and text or an attachment is a protocol error;
// neither present means a bare Enter, any combination of Text/AttachmentPath
// alone is legal). Every key must be in the closed Key set.
func (i Input) Validate() error {
	if i.ReqID == 0 {
		return fmt.Errorf("%w: input req_id must be >= 1", ErrInvalidField)
	}
	if i.Ref == "" {
		return fmt.Errorf("%w: input ref must be non-empty", ErrInvalidField)
	}
	if (i.Text != "" || i.AttachmentPath != "") && len(i.Keys) > 0 {
		return fmt.Errorf("%w: input carries both text/attachment_path and keys; at most one is allowed", ErrInvalidField)
	}
	for _, k := range i.Keys {
		if !k.IsValid() {
			return fmt.Errorf("%w: unknown input key %q", ErrInvalidField, k)
		}
	}
	return nil
}

// Validate reports whether the attach_preview frame is well-formed: a
// non-empty ref and a non-empty path.
func (p AttachPreview) Validate() error {
	if p.Ref == "" {
		return fmt.Errorf("%w: attach_preview ref must be non-empty", ErrInvalidField)
	}
	if p.Path == "" {
		return fmt.Errorf("%w: attach_preview path must be non-empty", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the ack is unambiguous: a rejection must carry a
// known reason and an acceptance must not.
func (a InputAck) Validate() error {
	if a.ReqID == 0 {
		return fmt.Errorf("%w: input_ack req_id must be >= 1", ErrInvalidField)
	}
	if !a.OK {
		if a.Reason == "" {
			return fmt.Errorf("%w: failed input_ack must carry a reason", ErrInvalidField)
		}
		switch a.Reason {
		case InputFailSessionNotFound, InputFailNotSubscribed, InputFailInjectFailed, InputFailTooLarge, InputFailInternal:
		default:
			return fmt.Errorf("%w: unknown input fail reason %q", ErrInvalidField, a.Reason)
		}
		return nil
	}
	if a.Reason != "" {
		return fmt.Errorf("%w: accepted input_ack must not carry a reason", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the scrollback request is well-formed: a ReqID >= 1,
// a non-empty ref, and a count >= 1. FromLine is any int32; the server clamps
// the requested range to what tmux has.
func (s Scrollback) Validate() error {
	if s.ReqID == 0 {
		return fmt.Errorf("%w: scrollback req_id must be >= 1", ErrInvalidField)
	}
	if s.Ref == "" {
		return fmt.Errorf("%w: scrollback ref must be non-empty", ErrInvalidField)
	}
	if s.Count == 0 {
		return fmt.Errorf("%w: scrollback count must be >= 1", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the resize request is well-formed: a non-empty ref
// and nonzero client dimensions.
func (r Resize) Validate() error {
	if r.Ref == "" {
		return fmt.Errorf("%w: resize ref must be non-empty", ErrInvalidField)
	}
	if r.Rows == 0 || r.Cols == 0 {
		return fmt.Errorf("%w: resize rows/cols must be >= 1", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the error frame is well-formed: a known error code.
// Reason is optional.
func (e ErrorFrame) Validate() error {
	switch e.Code {
	case ErrCodeUnauthorized, ErrCodeBadFrame, ErrCodeUnsupportedVersion,
		ErrCodeUnsupportedType, ErrCodeSessionNotFound, ErrCodeInternal:
		return nil
	default:
		return fmt.Errorf("%w: unknown error code %q", ErrInvalidField, e.Code)
	}
}

// Validate reports whether an upload response is well-formed: a non-empty
// absolute path.
func (u UploadResp) Validate() error {
	if u.Path == "" {
		return fmt.Errorf("%w: upload path must be non-empty", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the scroll_wheel frame is well-formed: a non-empty
// ref and a non-zero delta (zero delta has no direction and is a caller error).
func (s ScrollWheel) Validate() error {
	if s.Ref == "" {
		return fmt.Errorf("%w: scroll_wheel ref must be non-empty", ErrInvalidField)
	}
	if s.Delta == 0 {
		return fmt.Errorf("%w: scroll_wheel delta must be non-zero", ErrInvalidField)
	}
	return nil
}

// Validate reports whether the pane_mode_changed frame is well-formed: a
// non-empty ref.
func (p PaneModeChanged) Validate() error {
	if p.Ref == "" {
		return fmt.Errorf("%w: pane_mode_changed ref must be non-empty", ErrInvalidField)
	}
	return nil
}
