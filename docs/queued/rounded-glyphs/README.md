# Rounded box-drawing glyph correction

The active fix gives U+256D–U+2570 genuine quarter-circle paths and restores the
bottom corners' correct left/up and right/up connections. Stroke centers match
existing integer light-line bands; straight segments at cell boundaries preserve
joins. Tiny cells retain correctly directed arms when no arc radius fits.
Ordinary line/block rectangles and their non-antialiased paint are unchanged.

The original [source report](source-report.md) remains verbatim historical context;
its external tests do not count as executed validation of this candidate.

This PR carries only the Android renderer difference in corral-app. The user APK
is built from the corresponding corral-core renderer on accepted branding source
`2ee62e5299f3ad135a56589748bcca01da356dde`; that narrow companion retains branding
and Issue16 fixes without importing either repository's whole tree.

Validation: independent `RoundedGlyphAcceptanceTest` executed on Grok against the
corresponding production renderer: 3 tests passed (exit 0), covering four-corner
geometry/direction, integer straight-line bands and the production Canvas Path
route with resolved color and endpoint directions. The same test source is included
here. Run `./gradlew :app:testDebugUnitTest --tests
'dev.agentmirror.app.termview.RoundedGlyphAcceptanceTest'` from the Android project.

Per the user's narrowed scope, no device, size/theme matrix, unrelated regression
or performance gate. Release APK is built from exact core source `f6151fc2f` and
signed with the existing ea427 certificate; the later direct-test import does not
change product source. No service changes or remote merge.
