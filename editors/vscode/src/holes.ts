/**
 * Where a hole in a piece of markup lands, as `interpolate` judges it at run time: the twin of
 * `holeLandings` and `urlSchemeFixedBy` in streamlord-core Interpolation.kt and of `scanMarkup` in
 * MarkupScan.kt. Both are held to test/fixtures/twins/hole-landings.json, whose cases core's
 * HoleLandingsTest also holds to `interpolate` itself.
 */
import { catalog } from "./catalog.ts";
import { asciiLowercase, decodeEntities, rawTextEnd } from "./markup.ts";

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
}

/** A span the caller will fill: start inclusive, end exclusive. */
export interface Hole {
  start: number;
  end: number;
}

/** Each position's place in what it lets in: everything, a number or a boolean, a number, nothing. */
const STRICTNESS: readonly Position[] = ["TEXT", "ATTRIBUTE", "URL", "DATASTAR", "STYLE", "STRUCTURE", "CODE"];

/** RAW_TEXT in ClientHtml.kt: a noscript holds elements to the client, which parses with scripting off. */
const RAW_TEXT = ["script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "plaintext"];
/** SCRIPTING_RAW_TEXT: a page loaded with scripting on reads a noscript as text. */
const SCRIPTING_RAW_TEXT = [...RAW_TEXT, "noscript"];
const CODE_TEXT = ["script", "style"];
/** What the markup after a hole in a comment may begin with for dashes or a `!` in the value to end the comment there. */
const COMMENT_CLOSERS = [">", "!>", "->", "-!>"];
const HTML_SPACE = "\t\n\f\r ";

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

interface Visitor {
  attribute(tag: string, name: string, valueStart: number, valueEnd: number, quoted: boolean): void;
  tag(start: number, end: number): void;
  code(start: number, end: number): void;
  /** A comment, a CDATA section or a bogus comment, from its own `<`: declaration in MarkupScan.kt. */
  declaration?(start: number, end: number): void;
}

/** Discard in MarkupScan.kt: takes the attributes of an end tag. */
const DISCARD: Visitor = { attribute: () => {}, tag: () => {}, code: () => {} };

const isLetter =(c: string | undefined) => c !== undefined && /[A-Za-z]/.test(c);
const isSpace = (c: string | undefined) => c !== undefined && HTML_SPACE.includes(c);
const endsName = (c: string | undefined) => c === undefined || isSpace(c) || c === "/" || c === ">";

function startsWithAsciiIgnoreCase(html: string, at: number, needle: string): boolean {
  return asciiLowercase(html.slice(at, at + needle.length)) === needle;
}

/** commentEnd in ClientHtml.kt: one walk to the first `-->` or `--!>`, so many comments cost no more than their length. */
function commentEnd(html: string, lt: number): number {
  if (html.startsWith("<!-->", lt)) return lt + 5;
  if (html.startsWith("<!--->", lt)) return lt + 6;
  for (let at = lt + 4; ; ) {
    const dashes = html.indexOf("--", at);
    if (dashes < 0) return html.length;
    if (html[dashes + 2] === ">") return dashes + 3;
    if (html[dashes + 2] === "!" && html[dashes + 3] === ">") return dashes + 4;
    at = dashes + 1;
  }
}

/** scanMarkup in MarkupScan.kt. */
function scanMarkup(html: string, visitor: Visitor, foreign: boolean, cdata: boolean, scripting: boolean): void {
  const rawText = foreign ? CODE_TEXT : scripting ? SCRIPTING_RAW_TEXT : RAW_TEXT;
  const n = html.length;
  let i = 0;
  while (i < n) {
    const lt = html.indexOf("<", i);
    if (lt < 0) break;
    if (html.startsWith("<!--", lt)) {
      i = commentEnd(html, lt);
      visitor.declaration?.(lt, i);
      continue;
    }
    if (cdata && html.startsWith("<![CDATA[", lt)) {
      const close = html.indexOf("]]>", lt + 9);
      i = close < 0 ? n : close + 3;
      visitor.declaration?.(lt, i);
      continue;
    }
    const first = html[lt + 1];
    if (first === "!" || first === "?" || (first === "/" && !isLetter(html[lt + 2]) && html[lt + 2] !== ">")) {
      const close = html.indexOf(">", lt + 2);
      i = close < 0 ? n : close + 1;
      visitor.declaration?.(lt, i);
      continue;
    }
    // An end tag's attributes are read as a start tag's, so a quoted value can hold a >, and then thrown away.
    if (first === "/" && isLetter(html[lt + 2])) {
      i = scanTag(html, lt + 2, DISCARD);
      visitor.tag(lt, i);
      continue;
    }
    if (!isLetter(first)) {
      i = lt + 1;
      continue;
    }
    i = scanTag(html, lt + 1, visitor);
    visitor.tag(lt, i);
    const tag = rawText.find((t) => startsWithAsciiIgnoreCase(html, lt + 1, t) && endsName(html[lt + 1 + t.length]));
    if (tag !== undefined) {
      const found = tag === "plaintext" ? n : rawTextEnd(html, tag, i);
      const end = found < 0 ? n : found;
      if (CODE_TEXT.includes(tag)) visitor.code(i, end);
      i = end;
    }
  }
}

const NS_HTML = 0;
const NS_SVG = 1;
const NS_MATH = 2;
const SVG_HTML_INTEGRATION = new Set(["foreignobject", "desc", "title"]);
const MATH_TEXT_INTEGRATION = new Set(["mi", "mo", "mn", "ms", "mtext"]);
/** The two MathML elements that stay MathML inside a text integration point. */
const MATH_IN_TEXT = new Set(["mglyph", "malignmark"]);
/** The attributes that make a `<font>` in foreign content return to HTML. */
const FONT_EXITS = new Set(["color", "face", "size"]);
/** HTML elements the browser pops as it inserts them. */
// prettier-ignore
const VOID = new Set([
  "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "image", "img", "input",
  "keygen", "link", "meta", "param", "source", "track", "wbr",
]);
const HTML_ANNOTATION_ENCODINGS = new Set(["text/html", "application/xhtml+xml"]);
// prettier-ignore
const EXITS_FOREIGN = new Set([
  "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed",
  "h1", "h2", "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta",
  "nobr", "ol", "p", "pre", "ruby", "s", "small", "strong", "strike", "span", "sub", "sup", "table",
  "tt", "u", "ul", "var",
]);

/** The tag name beginning at `from` (after `<` or `</`), lowercased. */
function tagName(html: string, from: number): string {
  let i = from;
  while (i < html.length && !endsName(html[i])) i++;
  return asciiLowercase(html.slice(from, i));
}

/**
 * scanMarkupTracked in MarkupScan.kt: follows the browser's element stack and switches rule by
 * position, starting inside an element of the `context` namespace, as a patch in the svg namespace
 * is parsed inside an `<svg>`. Without `htmlStaysOpen`, no HTML element opened straight from a
 * foreign context stays open, as when the browser closes a `<p>` at the next `<p>` or `<div>`.
 */
function scanMarkupTracked(html: string, visitor: Visitor, context: number, scripting: boolean, htmlStaysOpen: boolean): void {
  const rawText = scripting ? SCRIPTING_RAW_TEXT : RAW_TEXT;
  const stackNs: number[] = [];
  const stackName: string[] = [];
  const stackHtmlContent: boolean[] = [];
  const open = new Map<string, number>();
  // An svg or math script or style holds elements, but its text is a program: code from the first opened to the last closed.
  let foreignCode = 0;
  let foreignCodeStart = -1;
  const insertionNs = () => (stackNs.length === 0 ? context : stackHtmlContent[stackHtmlContent.length - 1] ? NS_HTML : (stackNs[stackNs.length - 1] as number));
  const push = (ns: number, name: string, htmlContent: boolean, at: number) => {
    stackNs.push(ns);
    stackName.push(name);
    stackHtmlContent.push(htmlContent);
    open.set(name, (open.get(name) ?? 0) + 1);
    if (ns !== NS_HTML && CODE_TEXT.includes(name) && foreignCode++ === 0) foreignCodeStart = at;
  };
  const pop = (at: number) => {
    const name = stackName.pop() as string;
    const ns = stackNs.pop();
    stackHtmlContent.pop();
    open.set(name, (open.get(name) as number) - 1);
    if (ns !== NS_HTML && CODE_TEXT.includes(name) && --foreignCode === 0) visitor.code(foreignCodeStart, at);
  };
  // A comment in a foreign script is no part of its program.
  const skipCode = (from: number, to: number) => {
    if (foreignCode === 0) return;
    visitor.code(foreignCodeStart, from);
    foreignCodeStart = to;
  };
  const n = html.length;
  let i = 0;
  while (i < n) {
    const lt = html.indexOf("<", i);
    if (lt < 0) break;
    const foreignNow = insertionNs() !== NS_HTML;
    if (html.startsWith("<!--", lt)) {
      i = commentEnd(html, lt);
      visitor.declaration?.(lt, i);
      skipCode(lt, i);
      continue;
    }
    if (foreignNow && html.startsWith("<![CDATA[", lt)) {
      const close = html.indexOf("]]>", lt + 9);
      i = close < 0 ? n : close + 3;
      visitor.declaration?.(lt, i);
      continue;
    }
    const first = html[lt + 1];
    if (first === "!" || first === "?" || (first === "/" && !isLetter(html[lt + 2]) && html[lt + 2] !== ">")) {
      const close = html.indexOf(">", lt + 2);
      i = close < 0 ? n : close + 1;
      visitor.declaration?.(lt, i);
      skipCode(lt, i);
      continue;
    }
    if (first === "/" && isLetter(html[lt + 2])) {
      const end = scanTag(html, lt + 2, DISCARD);
      visitor.tag(lt, end);
      const name = tagName(html, lt + 2);
      if ((open.get(name) ?? 0) > 0) {
        const at = stackName.lastIndexOf(name);
        while (stackName.length > at) pop(lt);
      }
      i = end;
      continue;
    }
    if (!isLetter(first)) {
      i = lt + 1;
      continue;
    }
    const name = tagName(html, lt + 1);
    let encoding: string | undefined;
    let fontExits = false;
    let unquotedEnd = -1;
    const snoop: Visitor = {
      attribute(tag, nm, valueStart, valueEnd, quoted) {
        if (name === "annotation-xml" && encoding === undefined && asciiLowercase(nm) === "encoding" && valueStart >= 0) encoding = html.slice(valueStart, valueEnd);
        if (name === "font" && FONT_EXITS.has(asciiLowercase(nm))) fontExits = true;
        if (!quoted && valueStart >= 0) unquotedEnd = valueEnd;
        visitor.attribute(tag, nm, valueStart, valueEnd, quoted);
      },
      tag: (s, e) => visitor.tag(s, e),
      code: (s, e) => visitor.code(s, e),
    };
    const end = scanTag(html, lt + 1, snoop);
    visitor.tag(lt, end);
    // A `/` that ends an unquoted value is part of the value, not a self-closing mark.
    const selfClosing = end >= 2 && html[end - 2] === "/" && unquotedEnd !== end - 1;
    i = end;
    const exits = foreignNow && (EXITS_FOREIGN.has(name) || fontExits);
    if (exits) {
      while (stackNs.length > 0 && stackNs[stackNs.length - 1] !== NS_HTML && !stackHtmlContent[stackHtmlContent.length - 1]) pop(lt);
    }
    const mathInText =
      !exits && MATH_IN_TEXT.has(name) && stackNs.length > 0 && stackNs[stackNs.length - 1] === NS_MATH && MATH_TEXT_INTEGRATION.has(stackName[stackName.length - 1] as string);
    const html5 = exits || (insertionNs() === NS_HTML && !mathInText);
    if (html5) {
      const raw = rawText.find((t) => t === name);
      if (raw !== undefined) {
        const found = raw === "plaintext" ? -1 : rawTextEnd(html, raw, i);
        const close = found < 0 ? n : found;
        if (CODE_TEXT.includes(raw) && foreignCode === 0) visitor.code(i, close);
        if (found >= 0) push(NS_HTML, raw, false, i);
        i = close;
        continue;
      }
      // HTML ignores a self-closing mark on its own elements; only an svg or a math takes one.
      if (VOID.has(name) || (selfClosing && (name === "svg" || name === "math"))) continue;
      if (!htmlStaysOpen && stackNs.length === 0 && context !== NS_HTML && name !== "svg" && name !== "math") continue;
      push(name === "svg" ? NS_SVG : name === "math" ? NS_MATH : NS_HTML, name, false, i);
    } else {
      if (selfClosing) continue;
      // In foreign content every tag takes the namespace it stands in, an `<svg>` in a math included.
      const ns = stackNs.length === 0 ? context : (stackNs[stackNs.length - 1] as number);
      const integration =
        (ns === NS_SVG && SVG_HTML_INTEGRATION.has(name)) ||
        (ns === NS_MATH && MATH_TEXT_INTEGRATION.has(name)) ||
        (ns === NS_MATH && name === "annotation-xml" && encoding !== undefined && HTML_ANNOTATION_ENCODINGS.has(asciiLowercase(encoding)));
      push(ns, name, integration, i);
    }
  }
  if (foreignCode > 0) visitor.code(foreignCodeStart, n);
}

function scanTag(html: string, from: number, visitor: Visitor): number {
  const n = html.length;
  let i = from;
  while (i < n && !isSpace(html[i]) && html[i] !== ">" && html[i] !== "/") i++;
  const tag = html.slice(from, i);
  while (i < n) {
    const c = html[i];
    if (c === ">") return i + 1;
    if (isSpace(c) || c === "/") {
      i++;
      continue;
    }
    // The first character always starts the name, even an `=`, which the tokenizer takes as part
    // of the name here; only after it does an `=` end the name and open a value.
    const nameStart = i++;
    while (i < n && !isSpace(html[i]) && html[i] !== ">" && html[i] !== "=" && html[i] !== "/") i++;
    const name = html.slice(nameStart, i);
    let k = i;
    while (k < n && isSpace(html[k])) k++;
    if (k < n && html[k] === "=") {
      k++;
      while (k < n && isSpace(html[k])) k++;
      const q = html[k];
      if (q === '"' || q === "'") {
        const close = html.indexOf(q, k + 1);
        const end = close < 0 ? n : close;
        visitor.attribute(tag, name, k + 1, end, true);
        i = close < 0 ? n : close + 1;
      } else {
        const start = k;
        while (k < n && !isSpace(html[k]) && html[k] !== ">") k++;
        visitor.attribute(tag, name, start, k, false);
        i = k;
      }
    } else {
      visitor.attribute(tag, name, -1, -1, false);
    }
  }
  return n;
}

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

  attribute(tag: string, name: string, valueStart: number, valueEnd: number, quoted: boolean): void {
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
    return { position: this.positionOf(at), attribute: a?.name ?? null, valueStart: a?.start ?? -1, loadsCode };
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
    if (known.has(index)) return { position: "TEXT", attribute: null, valueStart: -1, loadsCode: false };
    const at = w.starts[index] as number;
    let strictest = landscapes[0] as Landscape;
    for (const l of landscapes) if (STRICTNESS.indexOf(l.positionOf(at)) > STRICTNESS.indexOf(strictest.positionOf(at))) strictest = l;
    const landing = strictest.landing(at);
    return landing.valueStart < 0 ? landing : { ...landing, valueStart: w.sourceOf(landing.valueStart) };
  });
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
