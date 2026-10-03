'use strict';

// npm package integrity: the entry point loads and every path it exports
// exists in the (packed or checked-out) package.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const binding = require('..');
const metadata = require('../package.json');
const manifest = require('../polycall-binding.json');

assert.equal(metadata.name, 'java-polycall');
assert.equal(metadata.license, 'MIT');
assert.equal(metadata.repository.url, 'git+https://github.com/obinexus/java-polycall.git');
assert.equal(manifest.version, metadata.version, 'polycall-binding.json version matches package.json');
assert.equal(manifest.core_repository, 'https://github.com/obinexus/polycall');

for (const [name, file] of Object.entries(binding)) {
  if (typeof file !== 'string' || !path.isAbsolute(file)) continue;
  assert.equal(fs.existsSync(file), true, `missing ${name}: ${file}`);
}

console.log('java-polycall npm package test: PASS');
