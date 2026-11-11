/**
 * Where a hole in a piece of markup lands, as `interpolate` judges it at run time: the twin of
 * `holeLandings` and `urlSchemeFixedBy` in streamlord-core Interpolation.kt, over the walk in
 * markupScan.ts. Both are held to test/fixtures/twins/hole-landings.json, whose cases core's
 * HoleLandingsTest also holds to `interpolate` itself.
 */
import { catalog } from "./catalog.ts";
import { decodeEntities } from "./markup.ts";
import { asciiLowercase, NS_HTML, NS_MATH, NS_SVG, scanMarkup, scanMarkupTracked, type Visitor } from "./markupScan.ts";

export type Position = "TEXT" | "ATTRIBUTE" | "URL" | "DATASTAR" | "STYLE" | "STRUCTURE" | "CODE";

export interface HoleLanding {
  /** The strictest of the positions the hole takes in the readings of the markup. */
  position: Position;
  /** The quoted attribute it landed in, or null. */
  attribute: string | null;
  /** Where that attribute's value starts in the markup, or -1. */
  valueStart: number;
  /** Whether that attribute is a URL its tag loads and runs, judged by urlHostFixedBy: CODE_URL_ATTRIBUTES. */
  loadsCode: boolean;
  /** Where the tag it landed in starts in the markup, at its `<`, or -1: HoleLanding.tagStart in Interpolation.kt. */
  tagStart: number;
}

/** A span the caller will fill: start inclusive, end exclusive. */
export interface Hole {
  start: number;
  end: number;
}

/** Each position's place in what it lets in: everything, a number or a boolean, a number, nothing. */
const STRICTNESS: readonly Position[] = ["TEXT", "ATTRIBUTE", "URL", "DATASTAR", "STYLE", "STRUCTURE", "CODE"];

/** What the markup after a hole in a comment may begin with for dashes or a `!` in the value to end the comment there. */
const COMMENT_CLOSERS = [">", "!>", "->", "-!>"];

const URL_ATTRIBUTES = new Set(["href", "src", "action", "formaction", "xlink:href", "poster", "data", "cite", "background", "ping", "manifest"]);
const ANIMATION_TAGS = new Set(["animate", "set", "animatetransform", "animatemotion"]);
const ANIMATION_URL_ATTRIBUTES = new Set(["values", "to", "from"]);
/** URL attributes that load what the browser runs or treats as its own document, by tag: CODE_URL_ATTRIBUTES in Interpolation.kt. */
const CODE_URL_ATTRIBUTES = new Map<string, ReadonlySet<string>>([
  ["script", new Set(["src", "href", "xlink:href"])],
  ["base", new Set(["href"])],
  ["iframe", new Set(["src"])],
  ["embed", new Set(["src"])],
  ["object", new Set(["data"])],
  ["link", new Set(["href"])],
]);

/** The attributes Datastar evaluates or follows as a signal path: ElementsGuard.evaluatedAttributes and CatalogNames.signalAttributes. */
const DATASTAR_ATTRIBUTES = new Set(
  catalog.attributes.filter((a) => a.valueKind === "expression" || a.valueKind === "filter" || a.valueKind === "signal").map((a) => a.name),
);
const PREFIXES = [catalog.prefix, catalog.aliasedPrefix];

const SCHEME = /^([A-Za-z][A-Za-z0-9+.-]*):/;
const SAFE_SCHEMES = new Set(["http", "https", "mailto", "tel"]);

/** The index of the last span that starts at or before `at`, or -1: lastAtOrBefore in Interpolation.kt. */
function lastAtOrBefore(spans: readonly ({ start: number } | readonly [number, number])[], at: number): number {
  let low = 0;
  let high = spans.length - 1;
  while (low <= high) {
    const mid = (low + high) >>> 1;
    const span = spans[mid] as { start: number } | readonly [number, number];
    if (("start" in span ? span.start : span[0]) <= at) low = mid + 1;
    else high = mid - 1;
  }
  return high;
}

