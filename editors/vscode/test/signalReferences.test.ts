import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { isKnownSignal, nearestSignal, signalReferencesInHtml, signalReferencesInKotlin, unknownSignalIssue } from "../src/signalReferences.ts";
import { collectSignalDefinitions, collectSignals } from "../src/signals.ts";

/** The cases of the analysis module's `SignalReferencesTest.kt`. */
const refs = (src: string) => signalReferencesInKotlin(src, "data-").map((r) => [src.slice(r.start, r.end), r.name]);

const unknown = (src: string, language: "kotlin" | "html" = "kotlin") => {
  const defined = collectSignalDefinitions(src, language);
  const found = language === "kotlin" ? signalReferencesInKotlin(src, "data-") : signalReferencesInHtml(src, "data-");
  return found.flatMap((r) => unknownSignalIssue(r, defined) ?? []);
};

describe("signal references", () => {
  it("a misspelt signal in a multi-dollar string is unknown", () => {
    const src = `fun f(): String = $$"""
    <div data-signals:teller="0">
      <button data-on:click="$telefon++">Øk</button>
      <span data-text="$teller"></span>
    </div>
"""`;
    const issues = unknown(src);
    assert.deepEqual(issues.map((i) => src.slice(i.start, i.end)), ["$telefon"]);
    assert.equal(issues[0]?.code, "unknown-signal");
  });

  it("a near name is offered as the fix, and the dollar is kept as written", () => {
    const src = `fun f() = """<div data-signals:count="0" data-text="\${'$'}coutn"></div>"""`;
    const [issue] = unknown(src);
    assert.ok(issue);
    assert.equal(src.slice(issue.start, issue.end), "${'$'}coutn");
    const [fix] = issue.fixes ?? [];
    assert.equal(fix?.text, "count");
    assert.equal(src.slice(fix!.start, fix!.end), "coutn");
    assert.match(issue.message, /Did you mean \$count\?/);
  });

  it("the expression handed to a dsl call is read", () => {
    const src = String.raw`fun f() { dataText("\$total + \$tax") }`;
    assert.deepEqual(refs(src), [
      ["\\$total", "total"],
      ["\\$tax", "tax"],
    ]);
  });

  it("a kotlin template is not a signal", () => {
    const src = `fun f(teller: Int) = """<button data-on:click="$teller++">x</button>"""`;
    assert.deepEqual(refs(src), []);
  });

  it("a path reaches into a defined signal, and an object holds its paths", () => {
    const defined = new Set(["user", "form.email"]);
    assert.ok(isKnownSignal("user.name.length", defined));
    assert.ok(isKnownSignal("form", defined));
    assert.ok(isKnownSignal("form.email", defined));
    assert.ok(!isKnownSignal("forms", defined));
  });

  it("an assignment defines the signal, a comparison does not", () => {
    const html = `<button data-on:click="$open = true"></button><p data-show="$open && $shown == 1"></p>`;
    assert.deepEqual(unknown(html, "html").map((i) => html.slice(i.start, i.end)), ["$shown"]);
  });

  it("markup files read bare signals without defining them", () => {
    const html = `<div data-signals:count="0"><span data-text="$cuont"></span><span data-text="$count"></span></div>`;
    assert.deepEqual(unknown(html, "html").map((i) => html.slice(i.start, i.end)), ["$cuont"]);
    // Completion still offers every name it sees.
    assert.ok(collectSignals(html, "html").has("cuont"));
  });

  it("strings, kebab-case and template syntax are left alone", () => {
    const html = `<p data-text="'$nope' + $foo-bar"></p><p data-text="{{ x }} + $nope"></p>`;
    assert.deepEqual(unknown(html, "html"), []);
  });

  it("serializable properties and dsl writes define signals", () => {
    const src = `@Serializable data class S(val query: String = "")
fun f() { patchSignals("total" to 1); dataText("\${'$'}query + \${'$'}total") }`;
    assert.deepEqual(unknown(src), []);
  });

  it("no fix when nothing is close", () => {
    assert.equal(nearestSignal("telefon", new Set(["teller"])), null);
    assert.equal(nearestSignal("telelr", new Set(["teller", "total"])), "teller");
  });
});
