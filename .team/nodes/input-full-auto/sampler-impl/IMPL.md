# t.sampler.impl implementation

## Scope

Added only `tools/perfbase/run-input-ab.sh`, `tools/perfbase/parse-input-ab.py`,
and this report. No product code, historical baseline, or old judge changed.

## Old入口先红

The frozen contract records that `.team/nodes/ca-emu/tmp/runab.sh` runs each
sample as `A then B`, has no stable-tag/reference-md5 guard, and parses only
the old two-segment result. It cannot establish this sampler's required
A/B/A/B, identity, or four-segment evidence. The old entry point remains
unchanged; this is the recorded red precondition for the new implementation.

## Implemented

- `run-input-ab.sh --self-test` creates hand-authored isolated PerfTrace
  fixtures and checks positive and negative cases for order, identity,
  three-fixture coverage, four segments, monotonic events, `n>=10`,
  `B/A<=1.10`, missing host readings, and envcheck exit handling. Generated
  files stay under this seat's node-local temporary directory.
- Normal sampling runs `envcheck.sh --gate` before setup, installs two APKs,
  records strict per-fixture A/B/A/B order, uses the existing isolated fixture
  and cold-open entry points, captures load/free/inactive readings, and refuses
  missing/identical/wrong-reference APK identity.
- `parse-input-ab.py` emits `schema=perf-ab.v1` with raw samples, p50/p95,
  identities, environment fields (`gate_exit` and `free_inactive`), order
  evidence, and an explicit verdict. Fixture segment `A` and `B` fields are
  raw millisecond arrays so an independent checker can recompute statistics.
  Missing evidence is exit 2; a measured ratio above 1.10 with otherwise
  complete evidence is exit 1.

## Verification

```text
bash tools/perfbase/run-input-ab.sh --self-test
python3 -m py_compile tools/perfbase/parse-input-ab.py
sh -n tools/perfbase/run-input-ab.sh
sh .team/ledgers/acceptance/sampler-impl.sh
```

Expected self-test result: exit 0 and
`SELF-TEST PASS order identity fixtures segments samples ratio env`.
