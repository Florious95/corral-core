# Status-core implementation evidence

The product is based directly on `4605951e427f9ba6627375498dcb3c757c05bf36`. The accepted source is `ff316dc0afe8ab280e61d30934e7624579be6224` / tree `5217a41aa914ddcb72c27f39f1b4af9ead68b1b6`; all 14 synchronized blobs are byte-identical. Runtime coordinates were verified as macOS arm64 binary size 781728 / SHA-256 `4ae8a87380025f810dad221f96b64764e4a312ae6de127d83cc641884d60a5d9` and official Pi extension size 3406 / SHA-256 `fce8c7fac3b4274d3c8e38261022b6ae31ad021247b7ff39bbf0f6dd04088850`.

The typed Go adapter executes once per discovered socket, rejects schema/error/process failures, and joins only `socket + session + window_index + pane_id`. Listing, delta, and level-2 now carry provider/activity/nullable session name/health with `status == activity`; the Kotlin decoder/model uses fail-closed effective activity and never title inference. Legacy Go/Rust detector authorities were deleted. Fixtures, Provider icon/UI, session-ui, device/emulator, production daemon, and accepted external assets were untouched.

Non-cached Rust (35), Node (1), Go changed packages, and APP JVM (605) passed; the provenance/static gate passed. Strict T3 remains exit 1 on exactly the repository baseline inventory after all PR-local findings were removed: base and head both have the same 18 T3-1, 1 T3-2, and 34 T3-3 findings; head removes the obsolete provider dependency and has no new PR-local T3-4 finding. This is recorded as baseline red, not pass, and was not retried to green.

Known accepted upstream risks are recorded without product correction: future-timestamp tolerance and challenge response `socket_path`. Rollback is one PR revert and does not alter external rollout assets.
