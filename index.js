'use strict';

// java-polycall is a source distribution of a Java (Maven)
// binding; requiring it from Node.js only locates the packaged files.
const path = require('node:path');

const fromPackageRoot = (...parts) => path.join(__dirname, ...parts);

module.exports = Object.freeze({
  packageName: 'java-polycall',
  language: 'Java',
  abi: 1,
  pom: fromPackageRoot('pom.xml'),
  javaSources: fromPackageRoot('src', 'main', 'java'),
  api: fromPackageRoot('src', 'main', 'java', 'org', 'obinexus', 'polycall', 'Polycall.java'),
  cli: fromPackageRoot('src', 'main', 'java', 'org', 'obinexus', 'polycall', 'cli', 'Main.java'),
  config: fromPackageRoot('java-polycallrc'),
  manifest: fromPackageRoot('polycall-binding.json')
});
