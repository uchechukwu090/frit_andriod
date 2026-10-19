// js_catch_audit.js — Week 6 F-08 sweep for the server tree.
//
// Walks every .js file under server/src and reports catch blocks whose body is
// EMPTY (or only a comment), i.e. failures nobody can debug later. Those are the
// same "silent failure" pattern the Kotlin audit (F-16) found — this is the
// server-side counterpart.
//
// Usage:  node scripts/js_catch_audit.js [rootDir]
// Exit:   0 = ran fine (findings printed), 2 = usage error.
//
// String/template/comment aware so a `catch` inside a string is not counted.

const fs = require('fs');
const path = require('path');

const root = process.argv[2] || path.join('server', 'src');
if (!fs.existsSync(root)) {
  console.error('root not found: ' + root);
  process.exit(2);
}

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.js')) out.push(p);
  }
  return out;
}

function blank(m) {
  const nl = (m.match(/\n/g) || []).length;
  return '""' + '\n'.repeat(nl) + '""';
}
function strip(src) {
  return src
    .replace(/`(?:\\[\s\S]|[^`\\])*`/g, blank)
    .replace(/"(?:\\.|[^"\\])*"/g, blank)
    .replace(/'(?:\\.|[^'\\])*'/g, blank)
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/\/\/.*$/gm, blank);
}

const files = walk(root, []).sort();
let total = 0;
const perFile = [];

for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8');
  const clean = strip(raw);
  const rawLines = raw.split(/\r?\n/);

  const lineStarts = [0];
  for (let i = 0; i < clean.length; i++) if (clean[i] === '\n') lineStarts.push(i + 1);
  const lineOf = (idx) => {
    let lo = 0, hi = lineStarts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (lineStarts[mid] <= idx) lo = mid; else hi = mid - 1;
    }
    return lo + 1;
  };

  const hits = [];
  const re = /\bcatch\s*\(/g;
  let m;
  while ((m = re.exec(clean)) !== null) {
    // skip `obj.catch(...)` (promise chains: the token before is '.')
    const before = clean.slice(Math.max(0, m.index - 2), m.index);
    if (before === '.)' || /\.\s*$/.test(before)) continue;

    // find the ')' closing the catch header
    let p = m.index + m[0].length;
    let depth = 1;
    while (p < clean.length && depth > 0) {
      const c = clean[p];
      if (c === '(') depth++;
      else if (c === ')') depth--;
      p++;
    }
    if (depth !== 0) continue;

    // the body: `{ ... }` or bare statement (rare in this codebase)
    let b = p;
    while (b < clean.length && /\s/.test(clean[b])) b++;
    if (clean[b] !== '{') {
      // expression / bare-statement catch — report only if it is literally empty
      const eol = clean.indexOf('\n', b);
      const stmt = clean.slice(b, eol < 0 ? clean.length : eol).trim();
      if (stmt === '') hits.push({ line: lineOf(m.index), kind: 'statement', text: '' });
      continue;
    }

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
      hits.push({ line: lineOf(m.index), kind: 'EMPTY', text: (rawLines[lineOf(m.index) - 1] || '').trim().slice(0, 120) });
    } else if (!/\b(console\.|log(?:Warn|Error|Info|Debug)?\s*\(|throw\b|process\.exit|reject\s*\(|reject\.|next\s*\(|res\.)|\.status\s*\(/.test(inner)) {
      // non-empty body that neither logs nor rethrows: the error vanishes
      hits.push({ line: lineOf(m.index), kind: 'SILENT', text: (rawLines[lineOf(m.index) - 1] || '').trim().slice(0, 120) });
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
    console.log(`\n${f}  (${hits.length} silent catch(es))`);
    hits.forEach(h => console.log(`   L${h.line} [${h.kind}]: ${h.text || '(bare statement)'}`));
  });

const emptyCount = perFile.reduce((n, { hits }) => n + hits.filter(h => h.kind === 'EMPTY').length, 0);
const silentCount = total - emptyCount;
console.log(`\n---`);
console.log(`files scanned : ${files.length}`);
console.log(`EMPTY catches : ${emptyCount}   (swallowed: no statement at all — fix these)`);
console.log(`SILENT catches: ${silentCount}   (non-empty, no log/rethrow — read them, most hand the error back)`);
console.log(`total         : ${total}`);
console.log(`files affected: ${perFile.length}`);
