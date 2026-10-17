import { PLACEHOLDER } from "./kotlinStrings.ts";
import { findCallSites } from "./scanner.ts";

/**
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

// A kebab-case name is written as a kebab-case key, which Datastar reads as camelCase: signal("foo-bar") is $fooBar.
const KOTLIN_PATTERNS: RegExp[] = [
  /\b(?:signal|set|setExpr|increment|decrement|toggle|not|dataBind|dataIndicator|dataRef|dataComputed|dataMatchMedia)\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"/g,
  /\bdataSignals\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"\s*,/g,
];

/** A dollar escaped in a Kotlin string: a signal read, which completion offers and a definition is not. */
const KOTLIN_READS: RegExp[] = [/\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)/g, /\\\$([A-Za-z_][A-Za-z0-9_.]*)/g];

/** `$name = ...` in an expression: Datastar creates a signal it is assigned to, so the assignment defines it. */
const ASSIGNED_SIGNAL = /(?:\$|\$\{'\$'\})([A-Za-z_][A-Za-z0-9_.]*)\s*=(?![=>])/g;

/**
 * Declarations in markup, read as the client stores them. These also run over Kotlin, where they
 * only ever match inside HTML strings. The browser lowercases an attribute name, so the name is
 * matched in any case and its key is lowercased: `data-signals:fooBar` declares `foobar`.
 */
const HTML_ATTRIBUTE_PATTERNS: RegExp[] = [
  // The key with its modifiers, which may name the casing: data-signals:my-value__case.snake.
  /data-(?:star-)?(?:signals|bind|indicator|ref|computed|match-media):([^\s=>"'/\\]+)/gi,
  // The value form names the signal as written: data-bind="user-name" is the signal user-name.
  /data-(?:star-)?(?:bind|indicator|ref)(?:__[^=\s>]*)?=(?:\\?"([^"\\\s]+)\\?"|'([^'\s]+)')/gi,
  // The object form may sit in either kind of quote: JSON written by hand is `data-signals='{"query": ""}'`.
  // data-computed="{total: () => ...}" declares its keys the same way.
  /data-(?:star-)?(?:signals|computed)(?:__[^=\s>]*)?=(?:"\{([^"]*)\}"|'\{([^']*)\}')/gi,
];


/** A bare `$name` is a signal in markup; in Kotlin it is a template, so it stays out of the Kotlin list. */
const HTML_PATTERNS: RegExp[] = [...HTML_ATTRIBUTE_PATTERNS, /\$([A-Za-z_][A-Za-z0-9_.]*)/g];

// Any modifier may stand between the annotation and `class`: an explicit-API module writes
// `@Serializable public data class`.
const SERIALIZABLE_CLASS = /@Serializable\s*(?:\([^)]*\))?\s*(?:(?:public|internal|private|protected|open|final|abstract|sealed|value|inline|data)\s+)*class\s+[A-Za-z_][A-Za-z0-9_]*\s*\(/g;
// `@SerialName` renames the signal, so it is read with whatever annotations and modifiers stand
// between it and the property.
const PROPERTY = /(?:@SerialName\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:\w+\s+)*?)?\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:/g;

const PAIR_CALLS: ReadonlySet<string> = new Set(["dataSignals", "patchSignals", "removeSignals", "respondSignals", "datastarSignals"]);
const JSON_CALLS: ReadonlySet<string> = new Set(["patchSignals", "respondSignals", "datastarSignals"]);
const PAIR = /"([A-Za-z_][A-Za-z0-9_.-]*)"\s+to\b/g;
const NAME = /^"([A-Za-z_][A-Za-z0-9_.-]*)"$/;

export function collectSignals(src: string, language: "kotlin" | "html"): Set<string> {
  return collect(src, language, true);
}

/**
 * The signals a source defines, which a `$name` in an expression is checked against: what
 * [collectSignals] finds without the reads (a bare `$name` in markup, a `${'$'}name` in Kotlin),
 * plus every signal an expression assigns to.
 */
export function collectSignalDefinitions(src: string, language: "kotlin" | "html"): Set<string> {
  const out = collect(src, language, false);
  for (const m of src.matchAll(ASSIGNED_SIGNAL)) if (m[1] && !m[1].includes(PLACEHOLDER)) out.add(m[1]);
  return out;
}

