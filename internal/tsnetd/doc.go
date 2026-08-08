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
// created, but the node only comes up when ListenTailnet is called. This
// keeps the no-authkey red line (zero control-plane contact) trivially
// enforceable and keeps unit tests network-free.
package tsnetd
