# REWORK-001 corrected coordinates

Frozen owning inputs remain unchanged and were not rewritten:

- base `4605951e427f9ba6627375498dcb3c757c05bf36`
- status-core `22bc9e6955d78c072e9715ca9e8ef3c3b7a9325a` / filtered core `9c6dbd178c94b30dedbf54fdf6860308872d5706` / filtered serve `bd34e1760f5d0b25006fbe091d8c11d3fdf1df1d`
- session-ui `123848a9263db00f0c5b0396c9ecbe2a20004938` / filtered `0f9261c43b260b04ffbb5e6c4eeb785a399dea3f`
- provider-icons `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9` / filtered `96a075f766dd972edbee0d661461c82d4bf2fef3`

Correct frozen ranges are exactly:

```text
status-core 3 → session-ui 24 → provider-icons 9
```

`logs/range-diff.log` contains 36 one-to-one (`=`) mappings: status 3, session 24, provider 9. There were zero cherry-pick conflicts and no manual composition resolution. The original receipt's “session-ui 25” was a counting error only; no commit changed to correct it.

Final tested code candidate before receipt-only commit: `ac84f22ef15e62eb74208592ce5fa258020c862e`.

- tree `a338cec1b881d2fb6204434debec674d71de4a07`
- app tree `f1ec0edd5a55d96c857b8f070efd964b4b5af694`
- server tree `54d28cf738b6b0dd9650b162f2d926b44980ec6b`

The unchanged session seven-test class and harness have no delta from frozen session head `123848a9`; `logs/exact-tree-attribution.log` records the empty path result.
