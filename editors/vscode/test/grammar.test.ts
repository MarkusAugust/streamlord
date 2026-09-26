import { describe, it, before } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import type * as textmate from "vscode-textmate";

/**
 * Tokenizes samples through the real TextMate engine with our grammars injected into minimal
 * host grammars, and asserts the scopes that carry colors.
 */

const require = createRequire(import.meta.url);
// Both packages are CommonJS/UMD; load them through require so their exports resolve.
const tm: typeof textmate = require("vscode-textmate");
const oniguruma: typeof import("vscode-oniguruma") = require("vscode-oniguruma");
const root = new URL("../", import.meta.url);
const read = (rel: string) => JSON.parse(readFileSync(new URL(rel, root), "utf8")) as textmate.IRawGrammar;

const grammars: Record<string, textmate.IRawGrammar> = {
  "source.kotlin": read("test/fixtures/host-kotlin.json"),
  "text.html.basic": read("test/fixtures/host-html.json"),
  "source.datastar": read("syntaxes/datastar-expression.tmLanguage.json"),
  "streamlord.kotlin.injection": read("syntaxes/kotlin-injection.tmLanguage.json"),
  "streamlord.html.injection": read("syntaxes/html-injection.tmLanguage.json"),
};

let registry: textmate.Registry;

before(async () => {
  const wasm = readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm"));
  await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength) as ArrayBuffer);
  registry = new tm.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (patterns: string[]) => new oniguruma.OnigScanner(patterns),
      createOnigString: (s: string) => new oniguruma.OnigString(s),
    }),
    loadGrammar: async (scope: string) => grammars[scope] ?? null,
    getInjections: (scope: string) => (scope === "source.kotlin" ? ["streamlord.kotlin.injection", "streamlord.html.injection"] : scope === "text.html.basic" ? ["streamlord.html.injection"] : undefined),
  });
});

async function tokens(scope: string, line: string): Promise<{ text: string; scopes: string[] }[]> {
  const grammar = await registry.loadGrammar(scope);
  assert.ok(grammar);
  const result = grammar.tokenizeLine(line, tm.INITIAL);
  return result.tokens.map((t) => ({ text: line.slice(t.startIndex, t.endIndex), scopes: t.scopes }));
}

const scopeOf = (toks: { text: string; scopes: string[] }[], text: string) => toks.find((t) => t.text === text)?.scopes.join(" ") ?? `<no token '${text}'>`;

/** Tokenize several lines in sequence, carrying the rule stack, and return the tokens of each line. */
async function tokenLines(scope: string, lines: string[]): Promise<{ text: string; scopes: string[] }[][]> {
  const grammar = await registry.loadGrammar(scope);
  assert.ok(grammar);
  let stack = tm.INITIAL;
  const out: { text: string; scopes: string[] }[][] = [];
  for (const line of lines) {
    const result = grammar.tokenizeLine(line, stack);
    out.push(result.tokens.map((t) => ({ text: line.slice(t.startIndex, t.endIndex), scopes: t.scopes })));
    stack = result.ruleStack;
  }
  return out;
}

