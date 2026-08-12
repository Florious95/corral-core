#!/usr/bin/env node
/*
 * postinstall: copy the xterm.js dist (npm package "@xterm/xterm", MIT) into
 * web/vendor/xterm/ so that `npx serve web/` serves a fully self-contained
 * static tree — no build step, no CDN dependency.
 *
 * MIT license is Apache-2.0 compatible; the package is a plain UMD build that
 * assigns `Terminal` onto globalThis (`window.Terminal`), which we load with a
 * classic <script> tag before the ES-module app code.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'node_modules', '@xterm', 'xterm');
const DST = path.join(ROOT, 'vendor', 'xterm');

const FILES = [
  ['lib/xterm.js', 'xterm.js'],
  ['lib/xterm.js.map', 'xterm.js.map'],
  ['css/xterm.css', 'xterm.css'],
];

function main() {
  if (!fs.existsSync(path.join(SRC, 'package.json'))) {
    console.error('postinstall: node_modules/@xterm/xterm not found — run `npm install` first.');
    process.exit(1);
  }
  fs.mkdirSync(DST, { recursive: true });
  let copied = 0;
  for (const [rel, name] of FILES) {
    const from = path.join(SRC, rel);
    if (!fs.existsSync(from)) continue; // sourcemap may be absent on some dists
    fs.copyFileSync(from, path.join(DST, name));
    copied += 1;
  }
  console.log(`postinstall: copied ${copied} xterm dist files to web/vendor/xterm/`);
}

main();
