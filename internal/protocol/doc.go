// Package protocol defines the wire contract between the Android app and the
// service: JSON control frames plus binary terminal stream frames over
// WebSocket.
//
// Landing zone for the protocol-spec task: workspace/session listing,
// subscription (snapshot + incremental stream + scrollback paging), input
// injection, resize, agent state fields, image upload, and pairing frames.
// The state fields must stay decoupled from the mirror channel so that
// "unknown" never blocks mirroring (requirement 008). Only the package
// contract is declared here; no types have landed yet.
package protocol