describe("kotlin injection grammar", () => {
  it("highlights datastar tokens inside dsl strings", async () => {
    const t = await tokens("source.kotlin", `dataOnClick("$count++; @post('/x', {contentType: 'form', retry: 'never'})")`);
    assert.match(scopeOf(t, "count"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, "$"), /punctuation\.definition\.signal\.datastar/);
    assert.match(scopeOf(t, "post"), /entity\.name\.function\.action\.backend\.datastar/);
    assert.match(scopeOf(t, "@"), /punctuation\.definition\.action\.datastar/);
    assert.match(scopeOf(t, "contentType"), /support\.type\.property-name\.option\.datastar/);
    assert.match(scopeOf(t, "++"), /keyword\.operator\.increment\.datastar/);
    assert.match(scopeOf(t, "/x"), /string\.quoted\.single\.datastar/);
    assert.ok(t.every((x) => !x.scopes.includes("markup.underline.link.datastar")), "no url underline");
  });

  it("highlights the second argument of dataOn and keeps plain kotlin strings alone", async () => {
    const t = await tokens("source.kotlin", `dataOn("keydown", "evt.key === 'Escape' && (\\$open = false)")`);
    assert.match(scopeOf(t, "evt"), /variable\.language\.scope\.datastar/);
    assert.match(scopeOf(t, "key"), /variable\.other\.property\.datastar/);
    assert.match(scopeOf(t, "false"), /constant\.language\.datastar/);
    assert.match(scopeOf(t, '"keydown"'), /string\.quoted\.double\.kotlin/);
    assert.ok(!scopeOf(t, '"keydown"').includes("datastar"), "the event name is not an expression");
    const plain = await tokens("source.kotlin", `println("$count @post(x)")`);
    assert.ok(plain.every((x) => !x.scopes.some((s) => s.includes("datastar"))), "no injection into unrelated strings");
  });

  it("highlights html and data-* attributes inside patchElements strings", async () => {
    const t = await tokens("source.kotlin", `s.patchElements("""<div id="a" data-on:click__debounce.500ms.leading="$n++"></div>""")`);
    assert.match(scopeOf(t, "data-"), /entity\.other\.attribute-name\.datastar\.prefix/);
    assert.match(scopeOf(t, "on"), /entity\.other\.attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(t, "click"), /entity\.other\.attribute-name\.datastar\.key/);
    assert.match(scopeOf(t, "debounce"), /keyword\.other\.modifier\.datastar/);
    assert.match(scopeOf(t, ".500ms"), /constant\.numeric\.duration\.datastar/);
    assert.match(scopeOf(t, ".leading"), /constant\.other\.modifier-arg\.datastar/);
    assert.match(scopeOf(t, "n"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, "div"), /entity\.name\.tag\.html/);
  });

  it("accepts a multi-dollar prefix on every kind of string", async () => {
    const call = await tokens("source.kotlin", `s.patchElements($$"""<div data-text="$n"></div>""")`);
    assert.match(scopeOf(call, "div"), /entity\.name\.tag\.html/);
    assert.match(scopeOf(call, "n"), /variable\.other\.constant\.signal\.datastar/);
    const dsl = await tokens("source.kotlin", `dataOnClick($$"$count++")`);
    assert.match(scopeOf(dsl, "count"), /variable\.other\.constant\.signal\.datastar/);
    const free = await tokens("source.kotlin", `val row = $$"""<li data-show="$open"></li>"""`);
    assert.match(scopeOf(free, "show"), /entity\.other\.attribute-name\.datastar\.plugin/);
  });

  it("highlights html in strings that open with a tag, wherever they are", async () => {
    const t = await tokens("source.kotlin", `val row = """<li data-on:click="$n++">x</li>""" + "<b data-ignore>"`);
    assert.match(scopeOf(t, "li"), /entity\.name\.tag\.html/);
    assert.match(scopeOf(t, "click"), /entity\.other\.attribute-name\.datastar\.key/);
    assert.match(scopeOf(t, "b"), /entity\.name\.tag\.html/);
    assert.match(scopeOf(t, "ignore"), /entity\.other\.attribute-name\.datastar\.plugin/);
    const plain = await tokens("source.kotlin", `val sql = """select * from x where a < b"""`);
    assert.ok(plain.every((x) => !x.scopes.some((s) => s.includes("html") || s.includes("datastar"))), "no html in a query");
  });

  it("follows the injection marker across lines", async () => {
    for (const marker of [`@Language("HTML")`, `// language=HTML`]) {
      const lines = await tokenLines("source.kotlin", [marker, `fun page(): String = """`, `  <div data-text="$count" class="x">`, `"""`, `val after = """`, `  <p>not marked, not on the first line</p>`, `"""`]);
      assert.match(scopeOf(lines[2]!, "div"), /entity\.name\.tag\.html/, marker);
      assert.match(scopeOf(lines[2]!, "text"), /entity\.other\.attribute-name\.datastar\.plugin/, marker);
      assert.match(scopeOf(lines[2]!, "class"), /entity\.other\.attribute-name\.html/, marker);
      assert.match(scopeOf(lines[3]!, `"""`), /string\.quoted\.triple\.kotlin/, marker);
      assert.ok(lines[5]!.every((x) => !x.scopes.some((s) => s.includes("html"))), `${marker}: the marker reaches one string only`);
    }
  });
});

describe("html injection grammar", () => {
  it("highlights data-* attributes and their expressions in html files", async () => {
    const t = await tokens("text.html.basic", `<form data-on:submit__prevent="@post('/save')" data-persist class="x">`);
    assert.match(scopeOf(t, "submit"), /entity\.other\.attribute-name\.datastar\.key/);
    assert.match(scopeOf(t, "prevent"), /keyword\.other\.modifier\.datastar/);
    assert.match(scopeOf(t, "post"), /entity\.name\.function\.action\.backend\.datastar/);
    assert.match(scopeOf(t, "persist"), /entity\.other\.attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(t, "class"), /entity\.other\.attribute-name\.html/);
    assert.ok(!scopeOf(t, "class").includes("datastar"));
  });

  it("supports the aliased prefix", async () => {
    const t = await tokens("text.html.basic", `<div data-star-on-intersect__threshold.50="x()">`);
    assert.match(scopeOf(t, "data-star-"), /attribute-name\.datastar\.prefix/);
    assert.match(scopeOf(t, "on-intersect"), /attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(t, ".50"), /constant\.numeric\.datastar/);
  });
});
