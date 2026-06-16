#!/usr/bin/env node
// Guards the one rule that keeps biting agent-built frontends: a browser must
// reach the backend SAME-ORIGIN via `/api/...` (the vite.config.ts proxy), never
// at the api's own URL (a sibling `service--token--user.domain` subdomain → a
// different origin → CORS). See app/lib/api.ts (`apiUrl`) and AGENTS.md.
//
// Run by `b service check web-app` (and the in-app agent harness after every
// edit), so a wrong call fails the check and the agent self-corrects.
//
// Usage: node scripts/lint-api-calls.mjs [rootDir]   (default: app)
// Exit 1 with file:line + fix on any violation; 0 when clean.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = process.argv[2] || "app";
const SCAN_EXT = /\.(ts|tsx|js|jsx|mjs|cjs)$/;
// Excluded: the seam itself (legitimately builds `/api`), server-only files
// (may use the in-cluster API_URL directly), tests, and the lint/build scripts.
const EXCLUDE = (p) =>
  p.endsWith("/lib/api.ts") ||
  /\.server\.(ts|tsx|js|jsx)$/.test(p) ||
  /\.(test|spec)\./.test(p) ||
  /(^|\/)(node_modules|scripts|build|public)(\/|$)/.test(p);

const IGNORE = "bluetext-lint-ignore api-call";

// A browser-facing HTTP client call (fetch / axios.* / ky.* / $fetch) whose URL
// argument is a string or template literal. \bfetch avoids matching `prefetch`,
// `fetcher.load` (react-router same-origin route loads — legitimate).
const CALL = /(?:\bfetch|\baxios(?:\.\w+)?|\bky(?:\.\w+)?|\$fetch)\s*\(\s*(["'`])([^"'`]*)\1/g;
// Cross-origin footgun: a service's own URL or a mounted link's ingress-url in
// browser code.
const ABSOLUTE = /(bluetext\.dev|bluetext\.localhost|ingress-url)/i;

function walk(dir, out = []) {
  let entries;
  try { entries = readdirSync(dir); } catch { return out; }
  for (const name of entries) {
    const p = join(dir, name);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) walk(p, out);
    else if (SCAN_EXT.test(p) && !EXCLUDE(p)) out.push(p);
  }
  return out;
}

// Blank out comments (// to EOL, /* */ across lines) while preserving string
// contents and line structure, so a URL mentioned in a comment or doc example is
// not mistaken for a real call, and `https://` inside a string isn't read as a
// comment. Returns an array of code-only lines (1:1 with the source lines).
function stripComments(source) {
  const out = [];
  let inBlock = false;
  let str = null; // the open quote char, or null
  for (const line of source.split("\n")) {
    let res = "";
    for (let j = 0; j < line.length; j++) {
      const c = line[j], d = line[j + 1];
      if (inBlock) {
        if (c === "*" && d === "/") { inBlock = false; res += "  "; j++; } else res += " ";
        continue;
      }
      if (str) {
        res += c;
        if (c === "\\") { res += d ?? ""; j++; }
        else if (c === str) str = null;
        continue;
      }
      if (c === "/" && d === "/") { res += " ".repeat(line.length - j); break; }
      if (c === "/" && d === "*") { inBlock = true; res += "  "; j++; continue; }
      if (c === '"' || c === "'" || c === "`") { str = c; res += c; continue; }
      res += c;
    }
    out.push(res);
  }
  return out;
}

function lintFile(path, violations) {
  const raw = readFileSync(path, "utf8");
  const codeLines = stripComments(raw);
  const lines = raw.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = codeLines[i]; // comments blanked; strings intact — match on this
    // Escape hatch lives in a comment (stripped from `line`), so check raw source.
    const suppressed = lines[i].includes(IGNORE) || (i > 0 && lines[i - 1].includes(IGNORE));
    if (suppressed) continue;
    const where = `${path}:${i + 1}`;
    const snippet = lines[i].trim(); // show the real source line

    // Rule A — cross-origin absolute URL / ingress-url in browser code.
    if (ABSOLUTE.test(line)) {
      violations.push(
        `${where}  browser code must not reference a service's own URL / ingress-url ` +
        `(cross-origin → CORS). Reach the api same-origin: apiUrl("/path") from app/lib/api.ts.\n    ${snippet}`
      );
      continue;
    }

    // Rule B — every relative HTTP call must be under /api/. A relative fetch is
    // always a backend call here (assets use import / <img src>, not fetch).
    CALL.lastIndex = 0;
    let m;
    while ((m = CALL.exec(line)) !== null) {
      const url = m[2];
      if (!url.startsWith("/") || url.startsWith("//")) continue; // absolute / protocol-relative → not Rule B
      if (url === "/api" || url.startsWith("/api/")) continue;     // correct
      violations.push(
        `${where}  browser→backend call "${url}" is not under /api/ — it bypasses the same-origin ` +
        `proxy (404, then CORS if "fixed" with the api's URL). Use apiUrl("${url}").\n    ${snippet}`
      );
    }
  }
}

const files = walk(ROOT);
const violations = [];
for (const f of files) lintFile(f, violations);

if (violations.length) {
  console.error(`\n✗ api-call lint: ${violations.length} violation(s) — browser→backend must go through /api/ (same-origin).\n`);
  for (const v of violations) console.error("  " + v + "\n");
  console.error(
    "Fix: call the backend via apiUrl(\"/path\") (app/lib/api.ts) so it stays same-origin\n" +
    "through the /api proxy. The api defines routes WITHOUT /api (the proxy strips it).\n" +
    "Genuinely-not-an-api relative fetch? add `// " + IGNORE + "` on the line above.\n"
  );
  process.exit(1);
}
console.log("✓ api-call lint: all browser→backend calls are same-origin /api/.");
