import { nameProblem as keyProblem } from "./attributeKeys.ts";

/*
 * The signal names the DSL refuses: the twin of `SignalNames` in streamlord-core, which the DSL
 * throws with and the Kotlin analysis reports, held to it by the shared corpus.
 */

/** The properties of `Object.prototype` in a browser: every object already has them. */
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

/** What Datastar's client finds behind a `$`, less the hyphen a signal name may not hold: ASCII word characters, in parts joined by `.`. */
const SIGNAL_NAME = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$/;

/** `name` with each hyphen dropped and the character after it raised: `first-name` is `firstName`. */
export function withoutHyphens(name: string): string {
  return name.replace(/-+.?/g, (m) => m.replace(/^-+/, "").toUpperCase());
}

/** Why `name` cannot name a signal in code, or null when it can: it has a hyphen, which the client reads as a capital in a key only. */
export function hyphenProblem(name: string): string | null {
  if (!name.includes("-")) return null;
  return `has a hyphen, which Datastar's client reads as a capital in an attribute key and as a hyphen in an expression or a patch, so it would name two signals; write ${withoutHyphens(name)}`;
}

/** Why the client cannot hold `path`, written with dots as `data-signals:a.b` writes it, or null when it can. */
export function pathProblem(path: string): string | null {
  const parts = path.split(".");
  if (parts.some((p) => p.length === 0)) return "has an empty part, which no $ reference reaches";
  const part = parts.find((p) => PROTOTYPE_NAMES.has(p));
  return part === undefined ? null : `has the part ${part}, which every object already has, so the client cannot hold a signal there`;
}

/** Why `name` cannot declare a signal in code, or null when it can: `SignalNames.nameProblem`. */
export function nameProblem(name: string): string | null {
  return keyProblem(name) ?? hyphenProblem(name) ?? pathProblem(name) ??
    (SIGNAL_NAME.test(name) ? null : "is not a name a $ reference reaches: Datastar reads ASCII letters, digits and _, in parts joined by dots");
}

/**
 * Why `Casing.signalName` refuses `name`, as `nameProblem` says, or null. Not for a hyphen alone,
 * which the hyphen check reports with the name to write instead.
 */
export function signalNameRefusal(name: string): string | null {
  const problem = nameProblem(name);
  return problem !== null && problem !== hyphenProblem(name) ? problem : null;
}