/** Whether `at` is in one of `spans`, found in order and never overlapping. */
function inSpan(spans: readonly [number, number][], at: number): boolean {
  const span = spans[lastAtOrBefore(spans, at)];
  return span !== undefined && at < span[1];
}

/** Landscape in Interpolation.kt. */
class Landscape implements Visitor {
  private readonly attributes: { name: string; tag: string; start: number; end: number }[] = [];
  private readonly tags: [number, number][] = [];
  private readonly codes: [number, number][] = [];
  private readonly declarations: [number, number][] = [];

  /** `markup` is the shadowed markup read, `holeEnds` where each hole in it ends, by where it starts. */
  constructor(
    private readonly prefixes: string[],
    private readonly markup: string,
    private readonly holeEnds: ReadonlyMap<number, number>,
  ) {}

  attribute(tag: string, name: string, _nameStart: number, valueStart: number, valueEnd: number, quoted: boolean): void {
    if (!quoted || valueStart < 0) return;
    this.attributes.push({ name, tag: asciiLowercase(tag), start: valueStart, end: valueEnd });
  }
  tag(start: number, end: number): void {
    this.tags.push([start, end]);
  }
  code(start: number, end: number): void {
    this.codes.push([start, end]);
  }
  declaration(start: number, end: number): void {
    this.declarations.push([start, end]);
  }

  /** reshapes in Interpolation.kt: whether a value at `hole` could open or end the declaration around it early. */
  private reshapes(start: number, hole: number): boolean {
    const end = this.holeEnds.get(hole);
    if (end === undefined) return false;
    const m = this.markup;
    const after = m.slice(end, end + 3);
    if (m.startsWith("<!--", start)) return COMMENT_CLOSERS.some((c) => after.startsWith(c));
    if (!m.startsWith("<!", start)) return false;
    const between = m.slice(start + 2, hole);
    return (between.length < 2 && "--".startsWith(between)) || (between.length < 7 && "[CDATA[".startsWith(between)) || (m.startsWith("<![CDATA[", start) && (after.startsWith(">") || after.startsWith("]>")));
  }

  private attributeAt(at: number) {
    const a = this.attributes[lastAtOrBefore(this.attributes, at)];
    return a !== undefined && at < a.end ? a : undefined;
  }

  positionOf(at: number): Position {
    const a = this.attributeAt(at);
    if (a) {
      const name = asciiLowercase(a.name);
      if (this.datastar(name)) return "DATASTAR";
      if (name.startsWith("on") || name === "srcdoc") return "CODE";
      if (name === "style") return "STYLE";
      if (URL_ATTRIBUTES.has(name) || (ANIMATION_URL_ATTRIBUTES.has(name) && ANIMATION_TAGS.has(a.tag))) return "URL";
      return "ATTRIBUTE";
    }
    if (inSpan(this.tags, at)) return "STRUCTURE";
    if (inSpan(this.codes, at)) return "CODE";
    const d = this.declarations[lastAtOrBefore(this.declarations, at)];
    if (d !== undefined && at < d[1] && this.reshapes(d[0], at)) return "STRUCTURE";
    return "TEXT";
  }

  landing(at: number): HoleLanding {
    const a = this.attributeAt(at);
    const loadsCode = a !== undefined && (CODE_URL_ATTRIBUTES.get(a.tag)?.has(asciiLowercase(a.name)) ?? false);
    const tag = this.tags[lastAtOrBefore(this.tags, at)];
    const tagStart = tag !== undefined && at < tag[1] ? tag[0] : -1;
    return { position: this.positionOf(at), attribute: a?.name ?? null, valueStart: a?.start ?? -1, loadsCode, tagStart };
  }

  private datastar(lower: string): boolean {
    const prefix = this.prefixes.find((p) => lower.startsWith(p));
    if (prefix === undefined) return false;
    const name = lower.slice(prefix.length).split(":")[0]?.split("__")[0] ?? "";
    return DATASTAR_ATTRIBUTES.has(name);
  }
}

