package api

// l2detect_codex.go — Codex title marks (requirement 068).
// This family does not set a pane title; Match stays unclaimed so
// classifyForProvider records unknown (known family, no sample).

func init() {
	registerL2Detector("codex", codexDetector{})
}

type codexDetector struct{}

func (codexDetector) Match(string) (status string, claimed bool) {
	return "", false
}
