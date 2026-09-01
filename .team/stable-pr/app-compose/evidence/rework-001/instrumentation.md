# Provider instrumentation flow

`ProviderUiSmokeTest.kt` is one focused androidTest using the real `SessionListRows` and `FavoritesScreen` composables. It seeds only test-local `SessionItem` state and uses Compose semantics plus real click/long-click touch input; callbacks are never invoked directly.

Covered in the flow:

- running/idle/abnormal/unknown Provider descriptions, same Claude identity for running/idle, and abnormal/unknown not exposed as idle;
- Provider icon touch does not favorite, ordinary row short-click opens, long-press exposes one `收藏` and mutates once;
- online favorite opens, offline favorite does not, both long-press to the sole `取消收藏` action and mutate once;
- close/destroy/create/open-Agent/Provider-config actions absent.

The final exact candidate's debug Android test APK compiles successfully. The existing session seven-test flow is unchanged and not duplicated.

Execution is **unjudgeable**: after the host barrier, SDK adb reported no connected device and Mobile MCP returned `{"devices":[]}`. No device was created, started, stopped, killed, or cleaned; no screenshot/pixel verdict was used.
