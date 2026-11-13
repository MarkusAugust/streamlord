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
  "streamlord.kotlin.template.injection": read("syntaxes/kotlin-template-injection.tmLanguage.json"),
  "streamlord.kotlin.template2.injection": read("syntaxes/kotlin-template2-injection.tmLanguage.json"),
  // VS Code's own Handlebars grammar, which brings its own tags; a template language that wraps HTML as jte's does.
  "text.html.handlebars": read("test/fixtures/host-handlebars.json"),
  // The grammars of maj2c.jte-template-syntax-highlight (.jte and .kte) and dawhite.mustache, as they ship; see host-grammars-LICENSES.txt.
  "text.html.jte": read("test/fixtures/host-jte.json"),
  // VS Code's own Java grammar, which the jte grammar's placeholders hold; an empty stand-in here.
  "source.java": { scopeName: "source.java", patterns: [] } as unknown as textmate.IRawGrammar,
  "source.mustache": tm.parseRawGrammar(readFileSync(new URL("test/fixtures/host-mustache.tmLanguage", root), "utf8"), "host-mustache.tmLanguage"),
  "streamlord.placeholders.jte.injection": read("syntaxes/placeholders-jte-injection.tmLanguage.json"),
  "streamlord.placeholders.handlebars.injection": read("syntaxes/placeholders-handlebars-injection.tmLanguage.json"),
  "streamlord.placeholders.mustache.injection": read("syntaxes/placeholders-mustache-injection.tmLanguage.json"),
  "streamlord.html.element.injection": read("syntaxes/html-element-injection.tmLanguage.json"),
  "streamlord.kotlin.template3.injection": read("syntaxes/kotlin-template3-injection.tmLanguage.json"),
  "text.html.derivative": { scopeName: "text.html.derivative", patterns: [{ include: "text.html.basic" }] } as unknown as textmate.IRawGrammar,
};

