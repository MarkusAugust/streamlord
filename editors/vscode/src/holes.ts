/**
 * Where a hole in a piece of markup lands, as `interpolate` judges it at run time: the twin of
 * `holeLandings` and `urlSchemeFixedBy` in streamlord-core Interpolation.kt and of `scanMarkup` in
 * MarkupScan.kt. Both are held to test/fixtures/twins/hole-landings.json, whose cases core's
 * HoleLandingsTest also holds to `interpolate` itself.
 */
import { catalog } from "./catalog.ts";
import { asciiLowercase, decodeEntities } from "./markup.ts";

export type Position = "TEXT" | "ATTRIBUTE" | "URL" | "DATASTAR" | "STYLE" | "STRUCTURE" | "CODE";

export interface HoleLanding {
  /** The strictest of the positions the hole takes in the three readings of the markup. */
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

const RAW_TEXT = ["script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext"];
const CODE_TEXT = ["script", "style"];
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
}

/** Discard in MarkupScan.kt: takes the attributes of an end tag. */
const DISCARD: Visitor = { attribute: () => {}, tag: () => {}, code: () => {} };

const isLetter =(c: string | undefined) => c !== undefined && /[A-Za-z]/.test(c);
const isSpace = (c: string | undefined) => c !== undefined && HTML_SPACE.includes(c);
const endsName = (c: string | undefined) => c === undefined || isSpace(c) || c === "/" || c === ">";

function startsWithAsciiIgnoreCase(html: string, at: number, needle: string): boolean {
  return asciiLowercase(html.slice(at, at + needle.length)) === needle;
}

function rawTextEnd(html: string, name: string, from: number): number {
  const lower = asciiLowercase(html);
  for (let at = from; ; ) {
    const close = lower.indexOf(`</${name}`, at);
    if (close < 0) return -1;
    const next = html[close + 2 + name.length];
    if (next === undefined || next === "/" || next === ">" || isSpace(next)) return close;
    at = close + 1;
  }
}

function commentEnd(html: string, lt: number): number {
  if (html.startsWith("<!-->", lt)) return lt + 5;
  if (html.startsWith("<!--->", lt)) return lt + 6;
  const dash = html.indexOf("-->", lt + 4);
  const bang = html.indexOf("--!>", lt + 4);
  if (dash < 0 && bang < 0) return html.length;
  if (bang < 0 || (dash >= 0 && dash < bang)) return dash + 3;
  return bang + 4;
}

