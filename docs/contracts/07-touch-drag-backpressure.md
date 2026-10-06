# Android TUI pointer backpressure

## Scope and compiled boundary

`ServiceWire` decorates the **existing persistent socket** with `TouchInputTransport`.
The pinned `core-conn:20261002.background-liveness1` Maven artifact is unchanged;
archived `.team/staging/corral-core` sources are not part of this build. Pairing
probes and independent managers do not share the persistent socket's input gate.
`SessionRoute.createSessionViewModel` explicitly injects that manager's dispatcher;
standalone view-model/manager fixtures must inject and decorate it as well.

`SessionViewModel.onTermMouse` is the typed SGR source. Text/IME, pasted bytes,
physical keys, shortcut keys, attachment previews and wheel displacement are
barriers, never interpreted as mouse reports by inspecting their contents.
Existing viewport/body drag and local selection behavior stays unchanged. The
pinned terminal Core maps DEC 1003 to its 1002 drag policy; this change does not
turn body drags into remote all-motion reports.

## Ordering

1. A mouse DOWN resets the gesture's cell/modifier dedup state.
2. A motion occupies one replaceable **send-action** slot. Replacement occurs
   before calling Core, hence discarded positions allocate no request IDs,
   pending-input entries, timeout entries or WebSocket writes.
3. Motion can be written only when every input already written on this physical
   socket has received its exact `input_ack`. Unknown, duplicate and wrong-link
   ACKs do not release it. Local timeout is never an ACK. There is no periodic
   flush, frame-rate limiter or 250ms automatic-open fallback.
4. A non-motion barrier seals the current endpoint: later motion cannot replace
   a coordinate from before that barrier. When ACK permits, write the sealed
   coordinate, then the barrier in WebSocket FIFO order. A barrier may follow
   that write immediately; the **next motion** waits for both ACKs. This keeps
   release and keyboard events moving without opening a second motion flight.
5. UP/CANCEL's physical final cell is flushed before release, even if no MOVE
   event reported that cell. Repeated gestures at identical coordinates remain
   independent. Modifier changes at the same cell are not deduplicated.
6. Without a pending pointer endpoint, ordinary keyboard/IME delivery remains
   immediate; this is not a blanket one-RTT-per-character throttle. Its actual
   input IDs are still tracked so a later pointer motion cannot overtake it.

A typed native `switch_mode` request is refused while that ref still has a held
pointer or unsent input: it must not replace TUI before an accepted old endpoint/UP
is written. The existing command failure UI exposes this refusal; retry after
release/drain. It does not inject a probe into an unknown TUI.

A keyboard submission's UI result is bound to its captured request ID; a mouse
or another pane's ACK cannot mark a queued Enter as sent. The exact raw ACK also
confirms that submission if it arrived before Core registered its pending input;
a later stale SDK timeout cannot turn that real success into a false failure.

## Lifetime, bounds and failures

Each socket has a distinct Link. Its closure clears pending actions and gesture
state. Connection-state departure from READY clears it *before* a delayed socket
close; chosen list/subscribe selects the new Link. Every queued action is also
bound to its original Link, including non-input wheel/preview/unsubscribe
barriers. Reconnect does not replay an old motion or UP against a new pane/process.
Raw writes are registered before calling the socket, including the case where
an ACK arrives before the write call returns. ACK-triggered drains are posted to
the main thread, preserving the existing Core caller boundary.

Only the current tail motion is mutable. Lossless barriers and their sealed
endpoints occupy a FIFO bounded to 64 actions. Already-written input correlation
is bounded to 4096 IDs. Exhaustion closes the link with a visible failure instead
of silently dropping accepted releases or growing memory without bound. A
negative ACK, failed write, or Core's existing input timeout also fails the link;
it never releases queued motion as if delivery succeeded. Admission closes
immediately, but teardown from a Core timeout callback is posted until its
pending-input iteration finishes (no reentrant map mutation). Screen exit keeps
an already accepted endpoint/UP before the unsubscribe barrier. Abandoning a
still-held pointer without any release is an explicit link failure. The existing
reconnect banner/error and diagnostic record expose these failures. No new timer,
worker or idle polling is added.

Every terminal `subscribe` declares `client_type: "mobile"`, including
resubscriptions. Existing dimensions and retain-pane-size fields are preserved.
This implements the mobile presence declaration, not the separate Core defect
where a desktop's first subscribe can resize before receiving presence.

## Evidence boundary

Controller/real-Core socket tests establish ordering and boundedness. A fake
100000-row TUI on Android establishes real touch/transport wiring, **not** a
100K-token Pi session. Long-context Pi and device performance require separate
raw recordings, processed-input timestamps, source/APK/server identity and a
passing environment gate. An overloaded host or failed device apparatus is
UNJUDGEABLE; neither tests nor synthetic TUI results substitute for that gate.
