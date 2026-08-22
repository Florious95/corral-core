package api

// l2detect_cursor.go — Cursor title marks (requirement 068).
// This family does not set a pane title; Match stays unclaimed.

func init() {
	registerL2Detector("cursor", cursorDetector{})
}

type cursorDetector struct{}

func (cursorDetector) Match(string) (status string, claimed bool) {
	return "", false
}
