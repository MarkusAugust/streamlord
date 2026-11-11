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
  "streamlord.kotlin.dsl.injection": read("syntaxes/kotlin-dsl-injection.tmLanguage.json"),
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
    getInjections: (scope: string) => (scope === "source.kotlin" ? ["streamlord.kotlin.injection", "streamlord.kotlin.dsl.injection", "streamlord.html.injection"] : scope === "text.html.basic" ? ["streamlord.html.injection"] : undefined),
  });
});

/** The DSL's import, after which a file's DSL calls are Streamlord's. */
const DSL_IMPORT = "import io.github.markusaugust.streamlord.html.*";

/** The tokens of `line`; a Kotlin line is read in a file that imports the DSL, unless `withDsl` is false. */
async function tokens(scope: string, line: string, withDsl = true): Promise<{ text: string; scopes: string[] }[]> {
  if (scope === "source.kotlin" && withDsl) return (await tokenLines(scope, [DSL_IMPORT, line]))[1]!;
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
  // A file calls the DSL's top-level functions only through its import, so without one a DSL-named call is somebody else's.
  it("colours a DSL call only in a file that imports the DSL, and HTML in any file", async () => {
    const call = `dataText("\\$count + 1")`;
    const embedded = (t: { text: string; scopes: string[] }[]) => t.some((x) => x.scopes.includes("meta.embedded.inline.datastar"));
    assert.ok(embedded(await tokens("source.kotlin", call)), "with the import");
    assert.ok(!embedded(await tokens("source.kotlin", call, false)), "without it");
    const inPackage = await tokenLines("source.kotlin", ["package io.github.markusaugust.streamlord.html", call]);
    assert.ok(embedded(inPackage[1]!), "in the DSL's own package");
    const html = await tokens("source.kotlin", `patchElements("""<div data-text="$count"></div>""")`, false);
    assert.match(scopeOf(html, "count"), /variable\.other\.constant\.signal\.datastar/);
    // An import inside a comment or a raw string opens nothing, and a name that only starts as the package is another.
    for (const [why, lines] of [
      ["in a block comment", ["/*", DSL_IMPORT, "*/", call]],
      ["in a raw string", ['val sample = """', DSL_IMPORT, '"""', call]],
      ["another package", ["package io.github.markusaugust.streamlord.htmlx", call]],
    ] as const) {
      const t = await tokenLines("source.kotlin", [...lines]);
      const last = t[t.length - 1]!;
      assert.ok(!embedded(last), why);
      // Opened there, the region would never let the comment or the string close.
      assert.ok(last.every((x) => !x.scopes.some((s) => s.startsWith("comment.block") || s.startsWith("string.quoted.triple"))), `${why}: closed`);
    }
  });

  // kotlinx.html's own form is Datastar in every file, as an attribute in HTML is.
  it("colours kotlinx.html's attributes form with and without the import", async () => {
    const line = `button { attributes["data-on:click__debounce.500ms"] = "\\$count++; @post('/x')" }`;
    for (const withDsl of [false, true]) {
      const t = await tokens("source.kotlin", line, withDsl);
      assert.match(scopeOf(t, "on"), /entity\.other\.attribute-name\.datastar/, `name, import ${withDsl}`);
      assert.match(scopeOf(t, "debounce"), /datastar/, `modifier, import ${withDsl}`);
      assert.match(scopeOf(t, "post"), /entity\.name\.function\.action\.backend\.datastar/, `value, import ${withDsl}`);
      assert.match(scopeOf(t, "count"), /meta\.embedded\.inline\.datastar/, `signal, import ${withDsl}`);
    }
    const embedded = (t: { text: string; scopes: string[] }[]) => t.some((x) => x.scopes.includes("meta.embedded.inline.datastar"));
    // Only a value the diagnostics judge: an expression attribute's, the whole statement, in one double-quoted literal.
    for (const [why, source] of [
      ["a signal name", `input { attributes["data-bind"] = "username" }`],
      ["a nonce", `script { attributes["data-nonce"] = "r4nd0m-ABC" }`],
      ["joined to more", `span { attributes["data-text"] = "\\$a + " + b }`],
      ["a raw string", `button { attributes["data-on:click"] = """@post('/x')""" }`],
    ] as const) {
      const t = await tokens("source.kotlin", source, false);
      assert.ok(!embedded(t), why);
      assert.ok(t.some((x) => x.scopes.some((s) => s.startsWith("entity.other.attribute-name.datastar"))), `${why}: the name`);
    }
    assert.ok(embedded(await tokens("source.kotlin", `span { attributes["data-text"] = "\\$a"; x() }`, false)), "followed by another statement");
    const assigned = await tokens("source.kotlin", `span { attributes["data-text"] = "\\$a" }`, false);
    assert.match(scopeOf(assigned, "="), /keyword\.operator\.assignment\.kotlin/, "the assignment keeps its colour");
    assert.ok(!(await tokens("source.kotlin", `div { attributes["data-text_x"] = "y" }`, false)).some((x) => x.scopes.some((s) => s.includes("datastar"))), "one underscore is no modifier");
    // A raw string is Kotlin's to colour, in a DSL call too: taking its first quote for a literal's mispaired the rest.
    for (const source of [`dataText("""\\$x""")`, `dataOn("click", """\\$x""")`]) {
      const t = await tokenLines("source.kotlin", [DSL_IMPORT, source, "val y = 2"]);
      assert.ok(!embedded(t[1]!), source);
      assert.ok(t[2]!.every((x) => !x.scopes.some((s) => s.startsWith("string"))), `${source}: the next line is code`);
    }
    const named = await tokens("source.kotlin", `div { attributes["data-text"] = handler }`, false);
    assert.match(scopeOf(named, "text"), /entity\.other\.attribute-name\.datastar/, "a key whose value is no literal");
    // A data-* attribute of the page's own, or a typo, is no Datastar attribute.
    for (const other of [`div { attributes["data-color"] = "success" }`, `div { attributes["data-onn:click"] = "x" }`]) {
      const t = await tokens("source.kotlin", other, false);
      assert.ok(t.every((x) => !x.scopes.some((s) => s.includes("datastar"))), other);
    }
  });

  it("knows exactly the catalog's attributes, and its expression attributes, in the attributes form", () => {
    const catalog = JSON.parse(readFileSync(new URL("../../catalog/datastar-1.0.5.json", root), "utf8")) as { attributes: { name: string; valueKind: string }[] };
    const grammar = JSON.parse(readFileSync(new URL("syntaxes/kotlin-injection.tmLanguage.json", root), "utf8"));
    const [value, name] = grammar.repository["attributes-index"].patterns as [{ begin: string }, { match: string }];
    // The alternation of names: the group after data-(?:star-)?.
    const names = (pattern: string) => /\(\?:((?:[a-z-]+\|)+[a-z-]+)\)/.exec(pattern)?.[1]?.split("|").sort() ?? [];
    const of = (kinds: (k: string) => boolean) => [...new Set(catalog.attributes.filter((a) => kinds(a.valueKind)).map((a) => a.name))].sort();
    assert.deepEqual(names(name.match), of(() => true));
    assert.deepEqual(names(value.begin), of((k) => k === "expression"));
    assert.ok(names(value.begin).length > 10 && names(name.match).length > names(value.begin).length);
  });

  it("leaves a DSL call written in a string alone, and keeps HTML and scripts, in a file with the import", async () => {
    const embedded = (t: { text: string; scopes: string[] }[], scope: string) => t.some((x) => x.scopes.includes(scope));
    assert.ok(!embedded(await tokens("source.kotlin", `val s = """dataText("\\$x")"""`), "meta.embedded.inline.datastar"), "a call in a string");
    assert.ok(!embedded(await tokens("source.kotlin", `/* dataText("\\$x") */`), "meta.embedded.inline.datastar"), "a call in a comment");
    // Markup that does not open with a tag is HTML only as patchElements' argument.
    assert.ok(embedded(await tokens("source.kotlin", `patchElements("""Hi <b data-text="$count"></b>""")`), "meta.embedded.block.html"), "html");
    assert.ok(embedded(await tokens("source.kotlin", `executeScript("""console.log(1)""")`), "meta.embedded.block.javascript"), "script");
  });

  it("opens the file's DSL region once, and adds nothing to Kotlin's own scopes", async () => {
    const lines = await tokenLines("source.kotlin", [DSL_IMPORT, "import io.github.markusaugust.streamlord.html.signal", "val x = 1"]);
    for (const line of lines) for (const t of line) assert.equal(t.scopes.filter((s) => s === "meta.dsl-file.streamlord").length, 1, t.text);
    const bare = await tokens("source.kotlin", "val x = 1", false);
    assert.deepEqual(lines[2]!.map((t) => t.scopes.filter((s) => s !== "meta.dsl-file.streamlord")), bare.map((t) => t.scopes));
  });

  // The client reads a signal as \$(\w+(?:[.-]\w+)*) (engine.ts): digits and hyphens are part of the name.
  it("reads a signal as far as the client does", async () => {
    const t = await tokens("source.kotlin", `dataText("$123 + $foo-bar + $items.0.name")`);
    assert.match(scopeOf(t, "123"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, "foo-bar"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, "items"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, ".0.name"), /variable\.other\.property\.signal-path\.datastar/);
  });

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
    const param = await tokenLines("source.kotlin", [`fun wrap(@Language("HTML") html: String, title: String) {`, `    val x = "not <html>"`]);
    assert.ok(param[1]!.every((x) => !x.scopes.some((s) => s.includes("html"))), "a parameter annotation does not reach the body");
    const withDefault = await tokenLines("source.kotlin", [`fun wrap(@Language("HTML") html: String? = null, title: String) {`, `    val x = "not <b>"`]);
    assert.ok(withDefault[1]!.every((x) => !x.scopes.some((s) => s.includes("entity.name.tag"))), "nor with a default value");
    const onFunction = await tokenLines("source.kotlin", [`@Language("HTML")`, `fun greeting(name: String): String = """`, `Hello <b data-onn:click="x()">$name</b>`, `"""`]);
    assert.match(scopeOf(onFunction[2]!, "b"), /entity\.name\.tag\.html/, "a function's own parameters do not cancel the marker");
    const paramDefault = await tokens("source.kotlin", `@Language("HTML") fun row(label: String = "Untitled"): String = """<tr data-text="$x"></tr>"""`);
    assert.ok(!scopeOf(paramDefault, "Untitled").includes("html"), "a parameter default before the literal stays a string");
    assert.match(scopeOf(paramDefault, "tr"), /entity\.name\.tag\.html/);
    assert.match(scopeOf(paramDefault, "text"), /entity\.other\.attribute-name\.datastar\.plugin/);
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

  it("leaves a data-* attribute of the page's own, or a typo, to HTML", async () => {
    const others = [`<div data-color="success">`, `<div data-onn:click="x()">`, `<div data-tooltip>`, `<div data-signalsx:a="1">`];
    // A name that starts as one of the catalog's is not one: the check after the name keeps these out.
    others.push(`<div data-ignored>`, `<div data-persistent>`, `<div data-showx>`, `<div data-on-intersects="x()">`);
    for (const other of others) {
      const t = await tokens("text.html.basic", other);
      assert.ok(t.every((x) => !x.scopes.some((s) => s.includes("datastar"))), other);
    }
    const t = await tokens("text.html.basic", `<div data-on-intersect="x()" data-ignore-morph data-show="$open">`);
    assert.match(scopeOf(t, "on-intersect"), /attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(t, "ignore-morph"), /attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(t, "show"), /attribute-name\.datastar\.plugin/);
    // The browser lowercases a name, and so do the checks.
    const upper = await tokens("text.html.basic", `<div DATA-SHOW="$open" data-On:Click="x()">`);
    assert.match(scopeOf(upper, "SHOW"), /attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(upper, "On"), /attribute-name\.datastar\.plugin/);
  });

  it("reads only the value of an attribute that takes an expression as one", async () => {
    const t = await tokens("text.html.basic", `<input data-bind="user.name" data-show="$open">`);
    assert.match(scopeOf(t, "bind"), /attribute-name\.datastar\.plugin/);
    assert.ok(!scopeOf(t, "user.name").includes("datastar"), scopeOf(t, "user.name"));
    assert.match(scopeOf(t, "user.name"), /string\.quoted\.double\.html/);
    assert.ok(t.some((x) => x.text.includes("open") && x.scopes.includes("meta.embedded.inline.datastar")));
  });

  it("reads a value in single quotes, and one in escaped quotes in a plain Kotlin string", async () => {
    const html = await tokens("text.html.basic", `<div data-show='$open' data-signals='{"a": 1}' data-bind='name' class="x">`);
    assert.ok(html.some((x) => x.text.includes("open") && x.scopes.includes("meta.embedded.inline.datastar")), "single quotes");
    assert.ok(html.some((x) => x.text.includes("a") && x.scopes.includes("meta.embedded.inline.datastar")), "JSON in single quotes");
    assert.match(scopeOf(html, "bind"), /attribute-name\.datastar\.plugin/);
    assert.ok(html.some((x) => x.text === "name" && !x.scopes.some((s) => s.includes("datastar"))), "a plain value keeps HTML's string");
    assert.match(scopeOf(html, "class"), /entity\.other\.attribute-name\.html/);
    const kt = await tokens("source.kotlin", `val h = "<div data-show=\\"\\$open\\" data-text='\\$count' data-persist id=\\"a\\">"`, false);
    assert.match(scopeOf(kt, "open"), /signal\.datastar/);
    assert.match(scopeOf(kt, "count"), /signal\.datastar/);
    // A Kotlin `\$` is an escape before the signal; the value ends at its escaped quote.
    assert.ok(kt.some((x) => x.text === "\\" && x.scopes.includes("constant.character.escape.kotlin")));
    assert.ok(kt.filter((x) => x.text === `\\"` && x.scopes.includes("punctuation.definition.string.end.html")).length >= 1);
    assert.match(scopeOf(kt, "persist"), /attribute-name\.datastar\.plugin/);
    // The Kotlin string still ends where it did.
    assert.match(kt[kt.length - 1]!.scopes.join(" "), /punctuation\.definition\.string\.end\.kotlin/);
    // JSON keys in escaped quotes inside a single-quoted value close where they open, and so does the Kotlin string.
    const json = await tokenLines("source.kotlin", [`val h = "<div data-signals='{\\"a\\": 1}'></div>"`, "val after = 1"]);
    assert.match(json[0]![json[0]!.length - 1]!.scopes.join(" "), /punctuation\.definition\.string\.end\.kotlin/);
    assert.ok(json[1]!.every((x) => !x.scopes.some((s) => s.includes("datastar") || s.includes("html"))), "the next line is Kotlin");
    // A Kotlin string that ends inside an escaped value ends the value too. The open tag runs on, as HTML's own grammar has it.
    const cut = await tokenLines("source.kotlin", [`val h = "<div data-show=\\"\\$a" + x`, "val after = 1"]);
    assert.ok(cut[1]!.every((x) => !x.scopes.some((s) => s.includes("datastar"))), "the line after a cut value is no Datastar");
  });

  it("colours the same in an HTML string in Kotlin", async () => {
    const t = await tokens("source.kotlin", `val h = """<div data-color="x" data-show="$a"></div>"""`);
    assert.ok(!t.some((x) => x.text.includes("color") && x.scopes.some((s) => s.includes("datastar"))));
    assert.match(scopeOf(t, "show"), /attribute-name\.datastar\.plugin/);
  });

  it("knows exactly the catalog's attributes", () => {
    const catalog = JSON.parse(readFileSync(new URL("../../catalog/datastar-1.0.5.json", root), "utf8")) as { attributes: { name: string; valueKind: string }[] };
    const grammar = JSON.parse(readFileSync(new URL("syntaxes/html-injection.tmLanguage.json", root), "utf8"));
    const names = (pattern: string) => /\(\?:((?:[a-z-]+\|)*[a-z-]+)\)\)/.exec(pattern)?.[1]?.split("|").sort() ?? [];
    const of = (kinds: (k: string) => boolean) => [...new Set(catalog.attributes.filter((a) => kinds(a.valueKind)).map((a) => a.name))].sort();
    assert.deepEqual(names(grammar.repository["datastar-attribute-with-value"].begin), of((k) => k === "expression"));
    assert.deepEqual(names(grammar.repository["datastar-attribute-with-plain-value"].begin), of((k) => k !== "expression"));
    assert.deepEqual(names(grammar.repository["datastar-attribute-bare"].match), of(() => true));
  });
});
