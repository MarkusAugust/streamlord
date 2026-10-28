import { clientRewrite, DOCS, type Issue } from "./expression.ts";
import type { KotlinString } from "./kotlinStrings.ts";
import { findCallSites, lexKotlin, stringAt, type Arg, type CallSite } from "./scanner.ts";

/*
 * A hyphen in a signal name written in a DSL call. The client reads one as a capital in an
 * attribute key and as a hyphen in an expression or a patch, so the SDK refuses such a name at
 * render or send time (`SignalNames.hyphenProblem` in streamlord-core); this says so in the editor,
 * with the name to write instead. `SignalNameHyphens.kt` in streamlord-analysis is the same check.
 */

/** The helpers whose first argument is the name of a signal the page declares. */
const NAMING_HELPERS: ReadonlySet<string> = new Set(["dataSignals", "dataComputed", "dataBind", "dataRef", "dataIndicator", "dataMatchMedia"]);

/**
 * The helper whose second argument may be `ifMissing` of the object form, `dataSignals("{...}", true)`,
 * as well as the expression of the keyed form. `dataComputed` takes an expression second or nothing.
 */
const KEYED_OR_OBJECT = "dataSignals";

/** The helper with an object form of one argument and a keyed form of two. */
const COMPUTED = "dataComputed";

/** The expression helpers, whose name is JavaScript: a hyphen inside brackets is a key. */
export const EXPRESSION_HELPERS: ReadonlySet<string> = new Set(["signal", "set", "setExpr", "increment", "decrement", "toggle", "not"]);

/**
 * The expression helpers whose names other APIs share in the same scope: `set` on a map, or on
 * Ktor's headers inside `headers { }`. One counts only inside the arguments of a DSL call.
 */
const SHARED_NAME_HELPERS: ReadonlySet<string> = new Set(["set", "toggle"]);

/** The calls whose pairs name signals: on the page, `dataSignals("count" to 0)`, and from the server. */
const PAIR_NAMING: ReadonlySet<string> = new Set(["dataSignals", "patchSignals"]);

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
    } else if (from !== "kotlin.collections") {
      // IntelliJ imports kotlin.collections.set for `map[k] = v`: an extension that needs a receiver.
      out.delete(name);
    }
  }
  return out;
}

/** `name` with each hyphen dropped and the character after it raised: `first-name` is `firstName`. */
export function withoutHyphens(name: string): string {
  return name.replace(/-+.?/g, (m) => m.replace(/^-+/, "").toUpperCase());
}

/**
 * `name`, a reference an expression writes after `$`, with its hyphens taken out, or null when it
 * has none that matter: one in a signal the client reads, as in `items[$i-1]`, and one outside
 * brackets and strings that no reference holds, as in `a['x'].b-c`, which JavaScript reads as a
 * minus. A quoted key is text. The same rule as `SignalNames.withoutReferenceHyphens` in streamlord-core.
 */
export function withoutReferenceHyphens(name: string): string | null {
  const text = `$${name}`;
  const read = clientRewrite(text);
  const references = read.rewrites.filter((r) => r.kind === "signal");
  const wrong = new Set<number>();
  for (const r of references) {
    for (let i = r.start; i < r.end; i++) if (text[i] === "-") wrong.add(i);
  }
  let depth = 0;
  let i = 0;
  while (i < text.length) {
    const at = i;
    const skip = read.runs.find((r) => at >= r.start && at < r.end) ?? references.find((r) => at >= r.start && at < r.end);
    if (skip) {
      i = skip.end;
      continue;
    }
    const c = text[i];
    if (c === "[") depth++;
    else if (c === "]") depth = Math.max(0, depth - 1);
    else if (c === "-" && depth === 0) wrong.add(i);
    i++;
  }
  if (wrong.size === 0) return null;
  let out = "";
  let raise = false;
  // By UTF-16 unit, as the offsets in wrong count.
  for (let k = 0; k < text.length; k++) {
    const c = text.charAt(k);
    if (wrong.has(k)) {
      raise = true;
      continue;
    }
    out += raise ? c.toUpperCase() : c;
    raise = false;
  }
  return out.slice(1);
}

/**
 * `name` with its one hyphenated part in brackets, `form.first-name` as `form['first-name']`, or
 * null when that is no simple rewrite: a name with brackets already, or more than one such part.
 */
