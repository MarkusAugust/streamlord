/**
 * What a key of a Datastar attribute may be: the twin of `AttributeKeys` in streamlord-core, which
 * the DSL throws with and the Kotlin analysis reports, held to it by the shared corpus. How a name
 * with capitals is written in kebab-case, which keys the client would cut in the wrong place, and
 * how an HTML attribute with capitals is spelled.
 */

/** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so Datastar's camel conversion gives the name back. */
export function kebab(name: string): string {
  const out = name.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase());
  // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
  return /^[A-Z]/.test(name) ? out.replace(/^-/, "") : out;
}

/** Blank as Kotlin's `isBlank` reads it: every character a space (Unicode Zs, Zl and Zp), a control space or U+001C to U+001F. */
const BLANK = /^[\t\n\v\f\r\x1c-\x1f \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/;

// Java's \s, the spaces JavaScript's \s adds (class.ts splits a class list on it), NUL, which
// the HTML parser turns into U+FFFD, and the characters that end an attribute or its name.
const FORBIDDEN = /[\t\n\v\f\r \x00\xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"'<>=\/]/;

/**
 * Why `name` cannot be a key or a signal name, or null: it is blank, or holds whitespace (as Java
 * or JavaScript counts it, so a no-break space too), NUL, a quote or a character that ends an
 * attribute or its name, or a `__` (`modifierStartIn`).
 */
export function nameProblem(name: string): string | null {
  if (BLANK.test(name)) return "is blank";
  if (FORBIDDEN.test(name)) return "contains whitespace, NUL, quotes or a character that ends an attribute or its name";
  return modifierStartIn(name);
}

/** Why `name` cannot be a key, or null: the client splits an attribute name at the first `__`. */
export function modifierStartIn(name: string): string | null {
  return name.includes("__") ? "contains __, where Datastar starts reading modifiers" : null;
}

/** Why `key` cannot stand before `modifiers`, or null: a key that ends in `_` runs into the `__` of the modifiers after it. */
export function runsIntoModifiers(key: string, modifiers: string): string | null {
  return modifiers.length > 0 && key.endsWith("_")
    ? `ends in _, which runs into the __ of the modifiers after it, so Datastar's client would read the key as ${key.slice(0, -1)}`
    : null;
}

/** The spellings HTML may have for an attribute `name` written with capitals: lowercased with a hyphen already, else kebab-case or one word. */
export function spellings(name: string): string[] {
  return name.includes("-") ? [name.toLowerCase()] : [...new Set([kebab(name), name.toLowerCase()])];
}

/** The object form with `name` as its key, `{viewBox: ...}`, quoted when it is no JavaScript name. */
export function objectForm(name: string): string {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name) ? `{${name}: ...}` : `{'${name}': ...}`;
}

/** Why the attribute `name` cannot be a key, or null: the browser lowercases a capital, and `helper`'s object form keeps it. */
export function capitalIn(name: string, helper: string): string | null {
  return /[A-Z]/.test(name)
    ? `has a capital, which the browser lowercases in an attribute name; write it as HTML has it, ${spellings(name).join(" or ")}, or, for an attribute that has capitals such as SVG's viewBox, the object form ${helper}("${objectForm(name)}")`
    : null;
}
