// kotlin_catch_audit.js — F-16 catch audit for the Android tree.
//
// Walks every .kt file and reports every `catch (...)` block whose body is
// EMPTY (no statement at all). Those are the silent failures the audit calls
// out: a swallowed exception that nobody can debug later.
//
// Usage:  node scripts/kotlin_catch_audit.js [rootDir]
// Exit:   0 = ran fine (findings are printed), 2 = usage error.
//
// String-literal aware: a `catch` inside a string or comment is not counted.

const fs = require('fs');
const path = require('path');

const root = process.argv[2] || path.join('android', 'app', 'src', 'main', 'java');
if (!fs.existsSync(root)) {
  console.error('root not found: ' + root);
  process.exit(2);
}

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

// Blank out comments and string literals so brace counting is reliable.
// Newlines inside the removed text are preserved so line numbers stay exact.
function blank(m) {
  const nl = (m.match(/\n/g) || []).length;
  return '""' + '\n'.repeat(nl) + '""';
}
function strip(src) {
  return src
    .replace(/"""[\s\S]*?"""/g, blank)
    .replace(/"(?:\\.|[^"\\])*"/g, blank)
    .replace(/\/\/.*$/gm, blank)
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/'(?:\\.|[^'\\])'/g, blank);
}

const files = walk(root, []).sort();
let total = 0;
const perFile = [];

for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8');
  const clean = strip(raw);
  const rawLines = raw.split(/\r?\n/);

  // line number of a character offset in `clean`
  const lineStarts = [0];
  for (let i = 0; i < clean.length; i++) if (clean[i] === '\n') lineStarts.push(i + 1);
  const lineOf = (idx) => {
    let lo = 0, hi = lineStarts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (lineStarts[mid] <= idx) lo = mid; else hi = mid - 1;
    }
    return lo + 1; // 1-based
  };

  const hits = [];
  const re = /\bcatch\s*\(/g;
  let m;
  while ((m = re.exec(clean)) !== null) {
    // 1. find the ')' that closes the catch header
    let p = m.index + m[0].length; // just after '('
    let depth = 1;
    while (p < clean.length && depth > 0) {
      const c = clean[p];
      if (c === '(') depth++;
      else if (c === ')') depth--;
      p++;
    }
    if (depth !== 0) continue; // unterminated header
    // 2. find the '{' that opens the body
    let b = p;
    while (b < clean.length && clean[b] !== '{' && clean[b] !== '\n') b++;
    // a body must open within a couple of lines
    if (clean[b] !== '{') {
      let probe = b;
      let nl = 0;
      while (probe < clean.length && nl < 2) {
        if (clean[probe] === '\n') nl++;
        if (clean[probe] === '{') break;
        probe++;
      }
      if (clean[probe] !== '{') continue; // expression-body catch (rare) — skip
      b = probe;
    }
    // 3. find the matching '}' and check the body is empty
    let d = 0, end = -1;
    for (let k = b; k < clean.length; k++) {
      const c = clean[k];
      if (c === '{') d++;
      else if (c === '}') {
        d--;
        if (d === 0) { end = k; break; }
      }
    }
    if (end < 0) continue;
    const inner = clean.slice(b + 1, end).trim();
    if (inner === '') {
      hits.push({ line: lineOf(m.index), text: (rawLines[lineOf(m.index) - 1] || '').trim().slice(0, 120) });
    }
  }

  if (hits.length) {
    perFile.push({ f, hits });
    total += hits.length;
  }
}

perFile
  .sort((a, b) => b.hits.length - a.hits.length)
  .forEach(({ f, hits }) => {
    console.log(`\n${f}  (${hits.length} empty catch block(s))`);
    hits.forEach(h => console.log(`   L${h.line}: ${h.text}`));
  });

console.log(`\n---`);
console.log(`files scanned : ${files.length}`);
console.log(`empty catches : ${total}`);
console.log(`files affected: ${perFile.length}`);
