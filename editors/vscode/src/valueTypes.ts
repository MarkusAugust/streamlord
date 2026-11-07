/**
 * What a Kotlin type or string literal, as the source writes it, tells of a value joined into
 * HTML. The twin of ValueTypes.kt in streamlord-analysis, which the IntelliJ plugin reads types
 * and literals with too; the cases both hold are in twins/value-types.json.
 */
import type { HoleFact } from "./analyze.ts";

/** Streamlord's own markup types, bare as an import gives them or written out in full. */
const CORE_DOMAIN = String.raw`(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?`;

const NUMBER_TYPE = /^(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|Number)$/;
/** Digits, but not a `kotlin.Number`: interpolate refuses one where only a number is let in. */
const UNSIGNED_TYPE = /^(?:kotlin\.)?(?:UInt|ULong|UShort|UByte)$/;
const BOOLEAN_TYPE = /^(?:kotlin\.)?Boolean$/;
const STRING_TYPE = /^(?:kotlin\.)?(?:String|CharSequence)$/;
const HTML_TYPE = new RegExp(`^${CORE_DOMAIN}Html$`);
const TRUSTED_TYPE = new RegExp(`^${CORE_DOMAIN}Trusted$`);

/** A collection of markup: what `joinToString` over Html or Trusted is called on. */
const MARKUP_COLLECTION = new RegExp(
  String.raw`^(?:kotlin\.collections\.|kotlin\.sequences\.)?(?:List|MutableList|ArrayList|Collection|Iterable|Set|MutableSet|Sequence|Array)<${CORE_DOMAIN}(?:Html|Trusted)>\??$`,
);

/**
 * A tag, a comment or a doctype first: `<$tag ` and `<${t} ` among them when a space, `>` or `/`
 * follows the template, and `__kt__`, a `%s` of interpolate's format read as one. Whitespace and
 * letters as HTML has them, ASCII only, so the twins cannot fold or space them apart.
 */
const TAG_START = /^[ \t\n\f\r]*<(?:[A-Za-z]|(?:__kt__|\$(?:\{[^{}]*\}|[\p{L}_][\p{L}\p{Nd}_]*))(?=[ \t\n\f\r>/])|!--|![Dd][Oo][Cc][Tt][Yy][Pp][Ee])/u;

const noSpace = (t: string) => t.replace(/\s+/g, "");

/**
 * The fact a type gives, as a declaration writes it: `Int` a number and `Int?` nullable, `String?`
 * a nullable string, and Streamlord's `Html` or `Trusted` markup, bare or in full. Null for any
 * other type, `com.acme.Html` among them.
 */
export function valueFactOfTypeName(written: string): HoleFact | null {
  const type = noSpace(written);
  const bare = type.replace(/\?$/, "");
  const nullable = bare !== type;
  if (NUMBER_TYPE.test(bare)) return nullable ? "nullable" : "number";
  if (UNSIGNED_TYPE.test(bare)) return nullable ? "nullable" : "unsigned";
  if (BOOLEAN_TYPE.test(bare)) return nullable ? "nullable" : "boolean";
  if (STRING_TYPE.test(bare)) return nullable ? "nullable-string" : "string";
  // What interpolate returns: markup whose values have been through it.
  if (HTML_TYPE.test(bare)) return "markup";
  // Markup the author vouched for, which a Kotlin template writes as it stands; `null` if it may be.
  if (!nullable && TRUSTED_TYPE.test(bare)) return "markup";
  return null;
}

/**
 * The fact a declaration of the type `written` gives its value: `nonNull` reads it as the left
 * side of `?:` does, and without `core` Html and Trusted are not Streamlord's.
 */
export function declaredFact(written: string, nonNull: boolean, core: boolean): HoleFact | null {
  const fact = valueFactOfTypeName(written);
  // Digits, true or false, or null: safe where anything is, and not where only a number or a boolean is.
  if (fact === "nullable") return nonNull ? valueFactOfTypeName(written.replace(/\s+/g, "").replace(/\?$/, "")) : fact;
  // A template writes `null` for it, which the analysis judges as the string it may also be.
  if (fact === "nullable-string") return "string";
  if (fact === "markup") return core ? fact : null;
  return fact;
}

/** Whether `type` is a collection of Streamlord's markup, as `List<Html>`; the whitespace in it is ignored. */
export const isMarkupCollection = (type: string): boolean => MARKUP_COLLECTION.test(noSpace(type));

/** Whether `type` is Streamlord's `Trusted`, which cannot be null. */
export const isTrustedType = (type: string): boolean => TRUSTED_TYPE.test(noSpace(type));

/**
 * Whether the content of a string literal, past its opening quotes, starts with a tag, a comment
 * or a doctype, as markup does. `trimMargin` reads it as `.trimMargin()` leaves it, past the first `|`.
 */
export const opensWithTag = (content: string, trimMargin = false): boolean => TAG_START.test(trimMargin ? content.replace(/^\s*\|/, "") : content);