/** The extension's grammars, injected where its package.json says, as VS Code injects them. */
const contributed = (JSON.parse(readFileSync(new URL("package.json", root), "utf8")) as { contributes: { grammars: { scopeName: string; injectTo?: string[] }[] } }).contributes.grammars;

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
    getInjections: (scope: string) => contributed.filter((g) => g.injectTo?.includes(scope)).map((g) => g.scopeName),
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
    const html = await tokens("source.kotlin", `patchElements($$"""<div data-text="$count"></div>""")`, false);
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
    // A raw string in a DSL call is the expression too, and closes where it does: taking its first quote for a literal's mispaired the rest.
    for (const source of [`dataText($$"""$x""")`, `dataOn("click", $$"""$x""")`]) {
      const t = await tokenLines("source.kotlin", [DSL_IMPORT, source, "val y = 2"]);
      assert.ok(embedded(t[1]!), source);
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
    const t = await tokens("source.kotlin", `dataText($$"$123 + $foo-bar + $items.0.name")`);
    assert.match(scopeOf(t, "123"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, "foo-bar"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, "items"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, ".0.name"), /variable\.other\.property\.signal-path\.datastar/);
  });

  it("highlights datastar tokens inside dsl strings", async () => {
    const t = await tokens("source.kotlin", `dataOnClick("\\$count++; @post('/x', {contentType: 'form', retry: 'never'})")`);
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
    const t = await tokens("source.kotlin", `s.patchElements($$"""<div id="a" data-on:click__debounce.500ms.leading="$n++"></div>""")`);
    assert.match(scopeOf(t, "data-"), /entity\.other\.attribute-name\.datastar\.prefix/);
    assert.match(scopeOf(t, "on"), /entity\.other\.attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(t, "click"), /entity\.other\.attribute-name\.datastar\.key/);
    assert.match(scopeOf(t, "debounce"), /keyword\.other\.modifier\.datastar/);
    assert.match(scopeOf(t, "500ms"), /constant\.numeric\.duration\.datastar/);
    assert.match(scopeOf(t, "leading"), /variable\.other\.enummember\.modifier-arg\.datastar/);
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
      // The second string's first line opens no tag, so it is no HTML: only the marker could make it one.
      const lines = await tokenLines("source.kotlin", [marker, `fun page(): String = """`, `  <div data-text="$count" class="x">`, `"""`, `val after = """`, `  not marked, <p>not at the start</p>`, `"""`]);
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
    assert.match(scopeOf(t, "50"), /constant\.numeric\.datastar/);
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
    // A signal's name, as the client reads it, not an expression.
    assert.match(scopeOf(t, "user"), /variable\.other\.constant\.signal\.datastar/);
    assert.match(scopeOf(t, ".name"), /variable\.other\.property\.signal-path\.datastar/);
    assert.ok(!scopeOf(t, "user").includes("meta.embedded.inline.datastar"), scopeOf(t, "user"));
    assert.ok(t.some((x) => x.text.includes("open") && x.scopes.includes("meta.embedded.inline.datastar")));
  });

  it("reads a value in single quotes, and one in escaped quotes in a plain Kotlin string", async () => {
    const html = await tokens("text.html.basic", `<div data-show='$open' data-signals='{"a": 1}' data-bind='name' class="x">`);
    assert.ok(html.some((x) => x.text.includes("open") && x.scopes.includes("meta.embedded.inline.datastar")), "single quotes");
    assert.ok(html.some((x) => x.text.includes("a") && x.scopes.includes("meta.embedded.inline.datastar")), "JSON in single quotes");
    assert.match(scopeOf(html, "bind"), /attribute-name\.datastar\.plugin/);
    assert.match(scopeOf(html, "name"), /variable\.other\.constant\.signal\.datastar/, "a signal name in single quotes");
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
    // A filter, {include: /x/}, is an object the client evaluates; a signal attribute names a signal.
    assert.deepEqual(names(grammar.repository["datastar-attribute-with-value"].begin), of((k) => k === "expression" || k === "filter"));
    assert.deepEqual(names(grammar.repository["datastar-attribute-with-unquoted-value"].match), of((k) => k === "expression" || k === "filter"));
    assert.deepEqual(names(grammar.repository["datastar-attribute-with-signal-value"].begin), of((k) => k === "signal"));
    assert.deepEqual(names(grammar.repository["datastar-attribute-with-plain-value"].begin), of((k) => k === "text" || k === "none"));
    assert.deepEqual(names(grammar.repository["datastar-attribute-bare"].match), of(() => true));
  });

  it("knows exactly the catalog's fetch options, and filterSignals' two keys", () => {
    const catalog = JSON.parse(readFileSync(new URL("../../catalog/datastar-1.0.5.json", root), "utf8")) as { fetchOptions: { name: string }[] };
    const grammar = JSON.parse(readFileSync(new URL("syntaxes/datastar-expression.tmLanguage.json", root), "utf8"));
    const keys = /\\b\(([A-Za-z|]+)\)/.exec(grammar.repository["option-keys"].match)?.[1]?.split("|").sort();
    assert.deepEqual(keys, [...catalog.fetchOptions.map((o) => o.name), "include", "exclude"].sort());
  });
});

/** A token's own scopes: everything but the regions it sits in. */
const own = (t: { scopes: string[] }) => t.scopes.filter((s) => !/^(meta|text|source)\./.test(s)).join(" ");

/** Each token that is not white space, with its own scopes. */
async function kinds(scope: string, line: string, withDsl = true): Promise<[string, string][]> {
  return (await tokens(scope, line, withDsl)).filter((t) => t.text.trim() !== "").map((t) => [t.text, own(t)]);
}

/** The tokens of `line` from the first whose text is `from` on, and up to the one whose text is `to`. */
function between(all: [string, string][], from: string, to?: string): [string, string][] {
  const start = all.findIndex(([text]) => text === from);
  assert.ok(start >= 0, `no token '${from}' in ${JSON.stringify(all)}`);
  const rest = all.slice(start);
  const end = to === undefined ? rest.length : rest.findIndex(([text], i) => i > 0 && text === to) + 1;
  assert.ok(end > 0, `no token '${to}'`);
  return rest.slice(0, end);
}

