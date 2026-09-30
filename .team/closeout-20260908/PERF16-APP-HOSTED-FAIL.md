# App recovery hosted result

## Apparatus correction

- Leader checked c20b5257d29cb3838dc0f68ee3f4c19c181d85b0: one test file only; OLD_DELTA changes ESC[8;1m (SGR) to ESC[8;1H (CUP). No assertion/deadline/product change.
- Prior old-residual timeout classified apparatus-invalid, not App product failure. Raw JUnit and run remain preserved.
- Existing workflow matches push to test/perf16-app-recovery, so reported push needs no duplicate dispatch. New run execution/result unobserved; A1–A5 still pending, A6 separate.

## JUnit verified after targeted follow-up

- Existing artifact retrieved using curl after urllib transport failed; SHA256 matches 4b25458d059e22661867f203c21a8550bf1bc73ccfb87a7b9635dda84a628a00. No test rerun or CI polling.
- JUnit: tests=2, failures=1, errors=0, skipped=0. Auth-reject scenario passed.
- Exact failure: `java.lang.AssertionError: timed out waiting for old residual applied`; caller line 218, helper line 132.
- XML and verified ZIP saved under `.team/closeout-20260908/perf16-app-34149580611/`. This supersedes the unverified ZIP/skip boundary below; failure attribution remains pending.

- Source a02a72cbcae8d3f55aebf5ea3d1f9f8c09637f8b; push run 34149580611, job 101828814888, completed failure.
- Gradle log: 2 tests completed, 1 failed. Named failure realOkHttpAbruptClose_servicePump_reauthListSubscribeClearsStaticScreen, AssertionError at Perf16AppRecoveryScenarioTest.kt:132.
- Artifact 10028943355, 15682 bytes, SHA256 4b25458d059e22661867f203c21a8550bf1bc73ccfb87a7b9635dda84a628a00. Connector returned file reference; local download failed with URLError. ZIP contents/JUnit skipped count not verified. No retry loop.
- A1–A5 not PASS; product-vs-apparatus cause unclassified. A6 still pending. Test seat to inspect exact assertion/oracle and classify before repair; do not weaken expectations or modify App product.

```
2026-09-07T17:55:58.5071520Z a02a72cbcae8d3f55aebf5ea3d1f9f8c09637f8b
2026-09-07T17:56:02.6345370Z a02a72cbcae8d3f55aebf5ea3d1f9f8c09637f8b
2026-09-07T17:56:11.6144530Z [36;1m./gradlew --no-daemon --rerun-tasks \[0m
2026-09-07T17:56:11.6145640Z [36;1m  --tests 'dev.agentmirror.app.session.Perf16AppRecoveryScenarioTest' \[0m
2026-09-07T17:59:08.0880020Z Perf16AppRecoveryScenarioTest > realOkHttpAbruptClose_servicePump_reauthListSubscribeClearsStaticScreen FAILED
2026-09-07T17:59:08.0901850Z     java.lang.AssertionError at Perf16AppRecoveryScenarioTest.kt:132
2026-09-07T17:59:09.7036710Z 2 tests completed, 1 failed
2026-09-07T17:59:09.7051710Z BUILD FAILED in 2m 57s
```