/** Every hole read as a run of letters, so `<%s>` is a tag whose name is the hole. */
function shadow(markup: string, holes: readonly Hole[]): string {
  const chars = [...markup.split("")];
  for (const h of holes) for (let i = h.start; i < h.end; i++) chars[i] = i === h.start ? "d" : "s";
  return chars.join("");
}

/**
 * The markup as it will be written: each hole in `known`, by index, replaced by its text, and every
 * other hole left standing. Written in Interpolation.kt, which collapses a format's `%%` as well.
 */
function written(markup: string, holes: readonly Hole[], known: ReadonlyMap<number, string>) {
  let text = "";
  const starts: number[] = new Array(holes.length).fill(0);
  const open: Hole[] = [];
  // Runs of text in order: where each starts, where in the markup it came from, and whether it is a known text.
  const runs: { start: number; source: number; known: boolean }[] = [];
  let cut = 0;
  const order = holes.map((_, i) => i).sort((a, b) => (holes[a] as Hole).start - (holes[b] as Hole).start);
  for (const index of order) {
    const hole = holes[index] as Hole;
    runs.push({ start: text.length, source: cut, known: false });
    text += markup.slice(cut, hole.start);
    starts[index] = text.length;
    const k = known.get(index);
    runs.push({ start: text.length, source: hole.start, known: k !== undefined });
    if (k !== undefined) text += k;
    else {
      open.push({ start: text.length, end: text.length + hole.end - hole.start });
      text += markup.slice(hole.start, hole.end);
    }
    cut = hole.end;
  }
  runs.push({ start: text.length, source: cut, known: false });
  text += markup.slice(cut);
  const sourceOf = (at: number) => {
    const run = runs[lastAtOrBefore(runs, at)] as { start: number; source: number; known: boolean };
    return run.known ? run.source : run.source + at - run.start;
  };
  return { text, starts, open, sourceOf };
}

/**
 * Where each of `holes` lands in `markup`: holeLandings in Interpolation.kt. `known` holds the text
 * of the holes, by index, whose content is known to be markup, as a Trusted value's is: the markup
 * is read with that text in place of the hole. A known hole is not judged, and lands as TEXT with no
 * attribute; a `valueStart` is still an offset into `markup`, where the hole begins if it falls
 * inside a known text.
 */
export function holeLandings(markup: string, holes: readonly Hole[], prefixes: string[] = PREFIXES, known: ReadonlyMap<number, string> = new Map()): HoleLanding[] {
  const w = written(markup, holes, known);
  const shadowed = shadow(w.text, w.open);
  const holeEnds = new Map(w.open.map((h) => [h.start, h.end]));
  const ordered = [...prefixes].sort((a, b) => b.length - a.length);
  // With a noscript in it, the markup is read with scripting off, as the client parses a patch, and on, as a page loads.
  const scriptings = /noscript/i.test(shadowed) ? [false, true] : [false];
  const landscapes: Landscape[] = [];
  const read = (scan: (l: Landscape) => void) => {
    const l = new Landscape(ordered, shadowed, holeEnds);
    scan(l);
    landscapes.push(l);
  };
  for (const scripting of scriptings) read((l) => scanMarkup(shadowed, l, false, false, scripting));
  read((l) => scanMarkup(shadowed, l, true, false, false));
  read((l) => scanMarkup(shadowed, l, true, true, false));
  // The tracked readings follow the browser's element stack, switching rule by position where the
  // fixed ones each hold one throughout, from HTML, from inside an svg and from inside a math:
  // scanMarkupTracked in MarkupScan.kt.
  for (const context of [NS_HTML, NS_SVG, NS_MATH]) for (const scripting of scriptings) read((l) => scanMarkupTracked(shadowed, l, context, scripting, true));
  // From inside an svg or a math, once more with no HTML element opened from it left open.
  for (const context of [NS_SVG, NS_MATH]) for (const scripting of scriptings) read((l) => scanMarkupTracked(shadowed, l, context, scripting, false));
  return holes.map((_, index) => {
    if (known.has(index)) return { position: "TEXT", attribute: null, valueStart: -1, loadsCode: false, tagStart: -1 };
    const at = w.starts[index] as number;
    let strictest = landscapes[0] as Landscape;
    for (const l of landscapes) if (STRICTNESS.indexOf(l.positionOf(at)) > STRICTNESS.indexOf(strictest.positionOf(at))) strictest = l;
    return inSource(strictest.landing(at), w);
  });
}

