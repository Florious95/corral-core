// Package pairing implements token-based device pairing and QR-code
// onboarding for the Android app.
//
// It is the pairing-security task's landing zone (requirement 011 route (a)):
//
//   - token: a pairing token, auto-generated on empty config (crypto/rand,
//     ~128 bits, base32), persisted under the user config dir with 0600
//     permission and reused across restarts; an explicitly configured token
//     always wins.
//   - qr: the token plus the service ws URL, carried in a single-line JSON
//     payload that the Android app scans; optional ts_authkey starts the App's
//     embedded tsnet node. Rendered as ANSI half blocks (▀▄█) with no image
//     pipeline.
//   - probe: deterministic discovery of the host's LAN/tailnet addresses to
//     build the ws URL; degraded to a loopback fallback with an explicit
//     warning instead of a silently unreachable QR.
//
// Token red line (docs/protocol.md §9): the QR payload and the printed
// onboarding guide are the token's only legal exits. The package never logs
// the token, and no error string embeds it.
package pairing