function collect(src: string, language: "kotlin" | "html", reads: boolean): Set<string> {
  const out = new Set<string>();
  if (language === "kotlin") {
    for (const site of findCallSites(src, PAIR_CALLS)) {
      const positional = site.args.filter((a) => a.named === null);
      positional.forEach((a, idx) => {
        // dataSignals("foo-bar" to 1) declares $fooBar, as the keyed form does; a patch keeps the key.
        for (const pair of a.text.matchAll(PAIR)) if (pair[1]) out.add(site.name === "dataSignals" ? toCamel(pair[1]) : pair[1]);
        // patchSignals("""{"count": 1}"""): the signals as JSON text.
        if (JSON_CALLS.has(site.name) && a.string && /^\s*\{/.test(a.string.text)) {
          const json = a.string.text;
          for (const path of objectKeyPaths(json.slice(json.indexOf("{") + 1, json.lastIndexOf("}")))) out.add(path);
        }
        const name = NAME.exec(a.text);
        if (!name?.[1]) return;
        // removeSignals("a", "b") names keys as they are; dataSignals("name", "expression") writes a key Datastar camel-cases.
        if (site.name === "removeSignals") out.add(name[1]);
        if (site.name === "dataSignals" && idx === 0 && positional[1]?.string) out.add(toCamel(name[1]));
      });
    }
  }
  const patterns =
    language === "kotlin" ? [...KOTLIN_PATTERNS, ...(reads ? KOTLIN_READS : []), ...HTML_ATTRIBUTE_PATTERNS] : reads ? HTML_PATTERNS : HTML_ATTRIBUTE_PATTERNS;
  for (const re of patterns) {
    re.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(src)) !== null) {
      // A keyed attribute may carry modifiers after the key: data-signals:foo-bar__ifmissing. The
      // object form's body is captured whole, `__` and all.
      const captured = m[1] ?? m[2] ?? "";
      if (re === HTML_ATTRIBUTE_PATTERNS[2]) {
        for (const path of objectKeyPaths(captured)) out.add(path);
      } else if (re === HTML_ATTRIBUTE_PATTERNS[0]) {
        // `__case` names the signal: data-signals:my-value__case.snake is `$my_value`.
        out.add(clientKeyName(captured.replace(/[A-Z]/g, (c) => c.toLowerCase())));
      } else if (re === HTML_ATTRIBUTE_PATTERNS[1]) {
        out.add(captured);
      } else if (captured) {
        out.add(toCamel(captured));
      }
    }
  }
  if (language === "kotlin") {
    SERIALIZABLE_CLASS.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = SERIALIZABLE_CLASS.exec(src)) !== null) {
      const open = m.index + m[0].length - 1;
      const close = matchingParen(src, open);
      if (close < 0) continue;
      for (const p of src.slice(open, close).matchAll(PROPERTY)) out.add(p[1] ?? p[2] ?? "");
    }
  }
  // The placeholder the analysis writes for a Kotlin template is never a signal, whatever file it turns up in.
  for (const name of out) if (name.includes(PLACEHOLDER)) out.delete(name);
  return out;
}

/** The offset of the `)` that closes the `(` at `open`, or -1. Nesting only; a parenthesis in a string is rare here. */
function matchingParen(src: string, open: number): number {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "(") depth++;
    else if (src[i] === ")" && --depth === 0) return i;
  }
  return -1;
}

/** The properties of `Object.prototype` in a browser: every object already has them (`SignalNames` in streamlord-core). */
export const PROTOTYPE_NAMES: ReadonlySet<string> = new Set([
  "__proto__",
  "constructor",
  "hasOwnProperty",
  "isPrototypeOf",
  "propertyIsEnumerable",
  "toLocaleString",
  "toString",
  "valueOf",
  "__defineGetter__",
  "__defineSetter__",
  "__lookupGetter__",
  "__lookupSetter__",
]);

/**
 * Why the client cannot hold a signal at `path`, written with dots as `data-signals:a.b` writes it,
 * or null when it can. The client follows the path without a guard, so a part every object already
 * has writes into the built-in one: `__proto__.x` and `constructor.prototype.x` set `x` on every
 * object of the page (`SignalNames.pathProblem` in streamlord-core says the same).
 */
export function signalPathProblem(path: string): string | null {
  const parts = path.split(".");
  if (parts.some((p) => p.length === 0)) return "has an empty part, which no $ reference reaches";
  const part = parts.find((p) => PROTOTYPE_NAMES.has(p));
  return part ? `has the part ${part}, which every object already has, so the client writes into the built-in one instead of holding a signal` : null;
}

