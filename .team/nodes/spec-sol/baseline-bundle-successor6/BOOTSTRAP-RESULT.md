# successor6 bootstrap result

Stage-one bootstrap is complete and no final ledger exists. The new gate separates two independently falsifiable facts: canonical content identity must rehash to bundle_id, while the two independent build APKs must occupy exact stable non-id slots with no traversal/circular placeholder/alias and independent build roots. Archive paths remain id-scoped content addresses.

The actual frozen successor5 manifest reproduces the legacy prefix conflict (`old_constraint_rc=1`) and passes the new projection (`canonical=true`, `slot=true`, `archive=true`). The pinned legal fixture and controlled mutations pass the permanent matrix: legal0, bundle-id tamper1, traversal1, slot rename1, slot swap1, legacy id-scoped path1, malformed manifest1, missing fact2.

The successor6 deep gate is a 200-line preservation copy of the legacy deep gate with one semantic path-prefix change. All source/APK/runtime/signature/report/provenance/archive/A2/focused/private-payload checks remain. The wrapper runs pinned projection teeth first, locks the real manifest hash, then deep/canonical-real/controlled-bypass gates; it does not invoke the obsolete legacy impl gate or successor3 impl wrapper. Successor5 SDK fallback and structure teeth were rechecked green.

The full wrapper is intentionally not run in stage one: doing so would require placing uncommitted bootstrap files into the frozen old WT, violating the read-only attempt boundary. Leader must independently review and commit this bootstrap; stage two then pins that commit, creates a new ledger id/revision1 with new absent WT identities, generates successor6-only structure gates, and runs the full real wrapper in a fresh case. Until then this is apparatus readiness, not a product pass.

No old judge, ledger, attempt, WT or product code was modified; no ledger was started; no credential, SDK value or private APK content was printed.

verdict: pass