const S = {
  prefix: "entity.other.attribute-name.datastar.prefix",
  plugin: "entity.other.attribute-name.datastar.plugin",
  colon: "entity.other.attribute-name.datastar punctuation.separator.key.datastar",
  key: "entity.other.attribute-name.datastar.key",
  sigil: "entity.other.attribute-name.datastar punctuation.definition.modifier.datastar",
  dot: "entity.other.attribute-name.datastar punctuation.separator.argument.datastar",
  modifier: "keyword.other.modifier.datastar",
  arg: "variable.other.enummember.modifier-arg.datastar",
  number: "constant.numeric.datastar",
  duration: "constant.numeric.duration.datastar",
  dollar: "variable.other.constant.signal.datastar punctuation.definition.signal.datastar",
  signal: "variable.other.constant.signal.datastar",
  path: "variable.other.property.signal-path.datastar",
  at: "entity.name.function.action.datastar punctuation.definition.action.datastar",
  action: "entity.name.function.action.datastar",
  backendAt: "entity.name.function.action.backend.datastar punctuation.definition.action.datastar",
  backend: "entity.name.function.action.backend.datastar",
  identifier: "variable.other.readwrite.datastar",
  property: "variable.other.property.datastar",
  objectKey: "variable.other.property.key.datastar",
  option: "support.type.property-name.option.datastar",
  method: "entity.name.function.method.datastar",
  call: "entity.name.function.datastar",
  global: "support.class.global.datastar",
  accessor: "punctuation.accessor.datastar",
  punctuation: "punctuation.datastar",
  arithmetic: "keyword.operator.arithmetic.datastar",
  assignment: "keyword.operator.assignment.datastar",
  bitwise: "keyword.operator.bitwise.datastar",
  spread: "keyword.operator.spread.datastar",
  arrow: "storage.type.function.arrow.datastar",
  escape: "constant.character.escape.kotlin",
  dollarEscape: "constant.character.escape.dollar.kotlin",
  stringStart: "punctuation.definition.string.begin.html",
  stringEnd: "punctuation.definition.string.end.html",
  ktStart: "punctuation.definition.string.begin.kotlin string.quoted.double.kotlin",
  ktEnd: "punctuation.definition.string.end.kotlin string.quoted.double.kotlin",
  ktRawStart: "punctuation.definition.string.begin.kotlin string.quoted.triple.kotlin",
  ktRawEnd: "punctuation.definition.string.end.kotlin string.quoted.triple.kotlin",
  templateBegin: "punctuation.definition.template-expression.begin.kotlin",
  templateEnd: "punctuation.definition.template-expression.end.kotlin",
  template: "variable.other.readwrite.kotlin",
};