/** What the client reads after a `$`: ASCII letters, digits and `_`, in parts joined by `.` or `-` (engine.ts:449 in 1.0.4). */
const REACHABLE_NAME = /^[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*$/;

/** Why no `$` reference reaches a signal named `name`, or null when one does. */
export function signalNameProblem(name: string): string | null {
  if (REACHABLE_NAME.test(name)) return null;
  if (name.startsWith("$")) return `holds the $ itself, so $${name.slice(1)} does not reach it: that reads the signal ${name.slice(1)}`;
  if (/[^\x00-\x7F]/.test(name)) return "no $ reference reaches: Datastar reads only ASCII letters, digits and _ after a $, so the browser looks for a JavaScript variable instead";
  return "no $ reference reaches: Datastar reads ASCII letters, digits and _ after a $, in parts joined by . or -";
}

const CASE_FNS: Record<string, (s: string) => string> = {
  camel: (s) => s.replace(/-[a-z]/g, (x) => x[1]!.toUpperCase()),
  snake: (s) => s.replace(/-/g, "_"),
  pascal: (s) => (s[0] ?? "").toUpperCase() + CASE_FNS.camel!(s.slice(1)),
};

/**
 * The signal the client makes of an attribute key written with its modifiers, as the browser hands
 * it over: `parseAttributeKey` (engine.ts) cuts the modifiers off at the first `__`, and
 * `modifyCasing` (text.ts) applies each `__case` given, in order, camel when none is. A casing it
 * does not know, kebab among them, leaves the key as it is.
 */
export function clientKeyName(written: string): string {
  const [key = "", ...rawModifiers] = written.split("__");
  const modifiers = new Map<string, string[]>();
  for (const raw of rawModifiers) {
    const [label = "", ...args] = raw.split(".");
    modifiers.set(label, [...new Set(args)]);
  }
  let name = key;
  for (const c of modifiers.get("case") ?? ["camel"]) name = CASE_FNS[c]?.(name) || name;
  return name;
}

/**
 * The signal paths the object form of `data-signals` or `data-computed` declares, `body` being the
 * text between its outer braces: each key as written, and the keys of an object under a key with
 * that key before them, as the client's `mergePatch` stores them. `{user: {name: 'x'}}` declares
 * `user` and `user.name`. A key may be bare or quoted, and inside a Kotlin string its quotes may be
 * escaped; an object that is not the value of a key, inside a function or a list, declares nothing.
 */
export function objectKeyPaths(body: string): string[] {
  return objectKeys(body).map((k) => k.path);
}

/** A key of the object form: its path, where it is written, where its value starts, and whether that value is an object. */
export interface ObjectKey {
  path: string;
  key: string;
  start: number;
  end: number;
  valueStart: number;
  object: boolean;
}

/** The keys `objectKeyPaths` names, with where each is written in `body`. */
export function objectKeys(body: string): ObjectKey[] {
  const out: ObjectKey[] = [];
  // The open brackets: the path of an object that is the value of a key, or null for anything else.
  const frames: (string | null)[] = [""];
  let expectKey = true;
  let objectFor: string | null = null;
  let i = 0;
  const skipSpace = () => {
    while (i < body.length && /\s/.test(body[i]!)) i++;
  };
  while (i < body.length) {
    const top = frames[frames.length - 1];
    if (expectKey && top !== null && top !== undefined) {
      expectKey = false;
      skipSpace();
      let key: string | null = null;
      const keyStart = i;
      const escaped = body[i] === "\\" && (body[i + 1] === '"' || body[i + 1] === "'");
      const quote = escaped ? body[i + 1] : body[i];
      if (quote === '"' || quote === "'") {
        const from = i + (escaped ? 2 : 1);
        let j = from;
        while (j < body.length && body[j] !== quote && !(body[j] === "\\" && body[j + 1] === quote)) j++;
        key = body.slice(from, j);
        i = j + (body[j] === "\\" ? 2 : 1);
      } else {
        const m = /^[A-Za-z0-9_$]+/.exec(body.slice(i));
        if (m) {
          key = m[0];
          i += key.length;
        }
      }
      const keyEnd = i;
      skipSpace();
      if (key !== null && body[i] === ":") {
        const path = top === "" ? key : `${top}.${key}`;
        i++;
        skipSpace();
        objectFor = body[i] === "{" ? path : null;
        out.push({ path, key, start: keyStart, end: keyEnd, valueStart: i, object: objectFor !== null });
      }
      continue;
    }
    const c = body[i]!;
    if (c === '"' || c === "'" || c === "`") {
      let j = i + 1;
      while (j < body.length && body[j] !== c) j += body[j] === "\\" ? 2 : 1;
      i = j + 1;
      continue;
    }
    if (c === "{") {
      frames.push(objectFor);
      expectKey = objectFor !== null;
    } else if (c === "[" || c === "(") frames.push(null);
    else if ((c === "}" || c === "]" || c === ")") && frames.length > 1) frames.pop();
    else if (c === ",") expectKey = true;
    objectFor = null;
    i++;
  }
  return out;
}

/** Datastar exposes kebab-case keys as camelCase signals by default. */
function toCamel(name: string): string {
  return name.includes("-") ? name.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase()) : name;
}
