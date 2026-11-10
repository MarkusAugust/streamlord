/*
 * What a value's fact says of it, written into markup: the one place the analysis asks which fact
 * a value has, so a rule is given once and named. The twin of ValueVerdict.kt; the table both
 * hold is in twins/value-verdict.json. A fact of null is a value of unknown type.
 */

import type { HoleFact } from "./analyze.ts";
import type { Position } from "./holes.ts";
import type { ArgKind, TextKind } from "./interpolation.ts";

/**
 * What a fact alone says of a value a Kotlin template writes into a position: SAFE, nothing to
 * say; ESCAPE, characters that need escaping (in a URL the text before the value decides the
 * rest); UNSAFE, escaping does not help; DIGITS, an unsigned number, whose digits are safe but
 * which interpolate refuses here.
 */
export type Verdict = "SAFE" | "ESCAPE" | "UNSAFE" | "DIGITS";

/** The verdict on `fact` where a Kotlin template writes it at `position`. */
export function verdict(fact: HoleFact | null, position: Position): Verdict {
  const p = plain(fact);
  if (ownAt(p, position)) return "SAFE";
  switch (position) {
    case "TEXT":
    case "ATTRIBUTE":
    case "URL":
      return scalar(p) ? "SAFE" : "ESCAPE";
    case "STYLE":
      return p === "number" ? "SAFE" : p === "unsigned" ? "DIGITS" : "UNSAFE";
    case "DATASTAR":
      return p === "number" || p === "boolean" ? "SAFE" : p === "unsigned" ? "DIGITS" : "UNSAFE";
    case "CODE":
    case "STRUCTURE":
      return "UNSAFE";
  }
}

/**
 * Whether the value is the author's own or cannot carry code: a constant, a number, a boolean or
 * an unsigned number, whose digits Kotlin writes. Where escaping does not help, `verdict` still
 * asks which.
 */
export function writesItself(fact: HoleFact | null): boolean {
  return fact === "constant" || fact === "number" || fact === "boolean" || fact === "unsigned";
}

/** A fact as the judgement reads it: a `String?` is a String, whose null only the fixes ask about. */
export function plain(fact: HoleFact | null): Exclude<HoleFact, "nullable-string"> | null {
  return fact === "nullable-string" ? "string" : fact;
}

/** Markup is the author's own in element text; in an attribute or code it is characters that can break out. */
function ownAt(fact: HoleFact | null, position: Position): boolean {
  return fact === "constant" || (fact === "markup" && position === "TEXT");
}

/** Digits, true, false or null: safe wherever escaping is enough. */
export function scalar(fact: HoleFact | null): boolean {
  return fact === "number" || fact === "boolean" || fact === "nullable" || fact === "unsigned";
}

/** Digits, true or false: never null and never markup. */
export function digitsOrBoolean(fact: HoleFact | null): fact is "number" | "boolean" | "unsigned" {
  return fact === "number" || fact === "boolean" || fact === "unsigned";
}

/** A String or markup: characters that can end what holds them, so a warning, where a value of unknown type is a hint. */
export function knownCharacters(fact: HoleFact | null): boolean {
  return fact === "string" || fact === "markup";
}

/** What interpolate lets in where escaping does not help: a number in style, a number or a boolean in a Datastar attribute. */
export function interpolateTakesIn(fact: HoleFact | null, position: Position): boolean {
  if (position === "DATASTAR") return fact === "number" || fact === "boolean";
  if (position === "STYLE") return fact === "number";
  return false;
}

/** How a message names a value of `digitsOrBoolean`: "a number", "a boolean", "an unsigned number". */
export function described(fact: "number" | "boolean" | "unsigned"): string {
  return fact === "unsigned" ? "an unsigned number" : `a ${fact}`;
}

/** The kind of interpolate's argument a value of this fact is, if the fact says. */
export function argKindOf(fact: HoleFact | null): ArgKind | null {
  if (fact === "string" || fact === "nullable-string") return "string";
  if (fact === "number" || fact === "unsigned" || fact === "boolean" || fact === "markup") return fact;
  return null;
}

/** Written by the author: a literal, a constant, or text made from them alone. */
export const isConstant = (fact: HoleFact | null): boolean => fact === "constant";

/** A String, which is what may come from outside. */
export const isString = (fact: HoleFact | null): boolean => fact === "string";

/** A `String?`, which a template writes as `null` and interpolate as nothing. */
export const isNullableString = (fact: HoleFact | null): boolean => fact === "nullable-string";

/** A number or a boolean that may be null. */
export const isNullable = (fact: HoleFact | null): boolean => fact === "nullable";

/** Html or Trusted: markup, whose values have been through interpolate or which the author vouched for. */
export const isMarkupFact = (fact: HoleFact | null): boolean => fact === "markup";

/** Of the kinds a hole's values have, the one safe in the fewest places; one not known stands between a String and markup. */
export function weakest(kinds: readonly (TextKind | "constant" | null)[]): TextKind | "constant" | null {
  const order: (TextKind | "constant" | null)[] = ["string", null, "markup", "nullable", "unsigned", "boolean", "number", "constant"];
  let worst = order.length - 1;
  for (const k of kinds) worst = Math.min(worst, order.indexOf(k));
  return order[worst] ?? null;
}
