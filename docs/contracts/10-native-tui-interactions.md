# Native TUI GUI adapters / portable client contract

Base: `07d4b744b41e26f0a225348a80c26876285afdfb`. This package does not replace native agents, change production 9900, merge main, or claim hosted-model/task completion from a wire acknowledgement.

## Pure-black GUI and usage

All nativeGUI palette slots use opaque `#000000`, including glass, panels, code, buttons and bubbles. Terminal themes are unchanged. Body/button text is white, secondary/code text is `#B6BDC8`; provider/status colors are foreground only. Pressing a destructive button changes its outline, not its black background (C1: Rose Pine Dawn danger retains AA). Every new native interaction/form uses the same black surfaces and sheet controls.

Grok cumulative usage is read with its verified native binary's `grok usage SESSION_ID` in the pane's cwd: bounded 5s/4MiB/4 concurrent reads, birth and returned-ID checks, no raw stdout/error/path/auth logging. `session`, `turns`, `modelUsage`, reasoning/model-call counters and exact `costUsdTicks / 10^10` are preserved. Recent turns are bounded to 50 while session totals remain native totals. Missing usage/cost is not USD0 or fabricated consumption; the original slash collector still supplies actual context. Cached input is not added twice, and unproved cache denominators do not produce a hit rate.

## Same authenticated conversation_v1 socket

All commands use the existing outer `{ref,id,command}`. `id` correlates an ordinary GUI response. It is **not** a native callback ID or node ID. No phone command accepts a host path/cwd or arbitrary native session identifier for these mutations.

| GUI command | Fields | Confirmed bridge action / result |
|---|---|---|
| `fork_points` | none | Pi `get_fork_messages`; returns `{session_id,points:[{id,text}]}`. `id` is a 32-hex bridge token bound to native session and complete catalog revision. |
| `fork_session` | `pointId`, optional `force:boolean` | Pi `fork {entryId}`; selected user is excluded, its text returns in `draft`; native `get_state` must confirm a different SID. |
| `clone_session` | optional `force:boolean` | Pi `clone`, or Grok `_x.ai/session/fork` with host-bound sourceSessionId/sourceCwd/newCwd, then explicit native load. |
| `rewind_points` | none | Grok `_x.ai/rewind/points {session_id}`; indexes are native 0-based prompt_index, never GUI ordinals. |
| `rewind_session` | `pointId`, optional `force:boolean` | Grok `_x.ai/rewind/execute {session_id,targetPromptIndex,mode:"conversation_only"}`; `success:false` (including error:null) is a visible failure, never truncation success. Only success:true proceeds to authoritative load/replay. |
| `rename_session` | `name`, 1–160 characters | Pi `set_session_name` + current get_state confirmation; Grok `_x.ai/session/rename {sessionId,title}` + explicit success:true + exact current-session/list title confirmation. Receipt includes `{session_id,sessionName}` and session-bound header event. Grok raw /rename is NOT sent as a model prompt. |
| `new_session` | optional explicit stop consent | Native new, current new SID/state confirmation and the same atomic replay commit; `sessionName:""` clears old title. |
| `interaction_reply` | `requestId`, exactly one cancelled:true / confirmed:boolean / value:string; optional confirmPermanent:true for permanent options | Opaque bridge callback mapping described below. No arbitrary native ID forwarding. |

Mutation receipt: `{session_id,sessionName,stream,head_seq,history_truncated,content_clipped,draft?}`. Busy returns a finite failure with busy:true; force is a separate explicit user decision and stops queued work. A native veto keeps old session/history/draft. Pi cloning an unsaved session (for example, immediately after a before-first-user fork) returns its native unsaved/no-node rejection, not success. These two known precondition rejections preserve the bridge only after get_state confirms the same SID, with a visible reason to send a message first; no automatic prompt, synthetic clone or persistence/file edit occurs. Unknown outcome, changed SID or lost confirmation still closes only the bridge. Grok clone partial success reports the already-created child ID if loading fails; it is not silently forked a second time.

### Atomic mutation timeline (Android and future iOS)

```text
client selects a native catalog token / confirms clone or explicit busy stop
 -> authenticated host API obtains same-ref native operator + old stream
 -> birth/session/revision verification + shared input switch barrier
 -> native mutation (human extension callbacks remain answerable through that barrier)
 -> get_state/native session load + bounded branch/replay capture
 -> atomic new stream: session_reset{sessionId,sessionName,replace:true}
                        original history -> history_window -> current get_state
 -> old replay subscription closes; host sends command receipt on authenticated WS
    (receipt is not sent on the retired replay pipe, and cannot be lost with that pipe)
 -> client re-subscribes to the new stream
 -> composer remains gated until receipt stream == state.stream,
    lastSeq >= head_seq, target SID == state.sessionId, and phase == Live
 -> returned text is appended to any existing user draft; attachments survive;
    nothing is automatically sent to a model
```

Pi persistent histories retain pre-compaction original content on the official active leaf, not abandoned branches. A new Pi session can report a not-yet-written file path while model/thinking entries already exist in memory: native get_entries+leafId provides that authoritative branch; no empty path is opened and no newest-file guessing is used.

