// Package pairing implements token-based device pairing and QR-code
// onboarding for the Android app.
//
// Landing zone for the pairing-security task: a pairing token, optionally
// carrying a Tailscale auth key, encoded in a scannable QR (route (a) of
// requirement 011). Only the package contract is declared here; no
// implementation has landed yet.
package pairing
