# Status-icons manual composition (auditable branch note)

Local mechanical composition. Not a merge of owning branches. Not a substitute for user merge authorization.

## Frozen inputs

- base/main: `4605951e427f9ba6627375498dcb3c757c05bf36`
- status-core local: `064bf78b84737e5fca941876f0598d4704d85b2f`
- PR #69 session local: `12646e0b6f876ca03fd18ac17495d5146decc580` (`4605951e..12646e0b`)
- PR #71 local: `497d423d897589ce1d273a110b5e13468134d34a` (`064bf78b..497d423d`)
- PR #71 remote corresponding: `27bb278f0ae2b5eb82bc8365addb93dad3220eb4` (same patch-id as local range)

Apply order: base → status → PR #69 range → PR #71 range.

## Authorized test-only conflict

Path: `app/app/src/debug/AndroidManifest.xml` add/add only.

Union keeps a single `<application>` with no duplicate application attributes, PR #69 `MobileSessionFixtureActivity`, PR #71 `ExternalSessionListAcceptanceActivity`, and the PR #69 release-exclusion comment. Production `app/app/src/main` was not edited to resolve it.

## Invalid local APK

Mac-built SHA-256 `f3539b011b0391d63ae43a1735975bd879f01ad662cc55d27af8f40f9fee831e` is invalid under the user override (no local compile). Rebuild is git-fetch on grok-bot only.
