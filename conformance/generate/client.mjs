// Generates conformance/client/*.json by running the functions of the local copy of the Datastar client:
// the casing, attribute key, expression, modifier tag and signal store rules Streamlord's analysis
// and editor mirror. Every output is what the client's own code returns, not a port of it.
//
// The reference is the free client under datastar-reference/datastar-<version>/library/src and nothing
// else: the bundler refuses to read a file outside that directory.
import { build } from "esbuild";
import { existsSync, mkdirSync, readdirSync, realpathSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const referenceRoot = path.resolve(here, "../../datastar-reference");
const versions = readdirSync(referenceRoot).filter((d) => d.startsWith("datastar-"));
if (versions.length !== 1) throw new Error(`expected one datastar-<version> directory under ${referenceRoot}, found ${versions.length}`);
const version = versions[0].slice("datastar-".length);
const reference = path.join(referenceRoot, versions[0], "library", "src");
const out = path.resolve(here, "../client");

// The aliases of the reference's tsconfig.json, less the ones this generator never follows.
const aliases = {
  "@engine": "engine/engine.ts",
  "@engine/": "engine/",
  "@utils/": "utils/",
  "@plugins/": "plugins/",
};

/** Whether [file] lies inside the reference directory: the free client's source and nothing else. */
const permitted = (file) => {
  const real = existsSync(file) ? realpathSync(file) : file;
  const relative = path.relative(realpathSync(reference), real);
  return !relative.startsWith("..") && !path.isAbsolute(relative);
};

/** Resolves the reference's aliases and refuses any path that leaves the free client. */
const referenceOnly = {
  name: "reference-only",
  setup(build) {
    build.onResolve({ filter: /.*/ }, (args) => {
      if (args.kind === "entry-point") return undefined;
      let target;
      if (args.path.startsWith("@")) {
        const alias = Object.keys(aliases).find((a) => (a.endsWith("/") ? args.path.startsWith(a) : args.path === a));
        if (!alias) throw new Error(`No alias for ${args.path}`);
        target = path.join(reference, aliases[alias] + args.path.slice(alias.length));
      } else {
        target = path.resolve(args.resolveDir, args.path);
      }
      if (!target.endsWith(".ts")) target += ".ts";
      if (!permitted(target)) throw new Error(`Refusing to read ${target}: not part of the free client at ${reference}`);
      return { path: target };
    });
  },
};

/** The minimum of a document the engine touches at module load. */
globalThis.document = {
  documentElement: { getAttribute: () => null, removeAttribute() {} },
  addEventListener() {},
  dispatchEvent: () => true,
  head: { appendChild() {} },
  createElement: () => ({ remove() {} }),
};
globalThis.window = globalThis;
globalThis.HTMLInputElement = class {};
globalThis.MutationObserver = class {
  observe() {}
};

// The engine first, as the client's own bundle enters it: text.ts, csp.ts and errors.ts import each
// other in a cycle, and only this order has each defined before the next reads it at module load.
const entry = `
export { parseAttributeKey, genRx } from "@engine";
export { kebab, camel, snake, pascal, modifyCasing, isStringTrue } from "@utils/text";
export { tagToMs, tagHas, tagFirst } from "@utils/tags";
export { modifyTiming } from "@utils/timing";
export { pathToObj } from "@utils/paths";
export { filtered, mergePatch, root } from "@engine/signals";
`;

const bundled = await build({
  stdin: { contents: entry, resolveDir: reference, loader: "ts" },
  bundle: true,
  write: false,
  format: "esm",
  platform: "neutral",
  target: "es2022",
  define: { ALIAS: "null" },
  plugins: [referenceOnly],
  logLevel: "silent",
});
const code = bundled.outputFiles[0].text;
const client = await import(`data:text/javascript;base64,${Buffer.from(code).toString("base64")}`);

const mods = (cases) => new Map(Object.entries(cases).map(([k, v]) => [k, new Set(v)]));

/** [fn]'s result, or the name of what it threw. */
const attempt = (fn) => {
  try {
    return { value: fn() };
  } catch (e) {
    return { error: e.constructor.name };
  }
};

// ---------------------------------------------------------------- casing.json

const casingKeys = [
  "foo-bar", "fooBar", "FooBar", "a-b-1", "grid-col-2", "foo-_bar", "foo--bar", "-foo", "foo-",
  "URLBase", "u-r-l-base", "DOMContentLoaded", "d-o-m-content-loaded", "x2y", "x-2-y", "x2-y",
  "_value", "to-string", "a-b.c-d", "A-b", "a-B", "__proto__", "foo.bar", "foo.bar-baz", "foo-bar.baz",
  "user.first-name", "a.b.c", "item-1", "item1", "1item", "a1b2", "2fa", "snake_case", "mixed_kebab-case",
  "foo_bar-baz", "Foo-Bar", "FOO", "foo", "f", "F", "", "foo bar", "foo\tbar", "is-open", "widget-loaded",
  "aria-label", "data-x", "my-u-r-l", "fooBAR", "foo-b-a-r", "fooBarBaz", "foo-bar-baz", "fooBar-baz", "f-o-o", "constructor", "has-own-property",
  "a-b", "a--b", "a-b-", "-a-b", "a.-b", "a-.b", "é-b", "foo-é", "b_", "x-1-y", "v2-api", "api-v2",
  // A character past the BMP, which a Java . takes whole and a JavaScript one half of, and U+0085,
  // which only a JavaScript . takes.
  "a-\u{10428}", "a-\u0085b",
];

const casingTable = {
  "default camel": (k) => client.modifyCasing(k, new Map(), "camel"),
  "default kebab": (k) => client.modifyCasing(k, new Map(), "kebab"),
  "case.camel": (k) => client.modifyCasing(k, mods({ case: ["camel"] })),
  "case.kebab": (k) => client.modifyCasing(k, mods({ case: ["kebab"] })),
  "case.snake": (k) => client.modifyCasing(k, mods({ case: ["snake"] })),
  "case.pascal": (k) => client.modifyCasing(k, mods({ case: ["pascal"] })),
  "case.title": (k) => client.modifyCasing(k, mods({ case: ["title"] })),
  "case.snake.pascal": (k) => client.modifyCasing(k, mods({ case: ["snake", "pascal"] })),
  "case.pascal.snake": (k) => client.modifyCasing(k, mods({ case: ["pascal", "snake"] })),
  "case.camel.snake": (k) => client.modifyCasing(k, mods({ case: ["camel", "snake"] })),
};

const casing = casingKeys.map((key) => {
  const entry = { key };
  for (const fn of ["kebab", "camel", "snake", "pascal"]) {
    const r = attempt(() => client[fn](key));
    entry[fn] = "value" in r ? r.value : { error: r.error };
  }
  entry.modifyCasing = {};
  for (const [name, fn] of Object.entries(casingTable)) {
    const r = attempt(() => fn(key));
    entry.modifyCasing[name] = "value" in r ? r.value : { error: r.error };
  }
  return entry;
});

// -------------------------------------------------------- attribute-keys.json

const rawKeys = [
  "signals", "signals:count", "signals:a.b", "signals:a.b__ifmissing", "signals__ifmissing",
  "on:click", "on:click__debounce.500ms.leading__case.camel", "on:click__debounce.500ms.leading__case.camel__once",
  "on:click__debounce.500ms__debounce.1s", "on:click__case.camel__case.snake", "on:click__case.snake.pascal",
  "on:click__case.snake.snake", "on:click__case", "on:click__", "on:click____once", "on:click__once__",
  "on-signal-patch:filter", "on-signal-patch__debounce.100ms", "on:keydown__window__prevent", "on:a:b:c",
  "bind:value", "bind:form.user.name", "attr:aria-label", "class:is-open", "class:is-open__case.camel",
  "signals:my__key", "signals:my__key__ifmissing", "_-proto-___case.snake", "signals:constructor",
  "signals:to-string__case.camel", "signals:foo-bar", "signals:foo-bar__case.kebab", "signals:fooBar",
  "signals:my-key__case.snake__case.camel",
  "indicator:loading", "ref:my-element", "computed:total", "match-media:is-mobile", "json-signals__terse",
  "init", "effect", "show", "text", "on:click__delay.200ms", "on:click__throttle.1s.noleading.trailing",
  "on:click__debounce.1.5s", "on:click__debounce.leading.500ms", "on:click__debounce.500",
  "on:click__viewtransition", "signals:", ":key", "__ifmissing", "", "on:click__once.", "on:click__once..",
];

const attributeKeys = rawKeys.map((raw) => {
  const parsed = client.parseAttributeKey(raw);
  const modifiers = {};
  for (const [label, tags] of parsed.mods) modifiers[label] = [...tags];
  const entry = { raw, pluginName: parsed.pluginName, key: parsed.key === undefined ? null : parsed.key, modifiers };
  if (parsed.key !== undefined) {
    for (const [name, defaultCase] of [["signalCamel", "camel"], ["signalKebab", "kebab"]]) {
      const r = attempt(() => client.modifyCasing(parsed.key, parsed.mods, defaultCase));
      entry[name] = "value" in r ? r.value : { error: r.error };
    }
  }
  return entry;
});

// ----------------------------------------------------------- expressions.json

const expressions = [
  "$count", "$count++", "$count--", "$foo.bar", "$foo-bar", "$foo.bar-baz", "$foo-$bar", "$arr[$index]",
  "$['foo']", "$foo[obj.bar]", "$foo['bar.baz']", "$123", "$foo.0.name", "$a-b.c-d", "$foo.", "$-a", "$a-",
  "$a--b", "$a..b", "$a.-b", "$_private", "$a_b", "$é", "$fooé", "$$a", "$ a", "foo$bar", "$foo$bar",
  "$a.b.c.d", "$a-b-c", "$a.1.2", "$9lives", "$",
  "@get('/x')", "@post('/x', {contentType: 'form'})", "@peek(() => $a)", "@get ('/x')", "@Get('/x')",
  "@$a('/x')", "@_a(1)", "@a1(1)", "@1a(1)", "@a-b(1)", "@get",
  "'$x'", "\"$x\"", "`$x`", "'@x(1)'", "`@x(1)`", "`${$x}`", "`a ${$x} b`", "`${$x} ${$y}`",
  "`${ {a:1}.a }`", "`${$x} and $y`", "`${@get('/x')}`", "`${'$a'}`", "`${\"@a(\"}`", "`\\${$x}`",
  "'it\\'s $x' + $y", "\"say \\\"$x\\\"\" + $y", "'\\\\' + $x", "'a\\'; $b = 'c'", "'a\\x5c'; $b = 'c'",
  "'$a' + $b; 'c'", "'unclosed $x", "`unclosed ${$x}", "'$a\n$b'", "`$x\n${$y}`",
  "$a / 2; $b / 3", "/;/.test($x)", "$a = /[;]/; $b", "/\\//.test($x); $y", "$x.replace(/a/g, 'b'); $y",
  "$a; $b", "$a; $b;", "$a;; $b", ";$a", "$a;", "$a = 1; return $a", "$a = 1; return($a)", "$a = 1; returnValue",
  "return $a", "  $a  ", "\n$a\n", "\u00A0$a\u00A0", "\uFEFF$a", "", "   ", ";",
  "(() => 1)()", "(() => { $a = 1; $b = 2 })(); $c", "(function() { return $a; })()", "(function(x) { return x })(1)",
  "(() => $a; $b)()", "(async () => 1)()",
  "await $x", "await @get('/x')", "evt.target.value", "el.value", "el.dataset.x", "$x = evt.target.value",
  "$count > 0 ? 'many' : 'none'", "$items.map($i => $i.name)", "{a: $a, b: $b}", "x = {$a: 1}",
  "$a = $b = 1", "$a == $b", "$a === 'x'", "!$open", "$open = !$open", "$a += 1", "$count++ // $z",
  "$a = 1 // don't\n$b = 'x'", "/* $a */ $b", "$a /* ; */ ; $b", "$items[$items.length - 1]",
  "$user.name.first", "$user['name'].first", "$a.b[$c.d]", "@get(`/x/${$id}`)", "@get(`/x/${$id}/${$y}`)",
  "@get('/x', {headers: {'X-Y': $z}})", "new Date($ts)", "typeof $a", "$a instanceof Foo",
  "$a ?? 'x'", "$a?.b", "$a?.['b']", "$a.toString()", "$a.constructor", "$__proto__.x", "$a.__proto__",
  "$constructor", "$hasOwnProperty", "console.log($a)", "window.$x", "this.$x", "$x; $x.y; $x-y; $x['z']",
  // Names the expression helpers write after $: a quoted key is text, a reference in brackets is one of its own.
  "$form['first-name']", "$a['x]y-z']", "$a[\"x]y-z\"]", "$a[`x]y-z`]", "$a['it\\'s-x']", "$a['b'].c-d",
  "$items[$i-1]", "$items[$i - 1]", "$a[$b['c-d']]", "$a[$b-c['d']]", "$a[`${$b-c}`]", "$a[`${'x-y'}`]",
  "$a[1-2]", "$a-b[0]", "$a[0].b-c", "$a.b-c['d-e']", "$a[$b][$c-d]", "$a['['].b-c", "$a[']'].b",
  "$a[`x]-${$b}`]", "$a[`${']'}-x`]", "$items.length-1", "$a-$b",
];

/** The text of the function body `genRx` compiles from [value]: `Function` is replaced to record it. */
const compiledSource = (value, returnsValue) => {
  const original = globalThis.Function;
  let source = null;
  let compiles = null;
  globalThis.Function = (...args) => {
    source = args[args.length - 1];
    try {
      original(...args);
      compiles = true;
    } catch {
      compiles = false;
    }
    return () => undefined;
  };
  try {
    client.genRx(value, { returnsValue_: returnsValue });
  } finally {
    globalThis.Function = original;
  }
  if (source === null) throw new Error(`genRx never compiled ${JSON.stringify(value)}`);
  return { source, compiles };
};

const expressionCases = [];
for (const expression of expressions) {
  for (const returnsValue of [false, true]) {
    const { source, compiles } = compiledSource(expression, returnsValue);
    expressionCases.push({ expression, returnsValue, js: source, compiles });
  }
}

// ------------------------------------------------------------------ tags.json

const tagSets = [
  ["500ms"], ["1s"], ["1.5s"], ["1.5ms"], ["leading", "500ms"], ["500ms", "leading"], ["500"], [],
  ["0ms"], ["0"], ["ms"], ["s"], ["1S"], ["1MS"], ["-1s"], ["1e3ms"], ["0x10"], ["1", "5s"], ["abc"],
  ["Leading"], ["noleading"], ["notrailing", "1s"], ["1.5"], [".5s"], ["5.s"], ["1_000ms"], ["Infinity"],
  ["1 s"], ["1ms "], [" 1ms"],
];

const tags = tagSets.map((set) => {
  const s = new Set(set);
  const entry = { tags: set, tagToMs: client.tagToMs(s), tagHas: {}, tagFirst: client.tagFirst(s), tagFirstDefault: client.tagFirst(s, "x") };
  for (const tag of ["leading", "Leading", "LEADING", "noleading", "trailing", "notrailing", "500ms"]) entry.tagHas[tag] = client.tagHas(s, tag);
  return entry;
});

const timingMods = [
  "debounce.500ms", "debounce.500ms.leading", "debounce.500ms.notrailing", "debounce.500ms.leading.notrailing",
  "throttle.1s", "throttle.1s.noleading", "throttle.1s.trailing", "throttle.1s.noleading.trailing", "delay.200ms",
  "delay.200ms__debounce.500ms", "debounce.500ms__throttle.1s", "delay.200ms__debounce.500ms__throttle.1s",
  "debounce.1.5s", "debounce.leading.500ms", "debounce.500", "debounce", "throttle", "delay", "debounce.0ms",
  "debounce.500ms__debounce.1s", "debounce.500ms.LEADING", "throttle.1s.NoLeading", "debounce.500ms.leading.leading",
];

/**
 * What `modifyTiming` makes of the modifiers written after `data-on:click`: the callback is called
 * twice at once with `a` and `b`, then every timer the client set is fired in the order it set
 * them, and the trace lists every call of the inner callback and every timer, with its wait.
 */
const timingTrace = (written) => {
  const { mods: parsed } = client.parseAttributeKey(`on:click__${written}`);
  const trace = [];
  const timers = [];
  const originalSet = globalThis.setTimeout;
  const originalClear = globalThis.clearTimeout;
  let nextId = 1;
  globalThis.setTimeout = (fn, wait, ...args) => {
    const id = nextId++;
    timers.push({ id, fn, wait, args });
    trace.push(`timer ${id} set ${wait}ms`);
    return id;
  };
  globalThis.clearTimeout = (id) => {
    const i = timers.findIndex((t) => t.id === id);
    if (i >= 0) {
      timers.splice(i, 1);
      trace.push(`timer ${id} cleared`);
    }
  };
  try {
    const wrapped = client.modifyTiming((...args) => trace.push(`callback ${args.join(",")}`), parsed);
    const wrappers = [];
    if (parsed.has("delay")) wrappers.push("delay");
    if (parsed.has("debounce")) wrappers.push("debounce");
    if (parsed.has("throttle")) wrappers.push("throttle");
    trace.push("call a");
    wrapped("a");
    trace.push("call b");
    wrapped("b");
    let fired = 0;
    while (timers.length && fired < 20) {
      const t = timers.shift();
      trace.push(`fire ${t.id}`);
      t.fn(...t.args);
      fired++;
    }
    return { modifiers: written, wrappers, trace };
  } finally {
    globalThis.setTimeout = originalSet;
    globalThis.clearTimeout = originalClear;
  }
};

const timing = timingMods.map(timingTrace);

// ---------------------------------------------------------- signal-store.json

/** The own properties `Object.prototype`, and the functions on it, hold right now. */
const prototypeState = () => {
  const state = new Map();
  state.set("Object.prototype", new Set(Object.getOwnPropertyNames(Object.prototype)));
  for (const name of Object.getOwnPropertyNames(Object.prototype)) {
    const v = Object.prototype[name];
    if (typeof v === "function") state.set(`Object.prototype.${name}`, new Set(Object.getOwnPropertyNames(v)));
  }
  return state;
};

/** The names [fn] added to `Object.prototype` or a function on it, removed again before it returns. */
const polluting = (fn) => {
  const before = prototypeState();
  const result = attempt(fn);
  const gained = [];
  for (const [owner, names] of prototypeState()) {
    const was = before.get(owner) ?? new Set();
    for (const name of names) {
      if (was.has(name)) continue;
      gained.push(`${owner}.${name}`);
      const target = owner === "Object.prototype" ? Object.prototype : Object.prototype[owner.slice("Object.prototype.".length)];
      delete target[name];
    }
  }
  return { result, gained };
};

const pathCases = [
  [["a", 1]], [["a.b", 1]], [["a.b", 1], ["a.c", 2]], [["a", 1], ["a.b", 2]], [["a.b", 1], ["a", 2]],
  [["a.b.c", true]], [["a.0", "x"]], [["0.a", "x"]], [["", 1]], [["a.", 1]], [[".a", 1]], [["a..b", 1]],
  [["__proto__", 1]], [["__proto__.x", 1]], [["a.__proto__.x", 1]], [["constructor", 1]], [["constructor.x", 1]],
  [["constructor.prototype.x", 1]], [["toString", 1]], [["toString.name", 1]], [["toString.x", 1]],
  [["hasOwnProperty", 1]], [["hasOwnProperty.x", 1]], [["valueOf.x", 1]], [["a.constructor", 1]],
  [["a.constructor.prototype.x", 1]], [["a.toString", 1]], [["a-b", 1]], [["a-b.c-d", 1]], [["a b", 1]],
  [["a", null]], [["a", { b: 1 }]], [["a", [1, 2]]], [["a.b", 1], ["a", { c: 2 }]],
];

const pathToObj = pathCases.map((paths) => {
  const { result, gained } = polluting(() => JSON.stringify(client.pathToObj(paths)));
  const entry = { paths };
  if ("value" in result) entry.result = result.value;
  else entry.error = result.error;
  entry.pollutes = gained;
  return entry;
});

const storeCases = [
  { store: { a: 1, _private: 2 } },
  { store: { a: { _b: 1, c: 2 }, _d: { e: 3 } } },
  { store: { user: { name: "x", address: { city: "y", _geo: 1 } }, _tmp: "z" } },
  { store: { arr: [1, { _y: 2 }], _arr: [3] } },
  { store: { a: { b: { c: { d: 1 } } } } },
  { store: { constructor: { prototype: { polluted: 1 } } } },
  { store: { constructor: { x: 1 } } },
  { store: { constructor: 1 } },
  { store: { toString: "x" } },
  { store: { toString: { name: 1 } } },
  { store: { hasOwnProperty: { x: 1 } } },
  { store: { valueOf: 1, a: 2 } },
  { store: { "a.b": 1, a: { b: 2 } } },
  { store: { "a-b": 1, a: { "b-c": 2 } } },
  { store: { a: "", b: null, c: false, d: 0 } },
  { store: { a: 1, b: { _x: 1 } } },
  { store: { _: 1, __: 2, a_: 3, a_b: 4, "a._b": 5 } },
  { store: { a: [{ b: 1 }, { _c: 2 }], _z: [1] } },
  { store: { a: { b: 1 } }, include: "^a\\.b$" },
  { store: { a: { b: 1, c: 2 }, d: 3 }, include: "^a" },
  { store: { a: { b: 1, c: 2 }, d: 3 }, include: "/^a/" },
  { store: { a: { b: 1, c: 2 }, d: 3 }, exclude: "c$" },
  { store: { a: { b: 1, c: 2 }, d: 3 }, include: "a", exclude: "b" },
  { store: { _a: 1 }, exclude: "" },
  { store: { foo: 1, foobar: 2, bar: { foo: 3 } }, include: "foo" },
  { store: { foo: 1 }, include: "FOO" },
];

const fetchExclude = /(^|\.)_/;

const resetRoot = () => {
  for (const key of Object.keys(client.root)) delete client.root[key];
};

const signalStore = storeCases.map(({ store, include, exclude }) => {
  resetRoot();
  client.mergePatch(store);
  const held = JSON.stringify(client.root);
  const filters = {};
  if (include !== undefined) filters.include = include;
  filters.exclude = exclude !== undefined ? exclude : fetchExclude;
  const { result, gained } = polluting(() => JSON.stringify(client.filtered(filters)));
  const entry = { store: JSON.stringify(store), held };
  entry.include = include !== undefined ? include : null;
  entry.exclude = exclude !== undefined ? exclude : fetchExclude.source;
  if ("value" in result) entry.filtered = result.value;
  else entry.error = result.error;
  entry.pollutes = gained;
  resetRoot();
  return entry;
});

const trueValues = [
  "true", " true ", "True", "TRUE", "1", "", "false", "true\n", "\ttrue", "\u00A0true", "\uFEFFtrue", "\u3000true",
  "\u2028true", "\u2029true", "\u001ctrue", "\u0085true", "\u200Btrue", "true ", "truee", "tru", "t rue", "\"true\"",
];
const isStringTrue = [
  ...trueValues.map((value) => ({ value, isStringTrue: client.isStringTrue(value) })),
  { value: true, isStringTrue: client.isStringTrue(true) },
  { value: 1, isStringTrue: client.isStringTrue(1) },
  { value: null, isStringTrue: client.isStringTrue(null) },
  { value: ["true"], isStringTrue: client.isStringTrue(["true"]) },
];

// -------------------------------------------------------------------- plugins.json

// What each attribute plugin of the free client declares when it registers: the plugins under
// plugins/attributes, bundled with an `@engine` whose `attribute` records its argument instead of
// queueing it. Everything else the plugins import is the reference's own.
const pluginDir = path.join(reference, "plugins", "attributes");
const recordingEngine = {
  name: "recording-engine",
  setup(build) {
    build.onResolve({ filter: /^@engine$/ }, (args) =>
      path.dirname(args.importer) === pluginDir ? { path: "recording-engine", namespace: "recording" } : undefined,
    );
    build.onLoad({ filter: /.*/, namespace: "recording" }, () => ({
      contents:
        `export * from ${JSON.stringify(path.join(reference, aliases["@engine"]))};\n` +
        "export const attribute = (plugin) => globalThis.registeredPlugins.push(plugin);\n",
      loader: "ts",
      resolveDir: reference,
    }));
  },
};
globalThis.registeredPlugins = [];
for (const name of ["HTMLSelectElement", "HTMLTextAreaElement", "HTMLElement", "HTMLFormElement", "HTMLButtonElement"]) {
  globalThis[name] ??= class {};
}
const pluginFiles = readdirSync(pluginDir).filter((f) => f.endsWith(".ts")).sort();
const pluginBundle = await build({
  stdin: { contents: pluginFiles.map((f) => `import "./plugins/attributes/${f}";`).join("\n"), resolveDir: reference, loader: "ts" },
  bundle: true,
  write: false,
  format: "esm",
  platform: "neutral",
  target: "es2022",
  define: { ALIAS: "null" },
  plugins: [recordingEngine, referenceOnly],
  logLevel: "silent",
});
await import(`data:text/javascript;base64,${Buffer.from(pluginBundle.outputFiles[0].text).toString("base64")}`);
const plugins = globalThis.registeredPlugins
  .map(({ name, requirement, returnsValue, argNames }) => ({
    name,
    requirement: requirement ?? null,
    returnsValue: returnsValue ?? false,
    argNames: argNames ?? [],
  }))
  .sort((a, b) => a.name.localeCompare(b.name));
if (plugins.length !== pluginFiles.length) throw new Error(`${pluginFiles.length} plugin files registered ${plugins.length} plugins`);

// ------------------------------------------------------------------- writing

const files = {
  "casing.json": {
    $comment:
      "kebab, camel, snake, pascal and modifyCasing(key, mods, defaultCase) from utils/text.ts of the Datastar " + version + " client, " +
      "run on each key. modifyCasing is run with no __case for the camel and kebab defaults, and with each __case value; " +
      "an {error} is what the client threw.",
    cases: casing,
  },
  "attribute-keys.json": {
    $comment:
      "parseAttributeKey(rawKey) from engine/engine.ts of the Datastar " + version + " client, with the key the browser hands over after " +
      "data-; signalCamel and signalKebab are modifyCasing(key, mods, defaultCase) from utils/text.ts on the parsed key.",
    cases: attributeKeys,
  },
  "expressions.json": {
    $comment:
      "The function body genRx (engine/engine.ts of the Datastar " + version + " client) hands to compileExpression for each expression, " +
      "with returnsValue false and true: Function was replaced to record its last argument. compiles says whether the real " +
      "Function took that body.",
    cases: { rewrites: expressionCases },
  },
  "tags.json": {
    $comment:
      "tagToMs, tagHas and tagFirst from utils/tags.ts of the Datastar " + version + " client over each tag set, and modifyTiming from " +
      "utils/timing.ts over the modifiers of data-on:click as parseAttributeKey reads them: the wrapped callback is called with " +
      "a and b, then each timer the client set is fired in order, with setTimeout and clearTimeout replaced to record. " +
      "A NaN, which JSON cannot hold, is written as null in tagToMs and as NaNms in a trace.",
    cases: { tags, timing },
  },
  "signal-store.json": {
    $comment:
      "pathToObj from utils/paths.ts, filtered and mergePatch from engine/signals.ts, and isStringTrue from utils/text.ts of the " +
      "Datastar " + version + " client. held is JSON.stringify of the root after mergePatch of store; filtered is JSON.stringify of " +
      "filtered({include, exclude}) with the fetch default exclude /(^|\\.)_/ unless given; pollutes lists what Object.prototype " +
      "or a function on it gained, removed again afterwards.",
    cases: { pathToObj, filtered: signalStore, isStringTrue },
  },
  "plugins.json": {
    $comment:
      "The argument each attribute plugin under plugins/attributes of the Datastar " + version + " client passes to attribute() " +
      "from engine/engine.ts, recorded by bundling the plugins with an @engine whose attribute() records it: requirement, " +
      "returnsValue and argNames as the plugin declares them, null, false and [] where it declares none.",
    cases: plugins,
  },
};

// ASCII only, as dom.mjs and dsl.mjs write theirs, so a byte order mark or a line separator in a case shows in a diff.
const ascii = (text) => text.replace(/[^\x00-\x7f]/g, (ch) => `\\u${ch.charCodeAt(0).toString(16).padStart(4, "0")}`);

mkdirSync(out, { recursive: true });
for (const [name, content] of Object.entries(files)) {
  writeFileSync(path.join(out, name), ascii(`${JSON.stringify(content, null, 2)}\n`));
  const count = Array.isArray(content.cases) ? content.cases.length : Object.values(content.cases).map((c) => c.length).join("+");
  console.log(`${name}: ${count} cases`);
}
