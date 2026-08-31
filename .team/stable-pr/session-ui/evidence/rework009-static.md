# REWORK-009 static repair
- Goal digest `203a1372af12a71bcb2506d18a0ab48695b81fdff16acc971ec9df8c25e25320`.
- Fresh `bash tools/test-session-ui-emulator.sh --static-contract`: rc=0, total_hits=0; planned/discovered=18 exact.
- Fresh Android test compile with `--rerun-tasks --no-build-cache`: rc=0, 35 tasks.
- Six adapters now call production seam symbols (SessionScreen/WorkspaceViewModel, WebSocketTransport, WindowInsetsCompat, SharedPreferencesTermThemeStore/TermPalette, classified contract); TermSurface recorder walks actual View hierarchy; smoke callbacks increment observable recorder.
- Functional/device runtime remains deferred: current load unsafe, no emulator started/touched. B-F runtime receipts and MCP remain UNJUDGEABLE.
