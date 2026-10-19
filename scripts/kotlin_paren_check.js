const fs = require('fs');
const files = process.argv.slice(2);
if (files.length === 0) { console.error('usage: node kotlin_paren_check.js <file.kt> ...'); process.exit(2); }
let bad = 0;
for (const f of files) {
  const s = fs.readFileSync(f, 'utf8');
  // Strip string literals FIRST (they may contain // or /* like URLs), then
  // comments, then char literals.
  const clean = s
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/\/\/.*$/gm, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/'(?:\\.|[^'\\])'/g, "''");
  let bal = 0, min = 0;
  for (const c of clean) { if (c === '{') bal++; if (c === '}') bal--; if (bal < min) min = bal; }
  let p = 0, minP = 0;
  for (const c of clean) { if (c === '(') p++; if (c === ')') p--; if (p < minP) minP = p; }
  console.log(f, 'braces=' + bal, 'minDepth=' + min, 'parens=' + p);
  if (bal !== 0 || min < 0 || p !== 0 || minP < 0) bad++;
}
process.exit(bad ? 1 : 0);
