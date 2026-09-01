# Emulator acceptance

Result: **unjudgeable — missing authorized already-running emulator**.

- `$HOME/Library/Android/sdk/platform-tools/adb devices -l` returned no devices.
- Mobile MCP `mobile_list_available_devices` returned `{"devices":[]}`.
- This task did not create, start, stop, kill, clean, or otherwise mutate a device/AVD.
- The frozen session UI inventory found exactly seven expected tests, but connected execution was not attempted without the required apparatus.
- No Provider focused instrumentation flow exists in the frozen composed tree (`app/app/src/androidTest` contains only the session UI smoke class among Session/Provider candidates), so Provider connected execution is also unavailable.

Unavailable same-device checks: four-axis fixture behavior, exact three-button gestures/sheet/switcher, Provider state/short-click/long-press/offline semantics, terminal identity, controlled IME/resize, theme chrome, and crash query. No screenshot or pixel verdict was used.
