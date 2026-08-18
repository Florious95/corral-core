package api

// l2detect_copilot.go — Copilot title marks (requirement 068).

func init() {
	registerL2Detector("copilot", copilotDetector{})
}

type copilotDetector struct{}

func (copilotDetector) Match(string) (status string, claimed bool) {
	return "", false
}
