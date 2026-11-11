// Runs the built extension in the VS Code installed on this Mac over test/fixtures/real, and records or checks what the editor showed.
import { execFileSync, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const extension = join(here, "../..");
const workspace = join(extension, "test/fixtures/real");
const snapshot = join(here, "snapshot");
const check = process.argv.includes("--check");
const code = process.env.STREAMLORD_REAL_CODE ?? "/Applications/Visual Studio Code.app/Contents/MacOS/Code";
// VS Code puts a socket under the user data directory, and a socket's path is short.
const scratch = process.env.STREAMLORD_REAL_TMP ?? "/tmp/streamlord-real";
/** Where the caret stands in a mark of marks.json. */
const CARET = "¦";
/** The prefix of an aliased bundle, which the files under this folder are written for. */
const ALIASED = "aliased/";
/** Files of the languages the run declares; anything else in the workspace is there to be found by the extension. */
const OPENED = /\.(html|kt|jte|kte|ftl|vm|mustache)$/;

if (!existsSync(code)) fail(`VS Code is not at ${code}. Install it, or set STREAMLORD_REAL_CODE to its Contents/MacOS/Code.`);

function fail(message) {
  console.error(message);
  process.exit(1);
}

function walk(dir) {
  return readdirSync(dir)
    .sort()
    .flatMap((name) => (statSync(join(dir, name)).isDirectory() ? walk(join(dir, name)) : [join(dir, name)]));
}

async function runEditor() {
  execFileSync("npm", ["run", "build", "--silent"], { cwd: extension, stdio: "inherit" });
  rmSync(scratch, { recursive: true, force: true });
  mkdirSync(join(scratch, "user/User"), { recursive: true });
  // A fresh profile each run, with what would add answers of its own turned off.
  writeFileSync(
    join(scratch, "user/User/settings.json"),
    JSON.stringify({
      "chat.disableAIFeatures": true,
      "editor.wordBasedSuggestions": "off",
      "extensions.autoUpdate": false,
      "git.enabled": false,
      "telemetry.telemetryLevel": "off",
      "update.mode": "none",
      "workbench.startupEditor": "none",
    }),
  );
  const files = walk(workspace)
    .map((f) => relative(workspace, f))
    .filter((f) => OPENED.test(f));
  const marks = JSON.parse(readFileSync(join(here, "marks.json"), "utf8"));
  for (const m of marks) if (!files.includes(m.file)) fail(`marks.json names ${m.file}, which is not in the workspace.`);
  const job = {
    workspace,
    out: join(scratch, "out.json"),
    caret: CARET,
    marks,
    renameTo: "renamedSignal",
    // The extension lints 250 ms after a change, and once more when the lint changed what the workspace defines.
    settle: 600,
    passes: [
      { prefix: "data-", files: files.filter((f) => !f.startsWith(ALIASED)) },
      { prefix: "data-star-", files: files.filter((f) => f.startsWith(ALIASED)) },
    ].filter((p) => p.files.length > 0),
  };
  writeFileSync(join(scratch, "job.json"), JSON.stringify(job));
  const env = { ...process.env, STREAMLORD_REAL_JOB: join(scratch, "job.json") };
  // Set by an Electron parent such as VS Code's own terminal, and it would make Code start as plain Node.
  delete env.ELECTRON_RUN_AS_NODE;
  const child = spawn(
    code,
    [
      // First: Code's command line takes the word after a switch it does not know, such as Chromium's below, for that switch's value.
      workspace,
      `--extensionDevelopmentPath=${extension}`,
      `--extensionDevelopmentPath=${join(here, "languages")}`,
      `--extensionTestsPath=${join(here, "host.js")}`,
      `--user-data-dir=${join(scratch, "user")}`,
      `--extensions-dir=${join(scratch, "extensions")}`,
      "--disable-extensions",
      "--disable-workspace-trust",
      "--skip-welcome",
      "--skip-release-notes",
      // Chromium slows the timers of a window that is covered or in the background, and the run with them.
      "--disable-renderer-backgrounding",
      "--disable-backgrounding-occluded-windows",
    ],
    { env, stdio: ["ignore", "ignore", "pipe"] },
  );
  let stderr = "";
  child.stderr.on("data", (d) => (stderr += d));
  const timer = setTimeout(() => child.kill("SIGKILL"), 15 * 60_000);
  const status = await new Promise((done) => child.on("exit", done));
  clearTimeout(timer);
  if (!existsSync(job.out)) fail(`VS Code exited with ${status} and wrote no result.\n${stderr.slice(-4000)}`);
  const out = JSON.parse(readFileSync(job.out, "utf8"));
  if (out.error) fail(`The run failed inside VS Code ${out.vscode}:\n${out.error}`);
  return out;
}

/**
 * The TextMate scopes of each file. VS Code has no API that hands an extension the scopes it
 * colours by, so they are read here with the engine VS Code runs (vscode-textmate), its own HTML,
 * CSS and JavaScript grammars from the installed app, and the extension's grammars injected as its
 * package.json says. No Kotlin grammar ships with VS Code: Kotlin is read with test/fixtures/host-kotlin.json.
 */
async function scopes(files) {
  const require = createRequire(join(extension, "package.json"));
  const tm = require("vscode-textmate");
  const oniguruma = require("vscode-oniguruma");
  const wasm = readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm"));
  await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
  const grammars = new Map([["source.kotlin", { path: join(extension, "test/fixtures/host-kotlin.json"), injectTo: [] }]]);
  const builtin = join(code, "../../Resources/app/extensions");
  for (const dir of [...readdirSync(builtin).map((d) => join(builtin, d)), extension]) {
    if (!existsSync(join(dir, "package.json"))) continue;
    for (const g of JSON.parse(readFileSync(join(dir, "package.json"), "utf8")).contributes?.grammars ?? []) {
      if (!grammars.has(g.scopeName)) grammars.set(g.scopeName, { path: join(dir, g.path), injectTo: g.injectTo ?? [] });
    }
  }
  const registry = new tm.Registry({
    onigLib: Promise.resolve({ createOnigScanner: (p) => new oniguruma.OnigScanner(p), createOnigString: (s) => new oniguruma.OnigString(s) }),
    loadGrammar: async (scope) => (grammars.has(scope) ? tm.parseRawGrammar(readFileSync(grammars.get(scope).path, "utf8"), grammars.get(scope).path) : null),
    getInjections: (scope) => [...grammars].filter(([, g]) => g.injectTo.includes(scope)).map(([name]) => name),
  });
  const out = {};
  for (const name of files) {
    const scope = name.endsWith(".kt") ? "source.kotlin" : name.endsWith(".html") ? "text.html.derivative" : null;
    if (!scope) continue;
    const grammar = await registry.loadGrammar(scope);
    let stack = tm.INITIAL;
    out[name] = readFileSync(join(workspace, name), "utf8")
      .split("\n")
      .map((line, i) => {
        const result = grammar.tokenizeLine(line, stack);
        stack = result.ruleStack;
        // The first scope is the file's own, the same on every token.
        return { line: i + 1, tokens: result.tokens.map((t) => [line.slice(t.startIndex, t.endIndex), t.scopes.slice(1).join(" ")]) };
      })
      .filter((l) => l.tokens.some(([text]) => text.trim() !== ""));
  }
  return out;
}

/** One line of JSON, keys sorted, with the record's kind and place first. */
function line(value) {
  const first = ["what", "at", "n"];
  const write = (v) => {
    if (v === null || typeof v !== "object") return JSON.stringify(v);
    if (Array.isArray(v)) return `[${v.map((x) => write(x ?? null)).join(",")}]`;
    const keys = Object.keys(v)
      .filter((k) => v[k] !== undefined)
      .sort((a, b) => (first.indexOf(a) + 1 || 9) - (first.indexOf(b) + 1 || 9) || (a < b ? -1 : a > b ? 1 : 0));
    return `{${keys.map((k) => `${JSON.stringify(k)}:${write(v[k])}`).join(",")}}`;
  };
  return write(value);
}

/**
 * The snapshot as files of one record per line: `<file>.jsonl` for each file of the workspace, and
 * `values.jsonl` with the long texts many records share, a hover's documentation or a file after
 * a fix, each held once and named by its hash where it is used.
 */
function render(out, tokens) {
  const values = new Map();
  const keep = (v) => {
    const id = createHash("sha256").update(v).digest("hex").slice(0, 12);
    values.set(id, v);
    return id;
  };
  const short = (v) => {
    if (typeof v === "string" && v.length > 200) return { value: keep(v) };
    if (Array.isArray(v)) return v.map(short);
    if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, short(x)]));
    return v;
  };
  const rendered = new Map();
  for (const [name, f] of Object.entries(out.files)) {
    const lines = [line({ what: "file", language: f.language })];
    const add = (what, list) => list.forEach((x) => lines.push(line({ what, ...short(x) })));
    // A diagnostic's message is read whole where it stands.
    f.diagnostics.forEach((d) => lines.push(line({ what: "diagnostic", ...d })));
    add("action", f.actions);
    for (const what of ["hover", "signature", "definition", "references", "rename"]) add(what, f[what]);
    add("renamed", f.renames);
    for (const c of f.completions) {
      const { items, ...where } = c;
      // An item is offered in many places: each is held once among the values, and named here by its label and hash.
      // What it replaces follows the word at the caret, so that is said here and the item is the same wherever it is offered.
      const entry = ({ range, ...item }) => `${item.label} ${keep(line(item))} ${typeof range === "object" ? `${range.inserting} ${range.replacing}` : (range ?? "")}`.trimEnd();
      lines.push(line({ what: "completions", ...where, items: items.map(entry) }));
    }
    add("lens", f.lenses);
    add("link", f.links);
    add("color", f.colors);
    for (const l of tokens[name] ?? []) lines.push(line({ what: "scopes", ...l }));
    rendered.set(`${name}.jsonl`, lines.join("\n") + "\n");
  }
  rendered.set("values.jsonl", [...values].sort(([a], [b]) => (a < b ? -1 : 1)).map(([id, value]) => line({ id, value })).join("\n") + "\n");
  rendered.set("commands.jsonl", out.commands.map((c) => line({ what: "command", command: c })).join("\n") + "\n");
  return rendered;
}

