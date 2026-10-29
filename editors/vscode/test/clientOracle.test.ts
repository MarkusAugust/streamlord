import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeHtml } from "../src/analyze.ts";
import { catalog, parseAttributeName } from "../src/catalog.ts";
import { clientRewrite, clientScript } from "../src/expression.ts";
import { kebab } from "../src/attributeKeys.ts";
import { wireKey, wireKeyReads } from "../src/markup.ts";
import { referenceAsSubtraction, withoutReferenceHyphens } from "../src/signalNameHyphens.ts";
import { clientKeyName, toCamel } from "../src/signals.ts";

/**
 * Holds the editor's mirror to the fixtures under `conformance/client/`, each the output of a
 * function of the Datastar 1.0.4 client run by `conformance/generate/client.mjs`: `casing.json`
 * (`modifyCasing` in utils/text.ts), `attribute-keys.json` (`parseAttributeKey` in
 * engine/engine.ts), `expressions.json` (the body `genRx` compiles) and `tags.json`
 * (`modifyTiming` in utils/timing.ts).
 */
const dir = new URL("../../../conformance/client/", import.meta.url);
const read = <T>(file: string): T => (JSON.parse(readFileSync(new URL(file, dir), "utf8")) as { cases: T }).cases;

type CasingCase = { key: string; modifyCasing: Record<string, string | { error: string }> };
type AttributeKeyCase = { raw: string; pluginName: string; key: string | null; modifiers: Record<string, string[]>; signalCamel?: string };
type ExpressionCase = { expression: string; returnsValue: boolean; js: string; compiles: boolean };
type TimingCase = { modifiers: string; wrappers: string[]; trace: string[] };

const opts = { prefix: "data-", checkHtmlAttributes: true };

