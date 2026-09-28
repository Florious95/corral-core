// Package tsnetd embeds Tailscale networking (tsnet) so the daemon's
// WebSocket service is reachable over the tailnet as well as the LAN.
//
// Two listeners feed the same HTTP/WS handler (wired by the ws-api task): a
// plain net.Listener on the LAN and, when a TS authkey is configured, a tsnet
// listener on the tailnet. With no authkey the package degrades to a LAN-only
// group without ever contacting the Tailscale control plane.
//
// Construction (New) never starts the embedded node: the tsnet.Server is
// built with Hostname/AuthKey/Dir wired through and its state directory is
// created, but the node only comes up when Up or ListenTailnet is called. This
// keeps the no-authkey red line (zero control-plane contact) trivially
// enforceable and keeps unit tests network-free.
//
// Cross-layer dependencies (T3-4): this package imports only the standard
// library and the external tailscale.com/tsnet library — no internal package,
// so the @consumes set is empty by construction.
// @consumes none
package tsnetd