/** What a record is of and where, which two records of the same place share. */
const place = (l) => /^\{"what":"[^"]*"(,"at":"[^"]*")?(,"n":\d+)?|^\{"id":"[^"]*"/.exec(l)?.[0] ?? l;

/** For two records of the same place, the entries of each list that only one of them has: a completion list is too long to compare by eye. */
function lists(was, now) {
  const a = JSON.parse(was);
  const b = JSON.parse(now);
  const out = [];
  for (const key of Object.keys(a)) {
    if (!Array.isArray(a[key]) || !Array.isArray(b[key]) || a[key].length < 8) continue;
    const text = (x) => JSON.stringify(x);
    const inA = new Set(a[key].map(text));
    const inB = new Set(b[key].map(text));
    out.push(...[...inA].filter((x) => !inB.has(x)).map((x) => `    - ${key}: ${x}`), ...[...inB].filter((x) => !inA.has(x)).map((x) => `    + ${key}: ${x}`));
  }
  return out;
}

/** What differs between the committed snapshot and this run, a file at a time: the lines only one of them has. */
function differences(rendered) {
  const report = [];
  const old = existsSync(snapshot) ? walk(snapshot).map((f) => relative(snapshot, f)).filter((f) => f.endsWith(".jsonl")) : [];
  const clip = (l) => (l.length > 600 ? `${l.slice(0, 600)}… (${l.length} characters)` : l);
  for (const name of [...new Set([...old, ...rendered.keys()])].sort()) {
    const was = old.includes(name) ? readFileSync(join(snapshot, name), "utf8") : null;
    const now = rendered.get(name) ?? null;
    if (was === now) continue;
    if (was === null || now === null) {
      report.push(`${was === null ? "+++ only in this run" : "--- only in the snapshot"}: ${name}`);
      continue;
    }
    const a = was.split("\n");
    const b = now.split("\n");
    const inA = new Set(a);
    const inB = new Set(b);
    report.push(`=== ${name}`);
    const gone = a.map((l, i) => [i + 1, l]).filter(([, l]) => !inB.has(l));
    const come = b.map((l, i) => [i + 1, l]).filter(([, l]) => !inA.has(l));
    for (const [i, l] of gone) {
      const twin = come.find(([, x]) => place(x) === place(l));
      report.push(`- ${i}: ${clip(l)}`);
      if (twin) report.push(`+ ${twin[0]}: ${clip(twin[1])}`, ...lists(l, twin[1]));
    }
    for (const [i, l] of come) if (!gone.some(([, x]) => place(x) === place(l))) report.push(`+ ${i}: ${clip(l)}`);
    if (a.every((l) => inB.has(l)) && b.every((l) => inA.has(l))) report.push("  the same lines in another order or number");
  }
  return report;
}