describe("datastar colours, every token exactly", () => {
  it("splits an attribute name, with a modifier after one with an argument", async () => {
    const t = await kinds("text.html.basic", `<div data-on:keydown__case.kebab__window__debounce.500ms__threshold.50="x">`);
    assert.deepEqual(between(t, "data-", "50"), [
      ["data-", S.prefix],
      ["on", S.plugin],
      [":", S.colon],
      ["keydown", S.key],
      ["__", S.sigil],
      ["case", S.modifier],
      [".", S.dot],
      ["kebab", S.arg],
      ["__", S.sigil],
      ["window", S.modifier],
      ["__", S.sigil],
      ["debounce", S.modifier],
      [".", S.dot],
      ["500ms", S.duration],
      ["__", S.sigil],
      ["threshold", S.modifier],
      [".", S.dot],
      ["50", S.number],
    ]);
  });

  it("colours every part of plain JavaScript", async () => {
    const t = await kinds("text.html.basic", `<div data-text="foo = {a: Math.max(x.length), b: go(() => typeof y)}; ...$g; @peek(() => 1)">`);
    assert.deepEqual(between(t, "foo", "1"), [
      ["foo", S.identifier],
      ["=", S.assignment],
      ["{", S.punctuation],
      ["a", S.objectKey],
      [":", "punctuation.separator.key-value.datastar"],
      ["Math", S.global],
      [".", S.accessor],
      ["max", S.method],
      ["(", S.punctuation],
      ["x", S.identifier],
      [".", S.accessor],
      ["length", S.property],
      [")", S.punctuation],
      [",", S.punctuation],
      ["b", S.objectKey],
      [":", "punctuation.separator.key-value.datastar"],
      ["go", S.call],
      ["(", S.punctuation],
      ["(", S.punctuation],
      [")", S.punctuation],
      ["=>", S.arrow],
      ["typeof", "keyword.operator.expression.typeof.datastar"],
      ["y", S.identifier],
      [")", S.punctuation],
      ["}", S.punctuation],
      [";", S.punctuation],
      ["...", S.spread],
      ["$", S.dollar],
      ["g", S.signal],
      [";", S.punctuation],
      ["@", S.at],
      ["peek", S.action],
      ["(", S.punctuation],
      ["(", S.punctuation],
      [")", S.punctuation],
      ["=>", S.arrow],
      ["1", S.number],
    ]);
  });

  it("gives each keyword the scope TypeScript's grammar gives it", async () => {
    const t = await kinds("text.html.basic", `<div data-init="if (x in o) return new A(this); let v = void delete a instanceof B; async function f() { await g }">`);
    const of = (w: string) => t.find(([text]) => text === w)?.[1];
    assert.equal(of("if"), "keyword.control.conditional.datastar");
    assert.equal(of("return"), "keyword.control.flow.datastar");
    assert.equal(of("await"), "keyword.control.flow.datastar");
    assert.equal(of("in"), "keyword.operator.expression.in.datastar");
    assert.equal(of("instanceof"), "keyword.operator.expression.instanceof.datastar");
    assert.equal(of("void"), "keyword.operator.expression.void.datastar");
    assert.equal(of("delete"), "keyword.operator.expression.delete.datastar");
    assert.equal(of("new"), "keyword.operator.new.datastar");
    assert.equal(of("this"), "variable.language.this.datastar");
    assert.equal(of("let"), "storage.type.datastar");
    assert.equal(of("function"), "storage.type.function.datastar");
    assert.equal(of("async"), "storage.modifier.async.datastar");
  });

  it("reads numbers and operators as JavaScript does", async () => {
    const t = await kinds("text.html.basic", `<div data-text=".5 + 10n ~ 1_000 ^ 0xFF | 1e3 & 2 ** 3">`);
    assert.deepEqual(between(t, ".5", "3"), [
      [".5", S.number],
      ["+", S.arithmetic],
      ["10n", S.number],
      ["~", S.bitwise],
      ["1_000", S.number],
      ["^", S.bitwise],
      ["0xFF", S.number],
      ["|", S.bitwise],
      ["1e3", S.number],
      ["&", S.bitwise],
      ["2", S.number],
      ["**", S.arithmetic],
      ["3", S.number],
    ]);
  });

  it("divides after a value, opens a regex after an operator, and knows comments", async () => {
    const t = await kinds("text.html.basic", `<div data-text="$a / 2 / $b /* $c */ + f(/x/) // $d">`);
    assert.deepEqual(between(t, "$", "// $d"), [
      ["$", S.dollar],
      ["a", S.signal],
      ["/", S.arithmetic],
      ["2", S.number],
      ["/", S.arithmetic],
      ["$", S.dollar],
      ["b", S.signal],
      ["/* $c */", "comment.block.datastar"],
      ["+", S.arithmetic],
      ["f", S.call],
      ["(", S.punctuation],
      ["/x/", "string.regexp.datastar"],
      [")", S.punctuation],
      ["// $d", "comment.line.double-slash.datastar"],
    ]);
    assert.equal(t[t.length - 2]![1], S.stringEnd, "the comment ends where the value does");
    const after = await kinds("text.html.basic", `<div data-text="$a++ / $b / 2" data-show="/'/.test($c)">`);
    assert.ok(!after.some(([, s]) => s === "string.regexp.datastar" && after.find(([x]) => x === "/ $b /")), "a slash after ++ divides");
    assert.deepEqual(between(after, "/'/"), [["/'/", "string.regexp.datastar"], [".", S.accessor], ["test", S.method], ["(", S.punctuation], ["$", S.dollar], ["c", S.signal], [")", S.punctuation], ['"', S.stringEnd], [">", "punctuation.definition.tag.end.html"]]);
  });

  it("reads an HTML entity quote as a string, an unquoted value as an expression", async () => {
    const t = await kinds("text.html.basic", `<div data-text="&quot;x&quot; + 1" data-show=$open>`);
    assert.deepEqual(between(t, "&quot;", "1"), [
      ["&quot;", "string.quoted.double.datastar punctuation.definition.string.begin.datastar"],
      ["x", "string.quoted.double.datastar"],
      ["&quot;", "string.quoted.double.datastar punctuation.definition.string.end.datastar"],
      ["+", S.arithmetic],
      ["1", S.number],
    ]);
    assert.deepEqual(between(t, "show"), [
      ["show", S.plugin],
      ["=", "punctuation.separator.key-value.html"],
      ["$", S.dollar],
      ["open", S.signal],
      [">", "punctuation.definition.tag.end.html"],
    ]);
  });

  it("reads a filter's object, and a signal attribute's name", async () => {
    const t = await kinds("text.html.basic", `<div data-persist="{include: /^a/}" data-indicator="busy" data-ref='box'>`);
    assert.deepEqual(between(t, "{", "}"), [
      ["{", S.punctuation],
      ["include", S.option],
      [":", "punctuation.separator.key-value.datastar"],
      ["/^a/", "string.regexp.datastar"],
      ["}", S.punctuation],
    ]);
    assert.deepEqual(between(t, "indicator"), [
      ["indicator", S.plugin],
      ["=", "punctuation.separator.key-value.html"],
      ['"', S.stringStart],
      ["busy", S.signal],
      ['"', S.stringEnd],
      ["data-", S.prefix],
      ["ref", S.plugin],
      ["=", "punctuation.separator.key-value.html"],
      ["'", S.stringStart],
      ["box", S.signal],
      ["'", S.stringEnd],
      [">", "punctuation.definition.tag.end.html"],
    ]);
  });

  it("colours SVG and MathML attributes", async () => {
    for (const line of [`<svg data-on:click="$a++"><circle data-attr:r="$r"/></svg>`, `<math data-show="$m"></math>`]) {
      const t = await tokens("text.html.basic", line);
      assert.ok(t.some((x) => x.scopes.includes(S.plugin)), line);
      assert.ok(t.some((x) => x.scopes.includes(S.signal)), line);
    }
  });

  it("colours a template language's HTML: Handlebars, jte and Mustache", async () => {
    for (const scope of ["text.html.handlebars", "text.html.jte", "source.mustache"]) {
      const t = await kinds(scope, `<div data-on:click="$a++" data-color="x">{{name}}</div>`);
      assert.deepEqual(between(t, "data-", "a"), [["data-", S.prefix], ["on", S.plugin], [":", S.colon], ["click", S.key], ["=", "punctuation.separator.key-value.html"], ['"', S.stringStart], ["$", S.dollar], ["a", S.signal]], scope);
      assert.ok(!t.some(([text, s]) => text.includes("color") && s.includes("datastar")), scope);
    }
    const injected = contributed.find((g) => g.scopeName === "streamlord.html.injection")!.injectTo!;
    for (const scope of ["text.html.basic", "text.html.derivative", "text.html.handlebars", "text.html.jte", "source.mustache", "source.kotlin"]) assert.ok(injected.includes(scope), scope);
  });
});

