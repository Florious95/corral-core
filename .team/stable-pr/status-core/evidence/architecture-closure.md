# Status-core architecture closure

Base: `4605951e427f9ba6627375498dcb3c757c05bf36`
Accepted source: `ff316dc0afe8ab280e61d30934e7624579be6224` (tree `5217a41aa914ddcb72c27f39f1b4af9ead68b1b6`)

## Forward chain

`accepted nodeprobe schema-v1 report`
→ `server/internal/nodeprobe` capability verification, typed direct runner, per-socket sampler, structural join
→ `server/internal/api` catalog/listing/list_delta/level2
→ `server/internal/protocol.Session` JSON (`provider`, `activity`, nullable `session_name`, `health`, `status==activity`)
→ `app/core-protocol Session` fail-closed effective activity
→ `Session.toL2Entry`
→ `WorkspaceViewModel` level-2 cache/full replacement
→ existing status badge.

## Reverse regression surface

- daemon startup and accepted binary/extension/corpus capability;
- discovery socket isolation and stable `ref`/bridge routing;
- listing full/delta equality;
- level-2 change key and idle gating;
- Kotlin old-server compatibility and badge status.

## Removed authority edges

`provider.Load`, `ProviderFinder`/`procFinder`, `identifyModel`/`filterModel`, `classifyForProvider`/`registerL2Detector`, and footer/background-task rules are deleted. Discovery remains structural only; title remains opaque display data.

## Boundary

Production capability is the accepted macOS arm64 binary only. Other platforms fail startup explicitly and never select a substitute classifier. The accepted upstream future-timestamp tolerance and challenge response `socket_path` behavior remain unchanged and unresolved here by decision.