const started = Date.now();
const out = await runEditor();
const rendered = render(out, await scopes(Object.keys(out.files)));
const seconds = Math.round((Date.now() - started) / 1000);
const about = `VS Code ${out.vscode}, Streamlord ${out.extension}, ${Object.keys(out.files).length} files, ${seconds} s`;
if (check) {
  const report = differences(rendered);
  if (report.length > 0) {
    const meta = JSON.parse(readFileSync(join(snapshot, "meta.json"), "utf8"));
    console.error(report.join("\n"));
    fail(`\nThe editor does not show what the snapshot holds (${about}; the snapshot was recorded with VS Code ${meta.vscode}).`);
  }
  console.log(`The editor shows what the snapshot holds (${about}).`);
} else {
  rmSync(snapshot, { recursive: true, force: true });
  for (const [name, content] of rendered) {
    mkdirSync(dirname(join(snapshot, name)), { recursive: true });
    writeFileSync(join(snapshot, name), content);
  }
  // Not compared: a newer VS Code is named when a check fails, and is no difference by itself.
  writeFileSync(join(snapshot, "meta.json"), JSON.stringify({ vscode: out.vscode }, null, 2) + "\n");
  console.log(`Recorded ${rendered.size} snapshot files in ${relative(process.cwd(), snapshot)} (${about}).`);
}