/** scanMarkup in MarkupScan.kt. */
function scanMarkup(html: string, visitor: Visitor, foreign: boolean, cdata: boolean): void {
  const rawText = foreign ? CODE_TEXT : RAW_TEXT;
  const n = html.length;
  let i = 0;
  while (i < n) {
    const lt = html.indexOf("<", i);
    if (lt < 0) break;
    if (html.startsWith("<!--", lt)) {
      i = commentEnd(html, lt);
      continue;
    }
    if (cdata && html.startsWith("<![CDATA[", lt)) {
      const close = html.indexOf("]]>", lt + 9);
      i = close < 0 ? n : close + 3;
      continue;
    }
    const first = html[lt + 1];
    if (first === "!" || first === "?" || (first === "/" && !isLetter(html[lt + 2]) && html[lt + 2] !== ">")) {
      const close = html.indexOf(">", lt + 2);
      i = close < 0 ? n : close + 1;
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

/** scanMarkupTracked in MarkupScan.kt: follows the browser's element stack and switches rule by position. */
function scanMarkupTracked(html: string, visitor: Visitor): void {
  const stackNs: number[] = [];
  const stackName: string[] = [];
  const stackHtmlContent: boolean[] = [];
  const insertionNs = () => (stackNs.length === 0 ? NS_HTML : stackHtmlContent[stackHtmlContent.length - 1] ? NS_HTML : (stackNs[stackNs.length - 1] as number));
  const pop = () => {
    stackNs.pop();
    stackName.pop();
    stackHtmlContent.pop();
  };
  const n = html.length;
  let i = 0;
  while (i < n) {
    const lt = html.indexOf("<", i);
    if (lt < 0) break;
    const foreignNow = insertionNs() !== NS_HTML;
    if (html.startsWith("<!--", lt)) {
      i = commentEnd(html, lt);
      continue;
    }
    if (foreignNow && html.startsWith("<![CDATA[", lt)) {
      const close = html.indexOf("]]>", lt + 9);
      i = close < 0 ? n : close + 3;
      continue;
    }
    const first = html[lt + 1];
    if (first === "!" || first === "?" || (first === "/" && !isLetter(html[lt + 2]) && html[lt + 2] !== ">")) {
      const close = html.indexOf(">", lt + 2);
      i = close < 0 ? n : close + 1;
      continue;
    }
    if (first === "/" && isLetter(html[lt + 2])) {
      const end = scanTag(html, lt + 2, DISCARD);
      visitor.tag(lt, end);
      const name = tagName(html, lt + 2);
      const at = stackName.lastIndexOf(name);
      if (at >= 0) while (stackName.length > at) pop();
      i = end;
      continue;
    }
    if (!isLetter(first)) {
      i = lt + 1;
      continue;
    }
    const name = tagName(html, lt + 1);
    if (foreignNow && EXITS_FOREIGN.has(name)) {
      while (stackNs.length > 0 && stackNs[stackNs.length - 1] !== NS_HTML && !stackHtmlContent[stackHtmlContent.length - 1]) pop();
    }
    const html5 = insertionNs() === NS_HTML;
    let encoding: string | undefined;
    let unquotedEnd = -1;
    const snoop: Visitor = {
      attribute(tag, nm, valueStart, valueEnd, quoted) {
        if (name === "annotation-xml" && encoding === undefined && asciiLowercase(nm) === "encoding" && valueStart >= 0) encoding = html.slice(valueStart, valueEnd);
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
    if (html5) {
      const raw = RAW_TEXT.find((t) => t === name);
      if (raw !== undefined) {
        const found = raw === "plaintext" ? -1 : rawTextEnd(html, raw, i);
        const close = found < 0 ? n : found;
        if (CODE_TEXT.includes(raw)) visitor.code(i, close);
        i = close;
        continue;
      }
      if (selfClosing) continue;
      stackNs.push(name === "svg" ? NS_SVG : name === "math" ? NS_MATH : NS_HTML);
      stackName.push(name);
      stackHtmlContent.push(false);
    } else {
      if (selfClosing) continue;
      const ns = name === "svg" ? NS_SVG : name === "math" ? NS_MATH : (stackNs[stackNs.length - 1] as number);
      const integration =
        (ns === NS_SVG && SVG_HTML_INTEGRATION.has(name)) ||
        (ns === NS_MATH && MATH_TEXT_INTEGRATION.has(name)) ||
        (ns === NS_MATH && name === "annotation-xml" && encoding !== undefined && HTML_ANNOTATION_ENCODINGS.has(asciiLowercase(encoding)));
      stackNs.push(ns);
      stackName.push(name);
      stackHtmlContent.push(integration);
    }
  }
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

  constructor(private readonly prefixes: string[]) {}

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

/** Where each of `holes` lands in `markup`: holeLandings in Interpolation.kt. */
export function holeLandings(markup: string, holes: readonly Hole[], prefixes: string[] = PREFIXES): HoleLanding[] {
  const shadowed = shadow(markup, holes);
  const ordered = [...prefixes].sort((a, b) => b.length - a.length);
  const landscapes = ([[false, false], [true, false], [true, true]] as const).map(([foreign, cdata]) => {
    const l = new Landscape(ordered);
    scanMarkup(shadowed, l, foreign, cdata);
    return l;
  });
  // The fourth reading follows the browser's element stack, switching rule by position where the
  // three fixed ones each hold one throughout: scanMarkupTracked in MarkupScan.kt.
  const tracked = new Landscape(ordered);
  scanMarkupTracked(shadowed, tracked);
  landscapes.push(tracked);
  return holes.map((h) => {
    let strictest = landscapes[0] as Landscape;
    for (const l of landscapes) if (STRICTNESS.indexOf(l.positionOf(h.start)) > STRICTNESS.indexOf(strictest.positionOf(h.start))) strictest = l;
    return strictest.landing(h.start);
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