describe("datastar colours in Kotlin strings", () => {
  it("leaves a Kotlin template to Kotlin, and reads an escaped dollar as a signal", async () => {
    const t = await kinds("source.kotlin", `div { dataText("$n + \\$total + \${'$'}m + \${x}") }`);
    assert.deepEqual(between(t, '"', '"'), [['"', S.ktStart], ["$", S.templateBegin], ["n", S.template], ["+", S.arithmetic], ["\\", S.escape], ["$", S.dollar], ["total", S.signal], ["+", S.arithmetic], ["${'$'}", S.dollarEscape], ["m", S.signal], ["+", S.arithmetic], ["${", S.templateBegin], ["x", "identifier.kotlin"], ["}", S.templateEnd], ['"', S.ktEnd]]);
    const multi = await kinds("source.kotlin", `div { dataShow($$"$open && $\${id} > 0") }`);
    assert.deepEqual(between(multi, "$", "0"), [["$", S.dollar], ["open", S.signal], ["&&", "keyword.operator.comparison.datastar"], ["$${", S.templateBegin], ["id", "identifier.kotlin"], ["}", S.templateEnd], [">", S.arithmetic], ["0", S.number]]);
    const html = await kinds("source.kotlin", `val h = """<p data-text="$n + \${'$'}m"></p>"""`, false);
    assert.deepEqual(between(html, "$", "m"), [["$", S.templateBegin], ["n", S.template], ["+", S.arithmetic], ["${'$'}", S.dollarEscape], ["m", S.signal]]);
  });

  it("colours a DSL call's expression on the next line, in a raw string, by name, and as a lone object", async () => {
    const next = await tokenLines("source.kotlin", [DSL_IMPORT, "div { dataOn(", '    "click",', '    "\\$a++",', ") }", "val y = 2"]);
    assert.ok(next[3]!.some((x) => x.scopes.includes(S.signal)), "the expression on its own line");
    assert.ok(!next[2]!.some((x) => x.scopes.includes("meta.embedded.inline.datastar")), "the event is no expression");
    assert.ok(next[5]!.every((x) => !x.scopes.some((s) => s.startsWith("string") || s.includes("datastar"))), "the next statement is code");
    const first = await tokenLines("source.kotlin", [DSL_IMPORT, "div { dataText(", '    "\\$a"', ") }"]);
    assert.ok(first[2]!.some((x) => x.scopes.includes(S.signal)), "a first argument on its own line");
    const raw = await kinds("source.kotlin", `div { dataText($$"""$a + 1""") }`);
    assert.deepEqual(between(raw, '$$"""', '"""'), [['$$"""', S.ktRawStart], ["$", S.dollar], ["a", S.signal], ["+", S.arithmetic], ["1", S.number], ['"""', S.ktRawEnd]]);
    for (const call of [`dataOnClick(expression = "\\$b++")`, `dataSignals("{a: \\$b}")`, `dataClass("{x: \\$b}")`, `dataAttr("{x: \\$b}")`, `dataStyle("{x: \\$b}")`, `dataComputed("{x: () => \\$b}")`, `dataAnimate("{x: \\$b}")`]) {
      const t = await kinds("source.kotlin", `div { ${call} }`);
      assert.ok(t.some(([text, s]) => text === "b" && s === S.signal), call);
    }
    const receiver = await tokens("source.kotlin", `fun f() { sse.executeScript("console.log(1)") }`, false);
    assert.ok(receiver.some((x) => x.scopes.includes("meta.embedded.block.javascript")), "a call after a receiver");
  });

  it("colours the expression of each DSL expression helper, and of each option of FetchOptions that takes one", async () => {
    const pro = "import io.github.markusaugust.streamlord.html.pro.*";
    for (const call of [
      ...["getExpr", "postExpr", "putExpr", "patchExpr", "deleteExpr", "queryExpr", "setAllExpr"].map((fn) => `dataOnClick(${fn}("\\$b"))`),
      `dataText(fitExpr("\\$b", "0", "1", "0", "1"))`,
      `dataText(fitExpr("1", signal("m"), "\\$b", "0", "1"))`,
      `dataOnClick(clipboardExpr("\\$b"))`,
      `dataOnClick(post("/x") { payloadExpr = "{id: \\$b}" })`,
      `dataOnClick(get("/x") { requestCancellationExpr = "\\$b" })`,
      `dataOnClick(get("/x") { headerExprs = mapOf("X-Id" to "\\$b") })`,
    ]) {
      const t = (await tokenLines("source.kotlin", [DSL_IMPORT, pro, `div { ${call} }`]))[2]!;
      assert.ok(t.some((x) => x.text === "b" && x.scopes.includes(S.signal)), call);
    }
    // A header's name, and what follows the call, are no expression.
    const t = await kinds("source.kotlin", `div { dataOnClick(get("/x") { headerExprs = mapOf("X-b" to "1") }); val s = "\\$b" }`);
    assert.ok(t.every(([, s]) => s !== S.signal), JSON.stringify(t));
  });

  it("colours the DSL's signal names", async () => {
    for (const [call, name] of [[`dataBind("user.name")`, "user"], [`dataRef("box")`, "box"], [`dataIndicator("busy")`, "busy"], [`dataSignals("count", "0")`, "count"], [`dataComputed("total", "\\$a")`, "total"]] as const) {
      const t = await kinds("source.kotlin", `div { ${call} }`);
      assert.ok(t.some(([text, s]) => text === name && s === S.signal), `${call}: ${JSON.stringify(t)}`);
    }
  });

  it("colours HTML in a raw string that opens on the next line", async () => {
    const lines = await tokenLines("source.kotlin", ['val h = """', '    <div data-text="${\'$\'}a">', '""".trimIndent()', 'val sql = """', "    select $x", '"""', "val y = 2"]);
    assert.ok(lines[1]!.some((x) => x.scopes.includes(S.plugin)), "the attribute");
    assert.ok(lines[1]!.some((x) => x.scopes.includes(S.signal)), "the signal");
    assert.ok(!lines[4]!.some((x) => x.scopes.some((s) => s.includes("html"))), "a string that is no HTML");
    assert.ok(lines[4]!.some((x) => x.scopes.includes("string.quoted.triple.kotlin")), "still a string");
    assert.ok(lines[6]!.every((x) => !x.scopes.some((s) => s.startsWith("string"))), "the next statement is code");
  });

  it("reads a long line without backtracking", async () => {
    const value = Array.from({ length: 400 }, () => "$a / 2 / $b + {k: {n: [($c)]}}").join(" + ");
    const started = performance.now();
    await tokens("text.html.basic", `<div data-text="${value}">`);
    await tokens("source.kotlin", `div { dataText("${value.replaceAll("$", "\\$")}") }`);
    await tokens("text.html.basic", `<div data-text="${"/".repeat(2000)}${"{".repeat(500)}">`);
    assert.ok(performance.now() - started < 3000, `took ${performance.now() - started} ms`);
  });
});

