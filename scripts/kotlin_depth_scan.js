// Show cumulative brace depth around every line where raw depth goes negative.
const fs = require('fs');
const s = fs.readFileSync(process.argv[2], 'utf8');
let bal = 0;
const lines = s.split(/\r?\n/);
lines.forEach((line, i) => {
  for (const c of line) { if (c === '{') bal++; if (c === '}') bal--; }
  if (bal < 0) {
    console.log('NEGATIVE at line', i + 1, 'depth', bal, ':', line.trim().slice(0, 100));
    console.log('  context:');
    const from = Math.max(0, i - 3), to = Math.min(lines.length, i + 2);
    for (let j = from; j < to; j++) console.log('   ', j + 1, lines[j].slice(0, 120));
    bal = 0; // reset to find subsequent occurrences relative
  }
});
console.log('final depth', bal);
