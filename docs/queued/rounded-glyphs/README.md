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

Current validation is in progress: independent direction/arc reproduction,
executed geometry and production Canvas tests, and one real App character fixture
at normal and small sizes. No service changes, performance matrix, or remote merge.
