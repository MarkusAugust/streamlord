import { DOCS, type Issue } from "./expression.ts";
import type { KotlinString } from "./kotlinStrings.ts";
import { stringAt, type Arg, type CallSite } from "./scanner.ts";

/*
 * A hyphen in a signal name written in a DSL call. The client reads one as a capital in an
 * attribute key and as a hyphen in an expression or a patch, so the SDK refuses such a name at
 * render or send time (`SignalNames.hyphenProblem` in streamlord-core); this says so in the editor,
 * with the name to write instead. `SignalNameHyphens.kt` in streamlord-analysis is the same check.
 */

/** The helpers whose first argument is the name of a signal the page declares. */
const NAMING_HELPERS: ReadonlySet<string> = new Set(["dataSignals", "dataComputed", "dataBind", "dataRef", "dataIndicator", "dataMatchMedia"]);

/** The helpers that take an object form as well as a name: one string argument alone is the object form. */
const KEYED_OR_OBJECT: ReadonlySet<string> = new Set(["dataSignals", "dataComputed"]);

/** The expression helpers, whose name is JavaScript: a hyphen inside brackets is a key. */
export const EXPRESSION_HELPERS: ReadonlySet<string> = new Set(["signal", "set", "setExpr", "increment", "decrement", "toggle", "not"]);

/** The calls whose pairs name signals: on the page, `dataSignals("count" to 0)`, and from the server. */
const PAIR_NAMING: ReadonlySet<string> = new Set(["dataSignals", "patchSignals", "respondSignals", "datastarSignals"]);

/** Every call this check reads. */
export const SIGNAL_NAMING_CALLS: ReadonlySet<string> = new Set([...NAMING_HELPERS, ...EXPRESSION_HELPERS, ...PAIR_NAMING, "removeSignals"]);

/** The DSL helpers whose first argument names a signal they read: `signal("count")` is `$count`. */
export const HELPER_READ_CALLS: ReadonlySet<string> = new Set(["signal", "increment", "decrement", "not"]);

/**
 * A file that uses the DSL: a helper call elsewhere, `redis.increment("visits")` or Hamcrest's
 * `not("error")`, is somebody else's.
 */
const USES_HTML_DSL = /^\s*(?:import\s+io\.github\.markusaugust\.streamlord\.html\.|package\s+io\.github\.markusaugust\.streamlord\.html\b)/m;

/** An import of a name from outside the DSL: `import org.hamcrest.Matchers.not`, or `.*` of Hamcrest's matchers. */
const FOREIGN_IMPORT = /^\s*import\s+(?!io\.github\.markusaugust\.streamlord\.html\.)([A-Za-z_][\w.]*)\.(\w+|\*)/gm;

/** The helpers of `names` that `src` reads as the DSL's: those no import from elsewhere puts in their place. */
export function dslHelpers(src: string, names: ReadonlySet<string> = HELPER_READ_CALLS): Set<string> {
  if (!USES_HTML_DSL.test(src)) return new Set();
  const out = new Set(names);
  for (const m of src.matchAll(FOREIGN_IMPORT)) {
    const [, from = "", name = ""] = m;
    if (name === "*") {
      if (from.startsWith("org.hamcrest")) out.delete("not");
    } else {
      out.delete(name);
    }
  }
  return out;
}

/** `name` with each hyphen dropped and the character after it raised: `first-name` is `firstName`. */
export function withoutHyphens(name: string): string {
  return name.replace(/-+.?/g, (m) => m.replace(/^-+/, "").toUpperCase());
}

/** `withoutHyphens` outside `[...]`, where a quoted key such as `['sub-total']` is text. */
export function withoutHyphensOutsideBrackets(name: string): string {
  let out = "";
  let depth = 0;
  let raise = false;
  for (const c of name) {
    if (c === "[") depth++;
    else if (c === "]") depth = Math.max(0, depth - 1);
    if (depth === 0 && c === "-") {
      raise = true;
      continue;
    }
    out += raise ? c.toUpperCase() : c;
    raise = false;
  }
  return out;
}

/** The first hyphen outside `[...]`, or -1. */
function hyphenOutsideBrackets(name: string): number {
  let depth = 0;
  for (let i = 0; i < name.length; i++) {
    const c = name[i];
    if (c === "[") depth++;
    else if (c === "]") depth = Math.max(0, depth - 1);
    else if (c === "-" && depth === 0) return i;
  }
  return -1;
}