### Native capability boundaries

Pi 1.0.0 RPC has no rewind/navigate_tree mutation. Its menu explains this and offers before-user fork without pretending to roll back the old session. Grok full-clone is confirmed; message-node fork is not confirmed and is visibly unavailable. Controlled Grok rewind probes returned success:false even for valid nodes; GUI exposes the exact provider outcome and never does external JSONL surgery. Grok custom compact instructions remain visibly unavailable because ACK did not prove consumption. Rewind stays visibly disabled pending a real success closure; its daemon adapter preserves native success/false outcomes for independent testing, not a GUI success claim. Grok typed rename is confirmed natively, including same-session manual title pushes; raw /rename as ACP prompt is specifically prohibited.

## Flow-inline dialogs and permissions

Native Pi confirm/select/input/editor requests, including message:string, are projected into inline cards. A native dialog message is not confused with the object-valued message of an agent turn. Standard empty input is a submitted empty string, not cancellation; editor newlines are retained; select replies use original option strings, not indexes.

ACP request_permission maps original JSON-RPC ID (including **numeric 0**), session, toolCallId and the advertised optionId/kind snapshot into the same GUI surface. Reject_once/reject_always are selected original options, not generic approved:false or cancelled. Every allow_always/reject_always requires a second dialog displaying the **original native scope label**. First option is never automatically chosen.

- At most 16 pending human decisions per worker/native connection.
- GUI `requestId` is a random 32-hex token, distinct from outer command id and native callback id.
- Token is session+bridge stream bound; original ACP sessionId must equal the confirmed worker ID, and load/initialization-time permission requests cancel rather than acquire the retired session's owner. Wrong-session, stale, duplicate and unadvertised decisions fail visibly without native writes.
- Host policy deadline is 2 minutes, reduced by Pi's optional native timeout in milliseconds. Mutations/resume that may ask an extension question allow that human window plus bounded native cancellation/replay grace (145s host / 160s client); a 25s RPC timer must not strand a still-valid human request. Unanswered expiry or last subscriber leaving produces native cancelled and an `interaction_resolved` event. Client expiry uses the existing host-clock skew, not the phone wall clock. Waiting humans do not block stdio or poll on a timer.
- Before-fork replies bypass their **own** mutation's user-input gate but still need the original token/session/stream; ordinary prompts remain blocked.
- Submitted decision means only “decision submitted”, never “tool executed”.
- Native status/widget/title/editor-text is rendered too. Extension title is separate from trusted session title. Editor text is appended without silently discarding user drafts/attachments. Chrome/item/input payloads remain bounded.

Future iOS implementation should consume these portable events and reproduce the same token, consent, expiry, title and stream/head/Live checks. It must not call native IDs directly or reproduce Android renderer internals.

## Grok task drawer

Native metadata-advertised commands populate the drawer. Live available_commands_update refreshes GUI metadata; reconnect reads it once again (no polling). Model/thinking, usage/context/session-info, history/resume, compact, export, new/clear, fork and rewind/undo route to existing dedicated GUI surfaces instead of blindly invoking a TUI popup.

Dedicated task forms preserve exact documented syntax:

- `/deep-research <query>`: nonempty query; explicit launch/cost consent; command end_turn is not research completion, reports can be Partial.
- `/workflow runs` versus `/workflows`: current-session runs versus saved-definition catalog are distinct.
- `/workflow <name> [--agent-budget 1..1024] [JSON object args]`: no invented names/effort choices, no conflicting form+JSON budget. Budget is logical child agent calls, not token budget.
- `/workflow pause|resume|stop|save <display-name>`: explicit current-session handle; no bare stop guessing or claim of exactly-once external effects.
- `/goal <objective> [--budget positive-token-count]` or status/pause/resume/clear: no invented default budget. Mutation/clear requires consent.
- Other metadata entries provide an explicitly labeled native argument form; unknown syntax is not guessed.

All starts/mutations are confirmed. An ACK only says command received; native report/status evidence determines completion. No host background task IDs, budgets or successful reports are invented.

## Issue #65: title ownership

Original red protocol facts: rename was rejected, new reset had no title metadata, resume result was title-less while Pi replay supplied the target's real native name. No old-title write-back was proved; therefore the fix is identity/sequencing, not arbitrary rename-on-resume.

Current title sequencing:

1. Three-dot Rename opens an input dialog and performs native rename, not a local pane label change.
2. New session receipt and atomic reset explicitly clear the old title. An established native stream without a name displays “新会话”, not the old pane-name fallback.
3. Selecting resume shows target catalog name immediately during restore; receipt/current get_state/reset carry target SID+name. No old title is sent to native during resume.
4. Client ignores asynchronous get_state/session_info_changed for another SID; a new stream reset does not inherit the retired stream's title.
5. Successful rename receipt updates header immediately, only when receipt SID matches current SID.

Evidence and frozen source/APK/server hashes belong in the delivery manifest and PR, not in this contract as guessed completion claims. Uncached unit/race checks and real native fixtures are necessary but do not replace independent Android screenshots or the user's final device acceptance.
