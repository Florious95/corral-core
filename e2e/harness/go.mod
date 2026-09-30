module github.com/agentmirror/agentmirror/e2e/harness

go 1.26.5

require (
	github.com/agentmirror/agentmirror v0.0.0
	github.com/coder/websocket v1.8.14
)

replace github.com/agentmirror/agentmirror => ../../server