/** `name` with the part holding the hyphen at `at` put in brackets: `form.first-name` is `form['first-name']`. */
function bracketed(name: string, at: number): string {
  const from = name.lastIndexOf(".", at) + 1;
  const dot = name.indexOf(".", at);
  const open = name.indexOf("[", at);
  const ends = [dot, open].filter((i) => i >= 0);
  const to = ends.length > 0 ? Math.min(...ends) : name.length;
  return `${name.slice(0, Math.max(0, from - 1))}['${name.slice(from, to)}']${name.slice(to)}`;
}

const PAIR_KEY = /^[\s(]*"/;
const PAIR_AFTER = /\s+to\b/y;
const OBJECT_START = /^\s*[{[]/;
const BOOLEAN = /^\s*(?:true|false)\s*$/;

function secondIsExpression(positional: Arg[]): boolean {
  const second = positional[1];
  return second !== undefined && !BOOLEAN.test(second.text);
}

/** The issues of `site`, a call in `SIGNAL_NAMING_CALLS`, for each signal name in it that holds a hyphen. */
export function signalNameHyphenIssues(site: CallSite, src: string, strings: KotlinString[], helpers: ReadonlySet<string>): Issue[] {
  const out: Issue[] = [];
  const positional = site.args.filter((a) => a.named === null);
  const first = positional[0];
  const named = (n: string) => site.args.find((a) => a.named === n);

  if (EXPRESSION_HELPERS.has(site.name)) {
    // set("user-id", x) on a cache or Hamcrest's not("x") is somebody else's.
    let i = site.nameStart - 1;
    while (i >= 0 && /\s/.test(src[i] ?? "")) i--;
    if (!helpers.has(site.name) || src[i] === ".") return out;
    const s = (first ?? named("name"))?.string;
    if (!s) return out;
    const at = hyphenOutsideBrackets(s.text);
    if (at >= 0) out.push(hyphenIssue(s, src, withoutHyphensOutsideBrackets(s.text), bracketed(s.text, at)));
    return out;
  }

  if (NAMING_HELPERS.has(site.name)) {
    // dataSignals("{...}") and dataSignals("{...}", true) are the object form: a second argument
    // that is not ifMissing makes the first a name.
    const f = first?.string;
    const keyed =
      f && (!KEYED_OR_OBJECT.has(site.name) || (!OBJECT_START.test(f.text) && (named("expression") !== undefined || secondIsExpression(positional)))) ? f : null;
    const s = (named("name") ?? named("signal"))?.string ?? keyed;
    if (s && s.text.includes("-")) out.push(hyphenIssue(s, src, withoutHyphens(s.text), null));
  }

  if (site.name === "removeSignals") {
    for (const a of positional) {
      if (a.string && a.string.text.includes("-")) out.push(hyphenIssue(a.string, src, withoutHyphens(a.string.text), null));
    }
  }

  if (PAIR_NAMING.has(site.name)) {
    for (const a of positional) {
      if (a.string) continue;
      const m = PAIR_KEY.exec(a.text);
      if (!m) continue;
      const quote = a.start + m[0].length - 1;
      const s = stringAt(src, quote + 1, strings);
      if (!s || s.start !== quote) continue;
      PAIR_AFTER.lastIndex = s.end;
      if (!PAIR_AFTER.test(src)) continue;
      if (s.text.includes("-")) out.push(hyphenIssue(s, src, withoutHyphens(s.text), null));
    }
  }
  return out;
}

function hyphenIssue(s: KotlinString, src: string, fixed: string, bracketedName: string | null): Issue {
  const name = s.text;
  // A name with escapes in its source is reported and left for the author to rewrite.
  const plain = s.interpolations.length === 0 && src.slice(s.contentStart, s.contentEnd) === name;
  return {
    start: s.start,
    end: s.end,
    message:
      `A signal name holds no hyphen: Datastar's client reads one as a capital in an attribute key and as a hyphen ` +
      `in an expression or a patch, so Streamlord refuses "${name}" at runtime. Write ${fixed}` +
      (bracketedName ? `, or ${bracketedName} for a key with a hyphen in it` : "") +
      ".",
    severity: "error",
    code: "signal-name-hyphen",
    link: DOCS.casing,
    fixes: plain ? [{ title: `Change to ${fixed}`, start: s.contentStart, end: s.contentEnd, text: fixed }] : [],
  };
}
