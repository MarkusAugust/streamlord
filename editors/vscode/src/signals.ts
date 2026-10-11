import { PLACEHOLDER } from "./kotlinStrings.ts";
import { findCallSites } from "./scanner.ts";

/**
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

const KOTLIN_PATTERNS: RegExp[] = [
  /\b(?:signal|set|setExpr|increment|decrement|toggle|not|dataBind|dataIndicator|dataRef|dataComputed|dataMatchMedia)\(\s*"([A-Za-z_][A-Za-z0-9_.]*)"/g,
  /\bdataSignals\(\s*"([A-Za-z_][A-Za-z0-9_.]*)"\s*,/g,
];

/** A dollar escaped in a Kotlin string: a signal read, which completion offers and a definition is not. */
const KOTLIN_READS: RegExp[] = [/\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)/g, /\\\$([A-Za-z_][A-Za-z0-9_.]*)/g];

/** `$name = ...` in an expression: Datastar creates a signal it is assigned to, so the assignment defines it. */
const ASSIGNED_SIGNAL = /(?:\$|\$\{'\$'\})([A-Za-z_][A-Za-z0-9_.]*)\s*=(?![=>])/g;

/** Declarations in markup. These also run over Kotlin, where they only ever match inside HTML strings. */
const HTML_ATTRIBUTE_PATTERNS: RegExp[] = [
  /data-(?:star-)?(?:signals|bind|indicator|ref|computed|match-media):([A-Za-z_][A-Za-z0-9_.-]*)/g,
  /data-(?:star-)?(?:bind|indicator|ref)(?:__[^=\s]*)?="([A-Za-z_][A-Za-z0-9_.]*)"/g,
  // The object form may sit in either kind of quote: JSON written by hand is `data-signals='{"query": ""}'`.
  /data-(?:star-)?signals(?:__[^=\s]*)?=(?:"\{([^"]*)\}"|'\{([^']*)\}')/g,
];

/** A key of the object form: bare or quoted, and inside a Kotlin string quoted with escaped quotes. */
const OBJECT_KEY = /(?:^|[{,])\s*(?:\\?["'])?([A-Za-z_][A-Za-z0-9_]*)(?:\\?["'])?\s*:/g;

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
const CASE_MODIFIER = /__case\.([a-z]+)/;
const PAIR = /"([A-Za-z_][A-Za-z0-9_.]*)"\s+to\b/g;
const NAME = /^"([A-Za-z_][A-Za-z0-9_.]*)"$/;

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
        for (const pair of a.text.matchAll(PAIR)) if (pair[1]) out.add(pair[1]);
        // patchSignals("""{"count": 1}"""): the signals as JSON text.
        if (JSON_CALLS.has(site.name) && a.string && /^\s*\{/.test(a.string.text)) {
          for (const key of a.string.text.matchAll(OBJECT_KEY)) if (key[1]) out.add(key[1]);
        }
        const name = NAME.exec(a.text);
        if (!name?.[1]) return;
        // removeSignals("a", "b") and dataSignals("name", "expression") take names positionally.
        if (site.name === "removeSignals" || (site.name === "dataSignals" && idx === 0 && positional[1]?.string)) out.add(name[1]);
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
      const keyed = re === HTML_ATTRIBUTE_PATTERNS[0];
      const captured = keyed ? (m[1] ?? "").split("__")[0] ?? "" : (m[1] ?? m[2] ?? "");
      if (re.source.includes("signals(?:__")) {
        for (const key of captured.matchAll(OBJECT_KEY)) {
          if (key[1]) out.add(key[1]);
        }
      } else if (captured) {
        // `__case` names the signal: data-signals:my-value__case.snake is `$my_value`.
        out.add(keyed ? keyName(captured, CASE_MODIFIER.exec(m[1] ?? "")?.[1] ?? null) : toCamel(captured));
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

/**
 * The signal a kebab-case key names under a `__case` modifier: camel by default (`foo-bar` is
 * `fooBar`), `snake` (`foo_bar`), `pascal` (`FooBar`) or `kebab` (`foo-bar`, as written).
 */
export function keyName(key: string, kase: string | null): string {
  switch (kase) {
    case "kebab":
      return key;
    case "snake":
      return key.replaceAll("-", "_");
    case "pascal": {
      const camel = toCamel(key);
      return camel.charAt(0).toUpperCase() + camel.slice(1);
    }
    default:
      return toCamel(key);
  }
}

/** Datastar exposes kebab-case keys as camelCase signals by default. */
function toCamel(name: string): string {
  return name.includes("-") ? name.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase()) : name;
}
