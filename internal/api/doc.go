// Package api implements the service-side WebSocket API and the image upload
// endpoint, wiring together discovery and bridge.
//
// Landing zone for the ws-api task. On reconnect it must replay the current
// snapshot so no state is lost (requirement 003). Only the package contract
// is declared here; no implementation has landed yet.
package api
