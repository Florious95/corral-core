# session-ui-shell implementation evidence

- Goal digest: `b4483865bb46c9c2740fead723e2419b8e9f2bf2c11131d571f5912e3fda016b`
- Base: `4605951e427f9ba6627375498dcb3c757c05bf36`
- Session dock mode is mutually exclusive: menu buttons switch the same second row to hotkeys/view/other-favorites chips; `返回菜单` restores menu.
- Favorite candidates are always `otherFavoriteRows(favoriteRows, currentRef)`, with no level2/all-session fallback. Empty candidates render `暂无收藏`; offline rows render `不在线` and are disabled.
- Build command: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --rerun-tasks --no-build-cache` (35 tasks, success).
- Emulator script was attempted; no eligible emulator was attached (exit 2), so connected test/mobile-tree/APK-copy evidence remains unknown.
- Archwiki command attempted with `--check --strict-t3`; exit 1, output in `archwiki.log`.