export function bracketed(name: string): string | null {
  if (name.includes("[") || name.includes("]")) return null;
  const parts = name.split(".");
  const i = parts.findIndex((p) => p.includes("-"));
  if (i < 0 || parts.filter((p) => p.includes("-")).length > 1) return null;
  return `${parts.slice(0, i).join(".")}['${parts[i]}']${parts.slice(i + 1).map((p) => `.${p}`).join("")}`;
}

const PAIR_KEY = /^[\s(]*"/;
const PAIR_AFTER = /\s+to\b/y;
const OBJECT_START = /^\s*[{[]/;
/** What the second argument of `dataSignals(first, second)` is, as far as its text shows: the overload Kotlin picks. */
export type SecondArgument = "expression" | "ifMissing" | "unknown";

/** An operator whose result is a Boolean, outside strings. ktlint spaces `<` and `>` when they compare, and not in a type argument. */
const BOOLEAN_OPERATOR = /==|!=|&&|\|\||\s[<>]=?\s|\s!?(?:in|is)\s/;

/** A call of a helper that writes an expression, which is a String. */
const EXPRESSION_CALL = /^(?:signal|not|set|setExpr|increment|decrement|toggle)\s*\(/;

/**
 * Whether `arg`, the second argument of `dataSignals`, is the expression of the keyed form, a
 * String, or `ifMissing` of the object form, a Boolean. The same rule as `secondArgument` in
 * streamlord-analysis; anything its text does not settle may be either, and only the compiler knows.
 */
export function secondArgument(arg: Arg | undefined, strings: KotlinString[]): SecondArgument {
  if (arg === undefined) return "unknown";
  if (arg.string !== null) return "expression";
  const inside = strings.filter((s) => s.start >= arg.start && s.end <= arg.end);
  const code = [...arg.text].map((c, i) => (inside.some((s) => arg.start + i >= s.start && arg.start + i < s.end) ? " " : c)).join("");
  const text = arg.text.trim();
  if (text === "true" || text === "false" || code.trim().startsWith("!") || BOOLEAN_OPERATOR.test(` ${code} `)) return "ifMissing";
  const first = arg.text.search(/\S/);
  const leading = inside.find((s) => s.start === arg.start + first);
  if (leading) {
    const after = arg.text.slice(leading.end - arg.start).trim();
    if (after === "" || after.startsWith("+")) return "expression";
  }
  // A call of an expression helper, alone or with more joined to it by `+`.
  const trimmed = code.trimStart();
  const call = EXPRESSION_CALL.exec(trimmed);
  if (!call) return "unknown";
  let depth = 0;
  for (let i = code.length - trimmed.length + call[0].length - 1; i < code.length; i++) {
    if (code[i] === "(") depth++;
    if (code[i] === ")" && --depth === 0) {
      const after = code.slice(i + 1).trim();
      return after === "" || after.startsWith("+") ? "expression" : "unknown";
    }
  }
  return "unknown";
}

/** The issues of `site`, a call in `SIGNAL_NAMING_CALLS`, for each signal name in it that holds a hyphen. */
export function signalNameHyphenIssues(site: CallSite, src: string, strings: KotlinString[], helpers: ReadonlySet<string>, insideDslCall: (at: number) => boolean): Issue[] {
  const out: Issue[] = [];
  const positional = site.args.filter((a) => a.named === null);
  const first = positional[0];
  const named = (n: string) => site.args.find((a) => a.named === n);

  if (EXPRESSION_HELPERS.has(site.name)) {
    // cache.set("user-id", x), Hamcrest's not("x") and a header's set("datastar-request", ...) are somebody else's.
    let i = site.nameStart - 1;
    while (i >= 0 && /\s/.test(src[i] ?? "")) i--;
    if (!helpers.has(site.name) || src[i] === ".") return out;
    if (SHARED_NAME_HELPERS.has(site.name) && !insideDslCall(site.nameStart)) return out;
    const s = (first ?? named("name"))?.string;
    if (!s) return out;
    const fixed = withoutReferenceHyphens(s.text);
    if (fixed !== null) out.push(hyphenIssue(s, src, fixed, bracketed(s.text)));
    return out;
  }

  if (NAMING_HELPERS.has(site.name)) {
    // dataSignals("{...}") and dataSignals("{...}", flag) are the object form: a second argument
    // that is a String makes the first a name, and one that may be either is left alone.
    const f = first?.string;
    const isKeyed = (text: string): boolean => {
      if (site.name === KEYED_OR_OBJECT) return !OBJECT_START.test(text) && (named("expression") !== undefined || secondArgument(positional[1], strings) === "expression");
      if (site.name === COMPUTED) return named("expression") !== undefined || positional.length > 1;
      return true;
    };
    const keyed = f && isKeyed(f.text) ? f : null;
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
  const written = src.slice(s.contentStart, s.contentEnd);
  // A name with escapes in its source is reported and left for the author to rewrite, and so is
  // one with a template in it, whose hyphen is in the text around the template. An escaped `$`,
  // as in "items[\$i-1]", is the one escape the fix writes back.
  const dollars = written !== s.text && written === s.text.replaceAll("$", "\\$");
  const plain = s.interpolations.length === 0 && (written === s.text || dollars);
  const text = dollars ? fixed.replaceAll("$", "\\$") : fixed;
  const advice = plain ? `Write ${fixed}${bracketedName ? `, or ${bracketedName} for a key with a hyphen in it` : ""}` : "Write it in camelCase";
  return {
    start: s.start,
    end: s.end,
    message: `A signal name holds no hyphen: Datastar's client reads one as a capital in an attribute key and as a hyphen in an expression or a patch, so Streamlord refuses "${written}" at runtime. ${advice}.`,
    severity: "error",
    code: "signal-name-hyphen",
    link: DOCS.casing,
    fixes: plain ? [{ title: `Change to ${fixed}`, start: s.contentStart, end: s.contentEnd, text }] : [],
  };
}

/**
 * The issues of the calls of `src` whose signal names hold a hyphen, including the calls inside a
 * string template, `"${signal("is-running")} ? 'Stop' : 'Go'"`, which the scanner reads as code of
 * their own. `dslCalls` names the calls whose arguments a shared-name helper counts inside.
 */
export function signalNameHyphenSites(src: string, lex: { strings: KotlinString[]; mask: Uint8Array }, helpers: ReadonlySet<string>, dslCalls: ReadonlySet<string>): Issue[] {
  const out: { at: number; issues: Issue[] }[] = [];
  collectHyphenSites(src, lex, helpers, dslCalls, false, 0, 0, out);
  return out.sort((a, b) => a.at - b.at).flatMap((o) => o.issues);
}

function collectHyphenSites(
  text: string,
  lex: { strings: KotlinString[]; mask: Uint8Array },
  helpers: ReadonlySet<string>,
  dslCalls: ReadonlySet<string>,
  enclosed: boolean,
  shift: number,
  depth: number,
  out: { at: number; issues: Issue[] }[],
): void {
  const calls = findCallSites(text, dslCalls, lex.mask);
  // Inside the arguments of a DSL call, and not in a lambda there: `buildMap { set("first-name", "") }` is a map's.
  const inside = (at: number): boolean =>
    calls.some((c) => at > c.openParen && at < c.closeParen && !openBraceBetween(text, lex.mask, c.openParen, at)) || (enclosed && !openBraceBetween(text, lex.mask, 0, at));
  for (const site of findCallSites(text, SIGNAL_NAMING_CALLS, lex.mask)) {
    const issues = signalNameHyphenIssues(site, text, lex.strings, helpers, inside);
    if (issues.length > 0) out.push({ at: site.nameStart + shift, issues: issues.map((i) => shifted(i, shift)) });
  }
  if (depth >= 4) return;
  for (const s of lex.strings) {
    for (const t of s.interpolations) {
      if (t.kind !== "braced") continue;
      const open = text.indexOf("{", t.start);
      if (open < 0 || open + 1 > t.end - 1 || text.slice(open + 1, t.end - 1) !== t.text) continue;
      collectHyphenSites(t.text, lexKotlin(t.text), helpers, dslCalls, inside(t.start), shift + open + 1, depth + 1, out);
    }
  }
}

/** Whether a `{` of code between `from` and `to` is still open at `to`. */
function openBraceBetween(text: string, mask: Uint8Array, from: number, to: number): boolean {
  let depth = 0;
  for (let i = from; i < Math.min(to, text.length); i++) {
    if (mask[i] === 0) continue;
    if (text[i] === "{") depth++;
    else if (text[i] === "}") depth = Math.max(0, depth - 1);
  }
  return depth > 0;
}

function shifted(issue: Issue, by: number): Issue {
  if (by === 0) return issue;
  return { ...issue, start: issue.start + by, end: issue.end + by, fixes: issue.fixes?.map((f) => ({ ...f, start: f.start + by, end: f.end + by })) };
}
