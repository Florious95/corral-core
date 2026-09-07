# Grok tile render chain (not the X fallback)

HTML: `Agent App Prototype.dc.html`

1. `Component.BRAND.grok = { run: 'grok', idle: 'grok' }` (L240)
2. `componentDidMount` fetches slug `grok` → `icons/grok.svg` into `iconCache['grok']` (L255–261)
3. `icon('grok', size, running)`: `BRAND['grok']` hits first (L283–290) and returns `<img src=iconCache['grok']>`
4. Loading placeholder is letter-circle `G`, not X
5. `switch (provider) case 'grok': blob(X paths)` (L308) is **unreachable** because grok is in BRAND

Wrong APK used L308 X geometry. Replacement is the BRAND img target glyph already in git (no network):

- SVG slug `grok`: blob `efb1a6183cbd9d1b68471805dc6c663e2a6fd7ff` SHA-256 `9175fc90c22655160231976c849f25a03b888d7cc0e04c5f1b987b659bb07c95`
- Runtime PNG `drawable-nodpi/provider_grok.png`: blob `804b01425d52c007a16edc1cdd8e3beaaf7f18e9` SHA-256 `515fd702a733df33e669a431f7d0b465350c8332c344c59672f2782f5ce3ff10`

Dead X path (must not equal Grok render): `M9 15.5 15 9M9.2 9.2l2.3 2.3M14.8 14.8l-2.3-2.3`
