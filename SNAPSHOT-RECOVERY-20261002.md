# Subscription first-snapshot recovery candidate

## Current frozen candidate: recovery3

Source `843b18222ea4f9348e87bbc2c611b13a49728d08`, tree `12f3bfa9982bc0e039291d8c86bd5adfd0e02b1d`.
Coordinate `dev.agentmirror.core:core-conn:20261002.snapshot-recovery3` additionally atomically claims the deadline before redialing and never rearms the wait from retry. A concurrent matching WS snapshot cannot leave a stale pump entry that falsely reconnects an already-completed wait. Budgets and scope are unchanged.
- AAR SHA-256 `ceeb3752668d2acd2128265117405faa6e8d40fbe0da508e4f781b7b1f89af75`
- JAR SHA-256 `3c22c27b31df6a9c8776e5cbeecb3fd54b5d7e97f33b7bb734751a0483d0c841`
- Developer tests: 12/12 passed. Final independent acceptance is recorded separately.

Recovery1/recovery2 coordinates are immutable intermediate evidence, **not APK deliverables**. Protocol/terminal are unchanged.

## Historical intermediate candidate: recovery2

Source `7491b2aaf26a2f301912446e0e62b606e4ee1253`, tree `c5b49da1319623c8f164106ad1524751a65d7600`.
Coordinate `dev.agentmirror.core:core-conn:20261002.snapshot-recovery2` adds volatile closed visibility and serialized finish to the same recovery logic, because local pump termination and transport callbacks run on different real threads.
- AAR SHA-256 `ebacef81ae370f985f29b241ae9a4aedea192fffa66dec2c9a2154a5dce1f252`
- JAR SHA-256 `ed517bfe1a2de91bf902024501002e72d3ca6dbf0d98fd415912e03da9c6beab`
- Developer tests: 12/12 passed, including competing timeout/network termination.

Recovery1 remains immutable below for historical evidence; its APK is **not the deliverable**. Protocol/terminal still use the identical golden hashes listed below.

## Historical intermediate candidate: recovery1

Source: `ad4ff6b590f584c22f555e2e5d4b342bf83d3a38` (tree `95cfcdc8f741df9f2a1e23cbf22bf6c3af2cc63d`). Golden source reconstruction is a separate predecessor commit `c7fe4e68a`; all its classes/Kotlin metadata match golden core-conn JAR `0c2aab7c59ef67d2775074036070f05ef734dba258ed0343345e96d0ee15f66c` byte-for-byte.

New coordinate: `dev.agentmirror.core:core-conn:20261002.snapshot-recovery1`.

SHA-256:
- core-conn AAR: `d6bb882dccea63322b0e5a6dd75c5e9e26d0d21063d5a03261103e65a4eb9189`
- core-conn JAR: `5ddaed63a224e5c744a6591f7ac44da2bc666e63ac8d5545aff1ee3b791fcf8a`

Unchanged golden dependencies have been copied, not rebuilt, into their previously absent `20260915.close` directories:
- core-protocol AAR: `e6ec508b623d98f15ad0341b405b558fe0e207d433b3d0117ca70812892b3e79`
- core-protocol JAR: `0543c87d6806970496ee58e2ce57e8279e5333c7118b64fa4b468f1026b7b855`
- core-terminal AAR: `b81934a3ea6d11913a03b89b93aa08be1f755a54a3cdf9ad26e3eaad134c7865`
- core-terminal JAR: `7ef686bd346ef08c5de50e948eee8a858f761fad389edcd92d9eb384cb795e6e`

No existing coordinate or Maven metadata is overwritten. Consume this repository via its immutable Git commit URL, not a moving branch. Developer unit tests: 11/11 passed; independent acceptance is recorded separately, not implied by publication.
