package api

// attach_preview.go implements the server-side bookkeeping for requirement
// 057's two-step attach flow: AttachPreview pastes an image ahead of send,
// and the later Input.AttachmentPath looks up how long ago that happened so
// it only waits out whatever remains of bridge.PasteSettleDelay instead of
// the full delay every time (often zero, once the user's own typing time
// has covered it).
//
// State lives on *Server, not on *bridge.Pane, because sessionCatalog.rebuild
// discards and reconstructs every *bridge.Pane on each discovery scan
// (every listInterval, as short as 500ms in tests) — a field on Pane would
// not reliably survive across the settle window. *Server is long-lived for
// the whole process, so it is the only safe home for this timestamp.

import (
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

// attachPreviewEntry is one pane's most recent AttachPreview: which path was
// pasted and when. Only the most recent call per ref is kept — with
// requirement 057 clause 4's accumulation semantics a pane may carry more
// than one pasted image, but the settle-delay math only cares about the
// newest paste (the bottleneck: it is the one still decoding).
type attachPreviewEntry struct {
	path string
	at   time.Time
}

// recordAttachPreview stores ref's most recent preview, overwriting any
// earlier one — called right after a successful bridge.Pane.PastePreview.
// @contract
// @pre ref 非空（调用方已过 Validate）
// @post attachPreviews[ref] 更新为 {path, 当前时刻}，覆盖旧值
// @err none
// @inv 只保留每个 ref 最近一次预贴记录
func (s *Server) recordAttachPreview(ref, path string) {
	s.attachPreviewsMu.Lock()
	defer s.attachPreviewsMu.Unlock()
	s.attachPreviews[ref] = attachPreviewEntry{path: path, at: time.Now()}
}

// consumeAttachPreview looks up ref's most recent preview and, if its path
// matches path exactly, removes it (one-shot: a send consumes the preview it
// confirms) and returns the elapsed time since it was pasted. A mismatch —
// no recorded preview, or a different path (the image was swapped, or this
// AttachmentPath was never preceded by AttachPreview at all, e.g. an older
// client) — returns ok=false and consumes nothing, so the caller falls back
// to bridge.Pane.InjectWithAttachment's full paste+wait+Enter path.
// @contract
// @pre path 非空（调用方在 AttachmentPath=="" 时不应调用本函数）
// @post 命中且路径一致 ⇒ 删除该记录，返回 (elapsed, true)；否则不修改状态，返回 (0, false)
// @err none
// @inv 路径必须逐字节相同才算命中——换了图必须重新走完整流程，不允许复用旧时间戳
func (s *Server) consumeAttachPreview(ref, path string) (elapsed time.Duration, ok bool) {
	s.attachPreviewsMu.Lock()
	defer s.attachPreviewsMu.Unlock()
	entry, found := s.attachPreviews[ref]
	if !found || entry.path != path {
		return 0, false
	}
	delete(s.attachPreviews, ref)
	return time.Since(entry.at), true
}

// remainingSettleDelay converts consumeAttachPreview's elapsed time into how
// much of bridge.PasteSettleDelay is still owed. Zero once elapsed already
// covers the full window (the common case: the user typed a caption) —
// requirement 057 clause 5's "normal path is zero wait", not "wait a little".
func remainingSettleDelay(elapsed time.Duration) time.Duration {
	remaining := bridge.PasteSettleDelay - elapsed
	if remaining < 0 {
		return 0
	}
	return remaining
}
