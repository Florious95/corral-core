# Pi RPC history browsing and resume (Issue #55)

The pane keeps its official native `pi --mode rpc` command. History selection
uses the **existing Pi PID and stdin**, not a launch/resume command or wrapper:

```json
{"type":"switch_session","sessionPath":"/host/store/selected.jsonl"}
```

Only `success:true` with `data.cancelled:false` is a confirmed switch. Paths stay
inside the host bridge; clients select IDs discovered from session headers.

## Authenticated conversation_v1 controls

List by a discovered ref (preferred), or by an already listed workspace with
exactly one verified native Pi RPC pane:

```json
{"v":1,"type":"conversation_list_sessions","payload":{"req_id":7,"ref":"<pane-ref>"}}
```

`workspace` may replace `ref`. A workspace-only request never scans an arbitrary
client-supplied filesystem path; multiple eligible panes require an explicit ref.

```json
{"v":1,"type":"conversation_sessions","payload":{"req_id":7,"ref":"<pane-ref>","ok":true,"sessions":[{"session_id":"<uuid>","name":"Title","first_message":"First user sentence","created_ms":1791248400000,"modified_ms":1791248405000,"current":false}]}}
```

Failure: `ok:false, reason:<visible error>`. An empty catalog is a successful
empty array, not an unavailable bridge.

```json
{"v":1,"type":"conversation_resume_session","payload":{"ref":"<pane-ref>","session_id":"<uuid>","force":false}}
```

`req_id` is optional for resume. The correlated response is
`conversation_session_resumed`, carrying `ref`, optional `req_id`, `ok`, and
`data:{session_id,stream,head_seq,history_truncated,content_clipped}` on success.
Failure has a reason and may carry `data.busy:true` or `data.cancelled:true`.
Busy tasks/compaction/queued input require explicit `force:true` consent before
`clear_queue` and `abort`. Cancelled native switches keep the old stream intact.

Android reuses the existing correlated `conversation_command` transport:

```json
{"type":"list_sessions"}
{"type":"resume_session","sessionId":"<uuid>","force":false}
```

These are **daemon controls**, never forwarded as Pi command types. Their
`response` data/reason matches the explicit endpoints. Client `sessionPath`,
`path`, launch arguments, and arbitrary `switch_session` commands are not accepted.

## Store and history fidelity

- Native explicit `--session-dir` takes precedence (last option wins). Otherwise
  the reported `get_state.sessionFile` supplies the known store directory.
- A known current file outside an explicit store is included individually; its
  unrelated parent directory is not scanned.
- A non-persistent pane with no reported file uses the documented default agent
  store. A custom process-only environment/settings override cannot be inferred
  from an absent file; use explicit `--session-dir` for that case.
- Default Pi storage is `~/.pi/agent/sessions/--<encoded-absolute-cwd>--/`.
  Every file's header cwd is validated, including default stores. Symlink/FIFO
  entries do not escape the verified file boundary. Duplicate IDs are ambiguous,
  not an instruction to guess a filename or newest file.
- Titles, first user sentence, and activity times are extracted read-only from
  `.jsonl` entries. List metadata is bounded to about 2 MiB; an oversized or
  unreadable catalog fails visibly, never masquerades as a complete partial list.
- Official `get_entries` supplies the current `leafId`. Reading `since:<last
  persisted entry ID>` avoids transferring the whole raw tree over Pi's tty.
  The raw append-only file is walked backwards along parent IDs, not along
  abandoned branches or the compacted `get_messages` model context.
- Original active-branch user/assistant text, thoughts, tool arguments/results,
  visible custom messages, and bash history are projected into normal GUI records.
  Opaque signatures, prices, and endpoints are not display content.

## Bounded replay and client transition

Normal histories fitting the bridge's 8 MiB / 4000-record window and Android's
1500 rendered-item limit replay the complete original current branch, including
pre-compaction history. A tool call and its result count as **one** displayed card.

Oversized histories still switch successfully. The newest contiguous window is
retained; `history_window` explicitly reports `loaded_items`, `older_omitted`, and
`content_clipped`. Text exceeding 200000 UTF-16 units is clipped only for display.
Original files and native model context are preserved. The UI's top-of-history
label says that earlier/full content remains in the host file; it never claims a
partial window is a full replay.

Successful resume atomically changes the daemon-local stream and retains
`session_reset{replace:true,sessionId}` before target records. Existing local
subscriptions end as `lost`; reconnecting clients receive `ready{reset:true}`
and the new replay. Sequence numbers remain monotonic, so old queued records
cannot overwrite the target generation. The Android composer stays gated until
the confirmed target stream, session ID, and replay head have arrived, then
scrolls to the newest row and accepts continuation prompts.

The GUI input gate serializes all bridge clients. Independent host writers or
extensions that start a concurrent turn during history reconstruction are not
silently discarded: the bridge closes visibly and asks the client to reconnect;
the native process/task is not killed. Unknown native identity, incomplete
switch confirmation, or missing branch parents also fail closed.

There is no idle history polling, new network route, private socket, pane worker,
or modification to terminal rendering. Two history controls per WebSocket,
40-second operation budgets, fixed replay/list bounds, and bridge teardown
bound resource use and make failures visible.
