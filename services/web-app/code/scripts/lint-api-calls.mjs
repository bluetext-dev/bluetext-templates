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

// A browser-facing HTTP client call (fetch / axios.* / ky.* / $fetch). Matches
// the call prefix up to its `(`; the URL argument that follows is classified
// below. \bfetch avoids matching `prefetch`, `fetcher.load` (react-router
// same-origin route loads — legitimate).
const CALL = /(?:\bfetch|\baxios(?:\.\w+)?|\bky(?:\.\w+)?|\$fetch)\s*\(\s*/g;
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

// Whether a call's first argument (the text right after `(`) is built by a
// top-level string concatenation (`base + "/path"`) — the hand-rolled-api-base
// footgun. Ignores `+` inside nested calls/strings; stops at the first top-level
// comma or the closing paren.
function firstArgIsConcat(rest) {
  let depth = 1, str = null;
  for (let i = 0; i < rest.length; i++) {
    const c = rest[i];
    if (str) { if (c === "\\") i++; else if (c === str) str = null; continue; }
    if (c === '"' || c === "'" || c === "`") { str = c; continue; }
    if (c === "(" || c === "[" || c === "{") { depth++; continue; }
    if (c === ")" || c === "]" || c === "}") { depth--; if (depth === 0) return false; continue; }
    if (depth === 1 && c === ",") return false;
    if (depth === 1 && c === "+") return true;
  }
  return false;
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

    // Rule B/C — classify each browser HTTP call's URL argument. The ONLY
    // sanctioned browser→backend calls are `apiUrl("/path")` or a literal
    // `/api/...`. A bare relative path, a dynamic/templated base, a string
    // concatenation, or any other computed URL bypasses the same-origin proxy
    // (this is how the recurring CORS bug ships — an agent builds its own api
    // base and the literal-only check can't see it).
    CALL.lastIndex = 0;
    let m;
    while ((m = CALL.exec(line)) !== null) {
      const rest = line.slice(m.index + m[0].length);
      if (rest === "") continue;                  // args wrap to the next line — can't classify
      if (/^apiUrl\s*\(/.test(rest)) continue;    // ✅ the sanctioned seam
      const q = rest[0];
      if (q === '"' || q === "'" || q === "`") {
        const end = rest.indexOf(q, 1);
        const content = end === -1 ? rest.slice(1) : rest.slice(1, end);
        if (q === "`" && content.startsWith("${")) {
          violations.push(
            `${where}  browser→backend fetch uses a dynamic/templated base — that reconstructs a ` +
            `cross-origin api URL. Build the call with apiUrl("/path") instead.\n    ${snippet}`
          );
          continue;
        }
        if (/^https?:\/\//i.test(content)) continue;                 // absolute literal: Rule A flags bluetext hosts; third-party allowed
        if (content === "/api" || content.startsWith("/api/")) continue; // ✅ correct
        const fix = content.startsWith("/") ? content : `/${content}`;
        violations.push(
          `${where}  browser→backend call "${content}" is not under /api/ — it bypasses the same-origin ` +
          `proxy (404, then CORS if "fixed" with the api's URL). Use apiUrl("${fix}").\n    ${snippet}`
        );
        continue;
      }
      // Non-literal first argument. Flag a STRING CONCATENATION (`base + "/x"`)
      // — the hand-rolled-api-base footgun. A bare identifier / call (e.g. a URL
      // already built via apiUrl and stored in a const) is intentionally left
      // alone to avoid false positives.
      if (firstArgIsConcat(rest)) {
        violations.push(
          `${where}  browser→backend fetch builds its URL by concatenation — that reconstructs a ` +
          `cross-origin api base. Build the call with apiUrl("/path") instead.\n    ${snippet}`
        );
      }
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
