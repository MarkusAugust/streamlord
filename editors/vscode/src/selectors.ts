/**
 * Collects element ids and class names declared in a file, for selector completion.
 * `id = "x"` in kotlinx.html and `id="x"` in HTML both match; classes are split on whitespace.
 */

const ID = /\bid\s*=\s*"([A-Za-z_][\w.:-]*)"/g;
const CLASS = /\b(?:class|classes)\s*=\s*"([^"]+)"/g;
const CLASSES_SET = /\bclasses\s*=\s*setOf\(([^)]*)\)/g;
const STRING = /"([^"]+)"/g;

export interface Selectors {
  ids: Set<string>;
  classes: Set<string>;
}

export function collectSelectors(src: string): Selectors {
  const ids = new Set<string>();
  const classes = new Set<string>();
  for (const m of src.matchAll(ID)) if (m[1] && !m[1].includes("${")) ids.add(m[1]);
  for (const m of src.matchAll(CLASS)) for (const c of (m[1] ?? "").split(/\s+/)) if (c && !c.includes("$") && !c.includes("{")) classes.add(c);
  for (const m of src.matchAll(CLASSES_SET)) for (const s of (m[1] ?? "").matchAll(STRING)) if (s[1]) classes.add(s[1]);
  return { ids, classes };
}