describe("the client's rules", () => {
  it("reads the case of a key as modifyCasing does", () => {
    const cases = read<CasingCase[]>("casing.json");
    assert.equal(cases.length, 68);
    for (const { key, modifyCasing } of cases) {
      // A key holding __ is cut there by parseAttributeKey, so modifyCasing never sees it whole.
      if (key.includes("__")) continue;
      assert.equal(clientKeyName(key), modifyCasing["default camel"], key);
      // A key ending in _ runs into the __ of a modifier after it, and the client reads a shorter key.
      if (key.endsWith("_")) continue;
      for (const [column, value] of Object.entries(modifyCasing)) {
        // The client throws on `__case.pascal` of an empty key, which no attribute hands it.
        if (!column.startsWith("case.") || typeof value !== "string") continue;
        assert.equal(clientKeyName(`${key}__${column}`), value, `${key} with __${column}`);
      }
    }
  });

  it("writes a key the client reads back as the name typed", () => {
    const casing = new Map(read<CasingCase[]>("casing.json").map((c) => [c.key, c.modifyCasing]));
    const misread: string[] = [];
    for (const name of casing.keys()) {
      if (!/[A-Z]/.test(name) || /\s/.test(name) || name.includes("__")) continue;
      for (const keyCase of ["camel", "kebab"] as const) {
        const wire = wireKey(name, keyCase, null);
        const [base = ""] = (wire ?? kebab(name)).split("__case.");
        const table = casing.get(base.toLowerCase());
        assert.ok(table, `${base}, the key written for ${name}, is not in casing.json: add it`);
        if (wire === null) {
          // No key gives the name back: the client's reading under the case the capitals ask for differs.
          const wanted = /^[A-Z]/.test(name) ? "pascal" : "camel";
          assert.notEqual(table[`case.${wanted}`], name, `${name} refused as a ${keyCase} key`);
          assert.equal(wireKeyReads(name), table[`case.${wanted}`], name);
          continue;
        }
        const explicit = wire.split("__case.")[1];
        const column = explicit ? `case.${explicit}` : `default ${keyCase}`;
        const read = table[column];
        // A signal key in markup is read as camelCase, as toCamel reads it; an event or class comes back as typed.
        const expected = keyCase === "camel" ? toCamel(name) : name;
        if (read !== expected) misread.push(`${name} written as ${wire} for ${keyCase} keys, which the client reads as ${JSON.stringify(read)}, not ${expected}`);
      }
    }
    assert.deepEqual(misread, []);
  });

  it("splits an attribute name as parseAttributeKey does", () => {
    const cases = read<AttributeKeyCase[]>("attribute-keys.json");
    assert.equal(cases.length, 54);
    for (const { raw, pluginName, key, modifiers, signalCamel } of cases) {
      const parsed = parseAttributeName(`data-${raw}`, "data-");
      assert.ok(parsed, raw);
      if (pluginName.endsWith(":") && key === null) {
        // The client's key pattern needs a character after the colon, so `data-signals:` is the plugin
        // `signals:`, which it does not have. The parse keeps the colon as an empty key, and the markup
        // check says in words what the client does with it.
        assert.deepEqual([parsed.base, parsed.key], [pluginName.slice(0, -1), ""], raw);
        const [issue, ...more] = analyzeHtml(`<div data-${raw}="1"></div>`, opts);
        assert.equal(more.length, 0, raw);
        assert.match(issue?.message ?? "", new RegExp(`Datastar reads ${pluginName} as an attribute it does not have`), raw);
        continue;
      }
      assert.equal(parsed.base, pluginName, raw);
      assert.equal(parsed.key, key, raw);
      // The client keeps the last modifier of each label, its arguments as a set; the parse keeps every one as written.
      const seen: Record<string, string[]> = {};
      for (const m of parsed.modifiers) seen[m.name] = [...new Set(m.args)];
      assert.deepEqual(seen, modifiers, raw);
      if (signalCamel !== undefined) assert.equal(clientKeyName(raw.slice(raw.indexOf(":") + 1)), signalCamel, raw);
    }
  });

  it("writes the function body genRx compiles", () => {
    const cases = read<{ rewrites: ExpressionCase[] }>("expressions.json").rewrites;
    assert.equal(cases.length, 322);
    for (const { expression, returnsValue, js } of cases) {
      assert.equal(clientRewrite(clientScript(expression, returnsValue).js).js, js, `${JSON.stringify(expression)} with returnsValue ${returnsValue}`);
    }
  });

  // What withoutReferenceHyphens and referenceAsSubtraction say of a name, each against the body
  // the client compiles for `$` and that name. The table is shared with the core's ClientOracleTest.
  it("reads a reference's hyphens as genRx does", () => {
    const cases = read<{ rewrites: ExpressionCase[] }>("expressions.json").rewrites;
    type Row = { name: string; fixed: string | null; subtraction: string | null; shows: string };
    const table = JSON.parse(readFileSync(new URL("./fixtures/reference-hyphens.json", import.meta.url), "utf8")) as { cases: Row[] };
    assert.equal(table.cases.length, 20);
    for (const { name, fixed, subtraction, shows } of table.cases) {
      const found = cases.filter((c) => c.expression === `$${name}` && !c.returnsValue);
      assert.equal(found.length, 1, name);
      const [one] = found as [ExpressionCase];
      assert.equal(one.compiles, true, name);
      assert.ok(one.js.includes(shows), `${name}: ${one.js}`);
      assert.equal(withoutReferenceHyphens(name), fixed, name);
      assert.equal(referenceAsSubtraction(name), subtraction, name);
    }
  });

  it("says what the client waits for a timing modifier", () => {
    const cases = read<{ timing: TimingCase[] }>("tags.json").timing;
    assert.equal(cases.length, 23);
    for (const { modifiers, trace } of cases) {
      // One wrapper at a time: a trace of several does not say which timer is whose.
      if (modifiers.includes("__")) continue;
      const waits = [...new Set(trace.map((t) => /timer \d+ set (\S+)ms/.exec(t)?.[1]).filter((w): w is string => w !== undefined))];
      assert.equal(waits.length, 1, `${modifiers}: one wait expected in the trace, found ${waits}`);
      const wait = waits[0];
      const message = analyzeHtml(`<div data-on:click__${modifiers}="$a = 1"></div>`, opts)
        .map((i) => i.message)
        .find((m) => m.includes("needs a duration") || m.includes("reads none and does not wait") || /as \d+ ms/.test(m));
      if (message === undefined) assert.notEqual(wait, "NaN", `${modifiers}: the check says nothing, but the client waits ${wait}ms`);
      else if (message.includes("reads none and does not wait")) assert.equal(wait, "NaN", modifiers);
      else if (/as \d+ ms/.test(message)) assert.equal(wait, /as (\d+) ms/.exec(message)?.[1], modifiers);
      // "needs a duration" names no number: it is right where the client reads none or no wait, and for
      // JavaScript's own number syntax, 1e3ms, which the check refuses on purpose (DURATION takes digits).
      else {
        assert.match(message, /needs a duration/, modifiers);
        const read = Number(wait);
        assert.ok(Number.isNaN(read) || read <= 0 || /1e3ms$/.test(modifiers), `${modifiers}: the client waits ${wait}ms, which the check does not say`);
      }
    }
  });

  // conformance/dsl/*.json: for each DSL call there with a literal key that has a capital, the hint's
  // wireKey is the key and __case the DSL wrote, and the name it says the client reads back is the
  // one the call means, which DslAsTheClientReadsItTest holds to what the client did.
  it("the wire hint names the key the dsl writes and the name the client reads", () => {
    type DslCase = { name: string; prefix: string; call: { fn: string; key: string | null; case: string | null }; read: { attributes: [string, string][][] } };
    let checked = 0;
    for (const file of ["free.json", "pro.json"]) {
      const cases = (JSON.parse(readFileSync(new URL(file, new URL("../../../conformance/dsl/", import.meta.url)), "utf8")) as { cases: DslCase[] }).cases;
      for (const c of cases) {
        const key = c.call.key;
        // The hint is shown for a key with a capital, the one the DSL rewrites.
        if (key === null || !/[A-Z]/.test(key)) continue;
        const spec = catalog.attributesByKotlin.get(c.call.fn);
        if (!spec?.keyed || !spec.keyCase) continue;
        const prefix = `${c.prefix}${spec.name}:`;
        const written = c.read.attributes.flat().map(([n]) => n).find((n) => n.startsWith(prefix))?.slice(prefix.length);
        if (written === undefined) continue;
        const [base, ...mods] = written.split("__");
        const wire = base + mods.filter((m) => m.startsWith("case.")).map((m) => `__${m}`).join("");
        assert.equal(wireKey(key, spec.keyCase, c.call.case), wire, c.name);
        if (c.call.case === null && spec.keyCase !== "raw") {
          assert.equal(spec.keyCase === "camel" || wire.includes("__case.") ? clientKeyName(wire) : wire, key, c.name);
        }
        checked++;
      }
    }
    assert.ok(checked >= 15, `only ${checked} cases checked`);
  });
});