describe("the lists both editors share", () => {
  it("knows the catalog's backend actions, and the DSL's call sites", () => {
    const catalog = JSON.parse(readFileSync(new URL("../../catalog/datastar-1.0.5.json", root), "utf8")) as {
      actions: { name: string; kind: string }[];
      kotlinCallSites: { expression: Record<string, { arg: number | string }> };
    };
    const expr = JSON.parse(readFileSync(new URL("syntaxes/datastar-expression.tmLanguage.json", root), "utf8"));
    const backend = /\(((?:[a-z]+\|)+[a-z]+)\)/.exec(expr.repository.action.match)?.[1]?.split("|").sort();
    assert.deepEqual(backend, catalog.actions.filter((a) => a.kind === "backend").map((a) => a.name).sort());
    const kt = JSON.parse(readFileSync(new URL("syntaxes/kotlin-injection.tmLanguage.json", root), "utf8"));
    const names = (pattern: string) => /\\b\(?:?\(\?:((?:[A-Za-z]+\|)*[A-Za-z]+)\)/.exec(pattern)?.[1]?.split("|").sort() ?? [];
    const sites = Object.entries(catalog.kotlinCallSites.expression).filter(([n]) => !n.startsWith("$"));
    const at = (arg: (a: number | string) => boolean) => sites.filter(([, s]) => arg(s.arg)).map(([n]) => n).sort();
    assert.deepEqual(names(kt.repository["expression-call-first-arg"].patterns[0].begin), at((a) => a === 0));
    assert.deepEqual(names(kt.repository["expression-call-second-arg"].patterns[0].begin), at((a) => a === 1 || a === "last"));
    assert.deepEqual(names(kt.repository["expression-call-lone-object"].patterns[0].begin), at((a) => a === "last"));
  });

  it("knows the same globals and keywords as the analysis", () => {
    const tokensKt = readFileSync(new URL("../../streamlord-analysis/src/main/kotlin/io/github/markusaugust/streamlord/analysis/Tokens.kt", root), "utf8");
    const set = (name: string) => [...new RegExp(`val ${name}[^=]*=\\s*setOf\\(([^)]*)\\)`).exec(tokensKt)![1]!.matchAll(/"(\w+)"/g)].map((m) => m[1]!).sort();
    const expr = JSON.parse(readFileSync(new URL("syntaxes/datastar-expression.tmLanguage.json", root), "utf8"));
    const words = (pattern: string) => /\\b\((?:\?:)?([\w|]+)\)\\b/.exec(pattern)![1]!.split("|");
    assert.deepEqual(words(expr.repository.globals.match).sort(), set("JS_GLOBALS"));
    const keywords = (expr.repository.keywords.patterns as { match: string }[]).flatMap((p) => words(p.match));
    assert.deepEqual([...keywords, ...words(expr.repository.constants.match)].sort(), set("JS_KEYWORDS"));
  });
});

describe("what is not Datastar's", () => {
  // Each engine writes its placeholders before the browser reads the value, as the analysis and IntelliJ have it.
  it("leaves a template engine's placeholder in a Datastar value to the engine's grammar", async () => {
    for (const [scope, open, engine] of [
      ["text.html.jte", "${", "statement.jte"],
      ["text.html.handlebars", "{{", "handlebars"],
      ["source.mustache", "{{", "mustache"],
    ] as const) {
      const close = open === "${" ? "}" : "}}";
      const t = await tokens(scope, `<div data-on:click="$sel = '${open}item.id${close}'; $n = ${open}count${close}" data-nonce="${open}token${close}" data-show="$a">`);
      for (const name of ["item", "count", "token"]) {
        const tok = t.find((x) => x.text.includes(name))!;
        // Inside a JavaScript string the engine's scope stands within the string's; nothing of Datastar's is inside it.
        const at = tok.scopes.findIndex((s, i) => i > 0 && s.includes(engine));
        assert.ok(at >= 0, `${scope} ${name}: ${tok.scopes.join(" ")}`);
        assert.ok(!tok.scopes.slice(at).some((s) => s.includes("datastar")), `${scope} ${name}: ${tok.scopes.join(" ")}`);
      }
      assert.ok(t.some((x) => x.text === "sel" && x.scopes.includes(S.signal)), `${scope}: the signal`);
      assert.ok(t.some((x) => x.text === "a" && x.scopes.includes(S.signal)), `${scope}: the next attribute`);
    }
  });

  it("colours an attribute of SVG and MathML in the tag, never the element's text", async () => {
    const t = await tokens("text.html.basic", `<svg data-show="$a"><circle data-attr:r="$r"/><text>Use data-show="$x" here</text></svg>`);
    assert.ok(t.some((x) => x.text === "a" && x.scopes.includes(S.signal)));
    assert.ok(t.some((x) => x.text === "r" && x.scopes.includes(S.signal)));
    assert.ok(!t.some((x) => x.text === "x" && x.scopes.some((s) => s.includes("datastar"))), "the text of <text>");
    const m = await tokens("text.html.basic", `<math><mi>data-text="$z"</mi></math>`);
    assert.ok(!m.some((x) => x.scopes.some((s) => s.includes("datastar"))), "the text of <mi>");
  });

  it("counts the dollars of a $$$ string", async () => {
    const t = await kinds("source.kotlin", `val h = $$$"""<p data-text="$\${x} + $$\${y}"></p>"""`, false);
    const template = (name: string) => t.some(([text, s], i) => text === name && t[i - 1]?.[1] === S.templateBegin && s.includes("kotlin"));
    assert.ok(template("y"), `three dollars open a template: ${JSON.stringify(t)}`);
    assert.ok(!template("x"), "two are text");
  });

  it("gives an object key's colon a punctuation scope, and a signal attribute only a name", async () => {
    const t = await kinds("text.html.basic", `<div data-signals="{slots: 8}" data-on:click="@post('/x', {retry: 'never'})" data-indicator="a b">`);
    assert.deepEqual(between(t, "slots", "8"), [["slots", S.objectKey], [":", "punctuation.separator.key-value.datastar"], ["8", S.number]]);
    assert.deepEqual(between(t, "retry", ":"), [["retry", S.option], [":", "punctuation.separator.key-value.datastar"]]);
    assert.ok(!t.some(([text, s]) => (text === "a" || text === "b") && s.includes("signal")), JSON.stringify(t));
    const ternary = await kinds("text.html.basic", `<div data-text="$a ? 1 : 2">`);
    assert.deepEqual(between(ternary, "?", "2"), [["?", S.arithmetic], ["1", S.number], [":", S.arithmetic], ["2", S.number]]);
  });

  it("reads a long line of DSL calls in time", async () => {
    const line = Array.from({ length: 1200 }, (_, i) => `dataOn("click", "\\$c${i}++; @post('/x')"); `).join("");
    const started = performance.now();
    await tokens("source.kotlin", line);
    assert.ok(performance.now() - started < 1000, `took ${performance.now() - started} ms`);
  });
});
