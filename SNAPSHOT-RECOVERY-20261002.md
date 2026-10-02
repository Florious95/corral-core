# Subscription first-snapshot recovery candidate

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
