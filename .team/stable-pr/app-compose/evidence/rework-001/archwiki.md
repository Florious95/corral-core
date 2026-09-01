# Incremental strict-T3 attribution

Raw strict command was run once at code candidate `50e3798ce14d5b06bb600ec3f2e4262f332821ff`:

```sh
python3 tools/archwiki/build_wiki.py --check --strict-t3
```

The global result remains exit 1 because frozen baseline debt remains; it is not mislabeled green. Incremental attribution against the preserved original compose strict log reports:

- T1-1 PASS unchanged;
- T1-2 changed from missing `dev.agentmirror.app.ui.components` package doc to PASS;
- normalized new T1/T3 findings: **0**;
- removed findings: five requested KDoc findings (`BackChevron`, `ProviderMark`, `FavoriteLongPressMenu`, `ProviderMarkState`, `ProviderPresentation`) plus the package doc's two declared consumes edges;
- no baseline cleanup.

After the strict run, only `server/internal/api/raw_bytes_api_test.go` and the Provider androidTest file changed. Neither changes the scanned production architecture surface; exact paths and trees are in `logs/exact-tree-attribution.log`. Raw and normalized reports are `logs/archwiki-strict-t3.log` and `logs/archwiki-attribution.log`.
