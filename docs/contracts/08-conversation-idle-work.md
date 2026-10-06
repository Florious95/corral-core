# Conversation idle work

The native session's `isStreaming` projection (`ConversationState.running`) is
necessary for tool animation. `ToolCall.shouldAnimate` additionally admits only
Composing/Running; Pending and finished phases are static even during a later
live turn. Both the row caller and public card entry enforce this predicate.

A historical aborted toolCall with no toolResult stays **Pending, unfinished**.
This fix changes render activity, not native truth: no invented success, global
settle, history mutation or lost original tool content. Idle Running also stops
its 100ms elapsed-time coroutine. `Breathing(false)` exits before constructing
`rememberInfiniteTransition`; true active work retains its original feedback.

History completion and its single 45s watchdog are mounted only during an
actual restore. Completion still requires the selected session ID, exact target
stream, replay head sequence and Live phase; ordinary lastSeq changes have no
inactive completion job to recreate. The input barrier is not weakened.

The independent audit reproduced the idle-Pending animation mechanism in both
ffc23e7 and 8eb, but did **not** establish that it explains the user's temperature
feedback. The lastSeq effect had no idle feedback loop; its conditional mounting
removes event-related overhead, not a proven self-exciting loop. No CPU saving
percentage, thermal reduction or device performance PASS follows from source
or JVM tests. Device frame/CPU measurements must bind APK/source/server identity
and compare the same idle-Pending/empty/live workloads; overloaded or failed
apparatus remains UNJUDGEABLE.
