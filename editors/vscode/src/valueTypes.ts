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
 * follows the template, and `__kt__`, a `%s` of interpolate's format read as one.
 */
const TAG_START = /^\s*<(?:[A-Za-z]|(?:__kt__|\$(?:\{[^{}]*\}|[\p{L}_][\p{L}\p{Nd}_]*))(?=[\s>/])|!--|!doctype)/iu;

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

/** Whether `type` is a collection of Streamlord's markup, as `List<Html>`; the whitespace in it is ignored. */
export const isMarkupCollection = (type: string): boolean => MARKUP_COLLECTION.test(noSpace(type));

/** Whether `type` is Streamlord's `Trusted`, which cannot be null. */
export const isTrustedType = (type: string): boolean => TRUSTED_TYPE.test(noSpace(type));

/**
 * Whether the content of a string literal, past its opening quotes, starts with a tag, a comment
 * or a doctype, as markup does. `trimMargin` reads it as `.trimMargin()` leaves it, past the first `|`.
 */
export const opensWithTag = (content: string, trimMargin = false): boolean => TAG_START.test(trimMargin ? content.replace(/^\s*\|/, "") : content);