/**
 * Where `hole` lands in `markup` as the browser parses it as a patch: the one reading that follows
 * the browser's element stack from HTML, with scripting off. Where `holeLandings` takes the
 * strictest of every reading, this is the reading the markup has, which an editor shows at the
 * caret: in a `<textarea>` the text is text. It is walkMarkup's, which the checks read by as well,
 * and with `templates` it skips the comments and tags of a template language as that does. Its
 * `valueStart` is an offset into `markup`.
 * landingAsParsed in Interpolation.kt.
 */
export function landingAsParsed(markup: string, hole: Hole, prefixes: string[] = PREFIXES, templates = false): HoleLanding {
  const w = written(markup, [hole], new Map());
  const shadowed = shadow(w.text, w.open);
  const parsed = new Landscape([...prefixes].sort((a, b) => b.length - a.length), shadowed, new Map(w.open.map((h) => [h.start, h.end])));
  scanMarkupTracked(shadowed, parsed, NS_HTML, false, true, templates);
  return inSource(parsed.landing(w.starts[0] as number), w);
}

/** The landing with its offsets into the markup as written: inSource in Interpolation.kt. */
function inSource(landing: HoleLanding, w: { sourceOf(at: number): number }): HoleLanding {
  return {
    ...landing,
    valueStart: landing.valueStart < 0 ? landing.valueStart : w.sourceOf(landing.valueStart),
    tagStart: landing.tagStart < 0 ? landing.tagStart : w.sourceOf(landing.tagStart),
  };
}

/** Whether the literal start of a URL attribute's value already settles the scheme: urlSchemeFixedBy in Interpolation.kt. */
export function urlSchemeFixedBy(attribute: string, prefix: string): boolean {
  const decoded = decodeEntities(prefix).text;
  const entry = asciiLowercase(attribute) === "values" ? decoded.slice(decoded.lastIndexOf(";") + 1) : decoded;
  const read = entry.replace(/[\t\n\r]/g, "").replace(/^[\u0000- ]+/, "");
  for (const c of read) {
    if (c === "/" || c === "?" || c === "#") return true;
    if (c === ":") {
      const scheme = SCHEME.exec(read)?.[1];
      return scheme !== undefined && SAFE_SCHEMES.has(asciiLowercase(scheme));
    }
  }
  return false;
}

/**
 * Whether the literal start of a code-loading URL attribute's value already fixes the host, so no
 * value after it can point the load at another origin: urlHostFixedBy in Interpolation.kt. A
 * path-absolute `/x` (not `//` or `/\`), a same-document `#`/`?`, or a whole origin `scheme://host/`
 * with a safe scheme. A bare `/`, or a host the literal does not close, is not enough.
 */
export function urlHostFixedBy(attribute: string, prefix: string): boolean {
  const decoded = decodeEntities(prefix).text;
  const entry = asciiLowercase(attribute) === "values" ? decoded.slice(decoded.lastIndexOf(";") + 1) : decoded;
  const read = entry.replace(/[\t\n\r]/g, "").replace(/^[\u0000- ]+/, "");
  const first = read[0];
  if (first === undefined) return false;
  if (first === "#" || first === "?") return true;
  if (first === "/") return read[1] !== undefined && read[1] !== "/" && read[1] !== "\\";
  const scheme = SCHEME.exec(read);
  if (scheme === null || !SAFE_SCHEMES.has(asciiLowercase(scheme[1] as string))) return false;
  let i = scheme[0].length;
  if (read.slice(i, i + 2) !== "//") return false;
  i += 2;
  while (i < read.length && read[i] !== "/" && read[i] !== "?" && read[i] !== "#") i++;
  return i < read.length;
}
