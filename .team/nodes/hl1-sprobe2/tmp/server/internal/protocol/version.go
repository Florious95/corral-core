package protocol

// Protocol versioning. Version is 1 for the initial contract; client and
// server MUST negotiate it on every connection (docs/protocol.md §2). Bumping
// it is a breaking change; adding new frame types or new optional JSON fields
// is additive and does not require a bump.
const (
	// Version is the current wire protocol version, carried in the "v" field
	// of every JSON control frame and in the version byte of every binary
	// stream frame.
	Version uint16 = 1

	// BinaryVersion is the version byte used inside binary frames. It equals
	// Version today; it is a separate constant so a binary-framing-only fix
	// never needs to bump the whole protocol.
	BinaryVersion byte = 1

	// BinaryMagic is the two-byte prefix of every binary stream frame ('R'
	// 'A'). A decoder can reject a mis-framed message immediately.
	BinaryMagic = "RA"
)

// DefaultBinarySessionRefLen is the wire size of the length field that
// prefixes a binary frame's session ref: a single byte, so a ref is capped at
// 255 bytes.
const DefaultBinarySessionRefLen = 1
