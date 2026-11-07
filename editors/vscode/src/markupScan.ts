/**
 * The walk over HTML both the checks and `interpolate`'s judging read markup by: the twin of
 * MarkupScan.kt in streamlord-core, with the helpers of its ClientHtml.kt it needs. holes.ts takes
 * every reading of it, markup.ts's tokenize the one walkMarkup gives. Core's insideMarkup, for the
 * Html values interpolate writes, has no twin: the editors do not judge those.
 */

/**
 * Lowercases A to Z and nothing else, so the result is as long as the input: `toLowerCase()`
 * turns U+0130 into two characters and moves every offset after it.
 */
export function asciiLowercase(s: string): string {
  return s.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32));
}

/** `indexOf` that ignores the case of A to Z; `needle` is given in lowercase. */
export function indexOfAsciiIgnoreCase(s: string, needle: string, from: number): number {
  for (let i = Math.max(0, from); i <= s.length - needle.length; i++) {
    let k = 0;
    while (k < needle.length) {
      const c = s.charCodeAt(i + k);
      if ((c >= 65 && c <= 90 ? c + 32 : c) !== needle.charCodeAt(k)) break;
      k++;
    }
    if (k === needle.length) return i;
  }
  return -1;
}

/**
 * Where `</name` begins after `from` as a whole name, `</titlex>` not ending a `title`; -1 when
 * nowhere. A `script` ends where the tokenizer's script data states say: rawTextEnd in core
 * ClientHtml.kt, which holes.ts shares as MarkupScan.kt does.
 */
function rawTextEnd(html: string, name: string, from: number): number {
  if (name === "script") return scriptEnd(html, from);
  for (let at = from; ; ) {
    const close = indexOfAsciiIgnoreCase(html, `</${name}`, at);
    if (close < 0) return -1;
    if (endsTagName(html[close + 2 + name.length])) return close;
    at = close + 1;
  }
}

const endsTagName = (c: string | undefined) => c === undefined || c === "/" || c === ">" || "\t\n\f\r ".includes(c);
const isAsciiAlpha = (c: string | undefined) => c !== undefined && /[A-Za-z]/.test(c);

const SCRIPT_DATA = 0;
const SCRIPT_ESCAPED = 1;
const SCRIPT_DOUBLE_ESCAPED = 2;

/**
 * Where the body of a `<script>` that starts at `from` ends, by the script data states: scriptEnd
 * in ClientHtml.kt. After a `<!--` the body is escaped, and a `<script` there makes it
 * double-escaped, where a `</script>` only returns it to escaped; `-->` returns either to script data.
 */
function scriptEnd(html: string, from: number): number {
  let state = SCRIPT_DATA;
  let dashes = 0;
  let i = from;
  const n = html.length;
  while (i < n) {
    const c = html[i];
    if (c === "-" && state !== SCRIPT_DATA) {
      dashes = Math.min(dashes + 1, 2);
      i++;
    } else if (c === ">") {
      if (dashes === 2) state = SCRIPT_DATA;
      dashes = 0;
      i++;
    } else if (c !== "<") {
      dashes = 0;
      i++;
    } else if (state !== SCRIPT_DOUBLE_ESCAPED && asciiLowercase(html.slice(i, i + 8)) === "</script" && endsTagName(html[i + 8])) {
      return i;
    } else if (state === SCRIPT_DATA) {
      if (html.startsWith("<!--", i)) {
        state = SCRIPT_ESCAPED;
        dashes = 2;
        i += 4;
      } else {
        i++;
      }
    } else {
      dashes = 0;
      // `<script` opens the double escape from escaped, `</script` closes it from double-escaped.
      const slash = state === SCRIPT_DOUBLE_ESCAPED;
      if (slash && html[i + 1] !== "/") {
        i++;
        continue;
      }
      const start = i + (slash ? 2 : 1);
      let j = start;
      while (isAsciiAlpha(html[j])) j++;
      // The letters are the temporary buffer; only a space, `/` or `>` after them decides.
      const decided = j < n && endsTagName(html[j]);
      if (decided && j - start === 6 && asciiLowercase(html.slice(start, j)) === "script") {
        state = slash ? SCRIPT_ESCAPED : SCRIPT_DOUBLE_ESCAPED;
        i = j + 1;
      } else {
        i = Math.max(j, i + 1);
      }
    }
  }
  return -1;
}

/** RAW_TEXT in ClientHtml.kt: a noscript holds elements to the client, which parses with scripting off. */
const RAW_TEXT = ["script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "plaintext"];
/** SCRIPTING_RAW_TEXT: a page loaded with scripting on reads a noscript as text. */
const SCRIPTING_RAW_TEXT = [...RAW_TEXT, "noscript"];
const CODE_TEXT = ["script", "style"];
const HTML_SPACE = "\t\n\f\r ";

/** What a walk over HTML reports: MarkupVisitor in MarkupScan.kt. Offsets are into the string that was walked. */
export interface Visitor {
  attribute(tag: string, name: string, nameStart: number, valueStart: number, valueEnd: number, quoted: boolean): void;
  tag(start: number, end: number): void;
  code(start: number, end: number): void;
  /** A comment, a CDATA section or a bogus comment, from its own `<`: declaration in MarkupScan.kt. */
  declaration?(start: number, end: number): void;
  /** A start tag, after its attributes, by walkMarkup: its name lowercased, whether it ends in `/>`, and whether its body is text to its end tag. */
  startTag?(name: string, start: number, end: number, selfClosing: boolean, rawText: boolean): void;
  /** An end tag, by walkMarkup, its name lowercased. */
  endTag?(name: string, start: number, end: number): void;
  /**
   * Text between the tags, with the number of elements open around it, the namespace of the open
   * element it goes into (an integration point's content is HTML, and -1 where none is open), and
   * whether that element is an svg or math integration point.
   */
  text?(start: number, end: number, depth: number, namespace: number, integrationPoint: boolean): void;
}

/** Discard in MarkupScan.kt: takes the attributes of an end tag. */
const DISCARD: Visitor = { attribute: () => {}, tag: () => {}, code: () => {} };

/**
 * walkMarkup in MarkupScan.kt: walks `html` as the browser parses a patch, with scripting off, the
 * one reading of markup the checks and the caret share. With `templates`, the comments and tags of
 * a template language are skipped, and so are the template constructs inside a tag.
 */
export function walkMarkup(html: string, visitor: Visitor, templates = false): void {
  scanMarkupTracked(html, visitor, NS_HTML, false, true, templates);
}

const isLetter =(c: string | undefined) => c !== undefined && /[A-Za-z]/.test(c);
const isSpace = (c: string | undefined) => c !== undefined && HTML_SPACE.includes(c);
const endsName = (c: string | undefined) => c === undefined || isSpace(c) || c === "/" || c === ">";

function startsWithAsciiIgnoreCase(html: string, at: number, needle: string): boolean {
  return asciiLowercase(html.slice(at, at + needle.length)) === needle;
}

/** commentClose in ClientHtml.kt, the end of the markup where nothing closes the comment: one walk to the first `-->` or `--!>`, so many comments cost no more than their length. */
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
export function scanMarkup(html: string, visitor: Visitor, foreign: boolean, cdata: boolean, scripting: boolean): void {
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
      i = orEnd(scanTag(html, lt + 2, DISCARD), n);
      visitor.tag(lt, i);
      continue;
    }
    if (!isLetter(first)) {
      i = lt + 1;
      continue;
    }
    i = orEnd(scanTag(html, lt + 1, visitor), n);
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

export const NS_HTML = 0;
export const NS_SVG = 1;
export const NS_MATH = 2;
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
/** IGNORED_IN_BODY, TABLE_PARTS, HEADINGS, CLOSES_P, LIST_LOOP_PASSES, BUTTON_SCOPE and SPECIAL in MarkupScan.kt. */
const IGNORED_IN_BODY = new Set(["html", "head", "body", "frameset"]);
const TABLE_PARTS = new Set(["caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr"]);
const HEADINGS = new Set(["h1", "h2", "h3", "h4", "h5", "h6"]);
// prettier-ignore
const CLOSES_P = new Set([
  "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl", "fieldset",
  "figcaption", "figure", "footer", "header", "hgroup", "main", "menu", "nav", "ol", "p", "search", "section",
  "summary", "ul", "pre", "listing", "form", "plaintext", "xmp", "hr", ...HEADINGS,
]);
const LIST_LOOP_PASSES = new Set(["address", "div", "p"]);
const BUTTON_SCOPE = new Set(["applet", "caption", "html", "table", "td", "th", "marquee", "object", "template", "button"]);
// prettier-ignore
const SPECIAL = new Set([
  "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote", "body", "br",
  "button", "caption", "center", "col", "colgroup", "dd", "details", "dir", "div", "dl", "dt", "embed",
  "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6",
  "head", "header", "hgroup", "hr", "html", "iframe", "img", "input", "keygen", "li", "link", "listing", "main",
  "marquee", "menu", "meta", "nav", "noembed", "noframes", "noscript", "object", "ol", "p", "param",
  "plaintext", "pre", "script", "search", "section", "select", "source", "style", "summary", "table", "tbody",
  "td", "template", "textarea", "tfoot", "th", "thead", "title", "tr", "track", "ul", "wbr", "xmp",
]);
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
export function scanMarkupTracked(html: string, visitor: Visitor, context: number, scripting: boolean, htmlStaysOpen: boolean, templates = false): void {
  const rawText = scripting ? SCRIPTING_RAW_TEXT : RAW_TEXT;
  const stackNs: number[] = [];
  const stackName: string[] = [];
  const stackHtmlContent: boolean[] = [];
  const open = new Map<string, number>();
  // How many special elements are open, so an end tag for none open knows whether one would stop it.
  let specialOpen = 0;
  // documentOpen in MarkupScan.kt: the html, head and body start tags open, which the tree builder drops.
  let documentOpen = 0;
  const isSpecial = (ns: number, name: string, htmlContent: boolean) => (ns === NS_HTML ? SPECIAL.has(name) : htmlContent || name === "annotation-xml");
  // An svg or math script or style holds elements, but its text is a program: code from the first opened to the last closed.
  let foreignCode = 0;
  let foreignCodeStart = -1;
  const insertionNs = () => (stackNs.length === 0 ? context : stackHtmlContent[stackHtmlContent.length - 1] ? NS_HTML : (stackNs[stackNs.length - 1] as number));
  const push = (ns: number, name: string, htmlContent: boolean, at: number) => {
    stackNs.push(ns);
    stackName.push(name);
    stackHtmlContent.push(htmlContent);
    open.set(name, (open.get(name) ?? 0) + 1);
    if (isSpecial(ns, name, htmlContent)) specialOpen++;
    if (ns !== NS_HTML && CODE_TEXT.includes(name) && foreignCode++ === 0) foreignCodeStart = at;
  };
  const pop = (at: number) => {
    const name = stackName.pop() as string;
    const ns = stackNs.pop() as number;
    const htmlContent = stackHtmlContent.pop() as boolean;
    open.set(name, (open.get(name) as number) - 1);
    if (isSpecial(ns, name, htmlContent)) specialOpen--;
    if (ns !== NS_HTML && CODE_TEXT.includes(name) && --foreignCode === 0) visitor.code(foreignCodeStart, at);
  };
  // A comment in a foreign script is no part of its program.
  const skipCode = (from: number, to: number) => {
    if (foreignCode === 0) return;
    visitor.code(foreignCodeStart, from);
    foreignCodeStart = to;
  };
  const boundsScope = (k: number) => (stackNs[k] === NS_HTML ? BUTTON_SCOPE.has(stackName[k] as string) : (stackHtmlContent[k] as boolean) || stackName[k] === "annotation-xml");
  const special = (k: number) => (stackNs[k] === NS_HTML ? SPECIAL.has(stackName[k] as string) : boundsScope(k));
  const popTo = (k: number, at: number) => {
    while (stackName.length > k) pop(at);
  };
  // closeImplied in MarkupScan.kt: the end tags the tree builder implies before it inserts an HTML element.
  const closeImplied = (name: string, at: number) => {
    const items = name === "li" ? ["li"] : name === "dd" || name === "dt" ? ["dd", "dt"] : null;
    if (items !== null) {
      for (let k = stackName.length - 1; k >= 0; k--) {
        if (stackNs[k] === NS_HTML && items.includes(stackName[k] as string)) {
          popTo(k, at);
          break;
        }
        if (special(k) && !(stackNs[k] === NS_HTML && LIST_LOOP_PASSES.has(stackName[k] as string))) break;
      }
    }
    if (items !== null || CLOSES_P.has(name)) {
      for (let k = stackName.length - 1; k >= 0; k--) {
        if (stackNs[k] === NS_HTML && stackName[k] === "p") {
          popTo(k, at);
          break;
        }
        if (boundsScope(k)) break;
      }
    }
    const top = stackName[stackName.length - 1];
    const topHtml = stackNs[stackNs.length - 1] === NS_HTML;
    if (HEADINGS.has(name) && topHtml && top !== undefined && HEADINGS.has(top)) pop(at);
    if ((name === "option" || name === "optgroup") && topHtml && top === "option") pop(at);
  };
  // htmlEndTagTarget in MarkupScan.kt: the nearest open HTML element of the name, unless, for an end
  // tag the tree builder has no rule of its own for, a special one stands nearer.
  const htmlEndTagTarget = (name: string): number => {
    if ((open.get(name) ?? 0) === 0) return OWN_END_TAG_RULE.has(name) || specialOpen === 0 ? NOT_OPEN : BLOCKED;
    for (let k = stackName.length - 1; k >= 0; k--) {
      if (stackNs[k] === NS_HTML && stackName[k] === name) return k;
      if (!OWN_END_TAG_RULE.has(name) && special(k)) return BLOCKED;
    }
    return NOT_OPEN;
  };
  // endTagTarget in MarkupScan.kt: in foreign content, the nearest open element of the name, until an HTML element is open.
  const endTagTarget = (name: string): number => {
    if (stackName.length === 0 || stackNs[stackNs.length - 1] === NS_HTML) return htmlEndTagTarget(name);
    if ((open.get(name) ?? 0) === 0) return NOT_OPEN;
    for (let k = stackName.length - 1; k >= 0; k--) {
      if (stackName[k] === name) return k;
      if (k > 0 && stackNs[k - 1] === NS_HTML) return htmlEndTagTarget(name);
    }
    return NOT_OPEN;
  };
  const n = html.length;
  // Where the text since the last tag, comment or raw-text body began.
  let textStart = 0;
  const text = (to: number) => {
    if (to > textStart) {
      const top = stackName.length - 1;
      visitor.text?.(textStart, to, top + 1 + documentOpen, top < 0 ? -1 : insertionNs(), top >= 0 && stackNs[top] !== NS_HTML && (stackHtmlContent[top] as boolean));
    }
  };
  let i = 0;
  while (i < n) {
    const lt = templates ? nextTagOrBrace(html, i) : html.indexOf("<", i);
    if (lt < 0) break;
    if (html[lt] === "{") {
      const end = templateCommentEnd(html, lt);
      if (end < 0) {
        i = lt + 1;
        continue;
      }
      text(lt);
      visitor.declaration?.(lt, end);
      i = textStart = end;
      continue;
    }
    const foreignNow = insertionNs() !== NS_HTML;
    if (html.startsWith("<!--", lt)) {
      text(lt);
      i = commentEnd(html, lt);
      visitor.declaration?.(lt, i);
      skipCode(lt, i);
      textStart = i;
      continue;
    }
    const templateTag = templates ? templateTagAt(html, lt) : null;
    if (templateTag !== null) {
      const [opener, closer] = templateTag;
      const close = html.indexOf(closer, lt + opener.length);
      text(lt);
      i = textStart = close < 0 ? n : close + closer.length;
      visitor.declaration?.(lt, i);
      continue;
    }
    if (foreignNow && html.startsWith("<![CDATA[", lt)) {
      const close = html.indexOf("]]>", lt + 9);
      text(lt);
      i = textStart = close < 0 ? n : close + 3;
      visitor.declaration?.(lt, i);
      continue;
    }
    const first = html[lt + 1];
    if (first === undefined) break;
    if (first === "!" || first === "?" || (first === "/" && !isLetter(html[lt + 2]) && html[lt + 2] !== ">")) {
      const close = html.indexOf(">", lt + 2);
      text(lt);
      i = textStart = close < 0 ? n : close + 1;
      visitor.declaration?.(lt, i);
      skipCode(lt, i);
      continue;
    }
    if (first === "/" && isLetter(html[lt + 2])) {
      text(lt);
      const closed = scanTag(html, lt + 2, DISCARD, templates);
      const end = orEnd(closed, n);
      visitor.tag(lt, end);
      const name = tagName(html, lt + 2);
      visitor.endTag?.(name, lt, end);
      if (closed < 0) {
        textStart = n;
        break;
      }
      if (IGNORED_IN_BODY.has(name) && documentOpen > 0) documentOpen--;
      // A `</p>` or `</br>` in foreign content closes the foreign elements, as an exiting tag does.
      if (foreignNow && (name === "p" || name === "br")) {
        while (stackNs.length > 0 && stackNs[stackNs.length - 1] !== NS_HTML && !stackHtmlContent[stackHtmlContent.length - 1]) pop(lt);
      }
      const at = name === "br" ? BLOCKED : endTagTarget(name);
      if (at >= 0) popTo(at, lt);
      i = textStart = end;
      continue;
    }
    if (!isLetter(first)) {
      i = lt + 1;
      continue;
    }
    text(lt);
    const name = tagName(html, lt + 1);
    let encoding: string | undefined;
    let fontExits = false;
    let unquotedEnd = -1;
    const snoop: Visitor = {
      attribute(tag, nm, nameStart, valueStart, valueEnd, quoted) {
        if (name === "annotation-xml" && encoding === undefined && asciiLowercase(nm) === "encoding" && valueStart >= 0) encoding = html.slice(valueStart, valueEnd);
        if (name === "font" && FONT_EXITS.has(asciiLowercase(nm))) fontExits = true;
        if (!quoted && valueStart >= 0) unquotedEnd = valueEnd;
        visitor.attribute(tag, nm, nameStart, valueStart, valueEnd, quoted);
      },
      tag: (s, e) => visitor.tag(s, e),
      code: (s, e) => visitor.code(s, e),
    };
    const closed = scanTag(html, lt + 1, snoop, templates);
    const end = orEnd(closed, n);
    visitor.tag(lt, end);
    if (closed < 0) {
      visitor.startTag?.(name, lt, n, false, false);
      textStart = n;
      break;
    }
    // A `/` that ends an unquoted value is part of the value, not a self-closing mark.
    const selfClosing = end >= 2 && html[end - 2] === "/" && unquotedEnd !== end - 1;
    i = textStart = end;
    const exits = foreignNow && (EXITS_FOREIGN.has(name) || fontExits);
    if (exits) {
      while (stackNs.length > 0 && stackNs[stackNs.length - 1] !== NS_HTML && !stackHtmlContent[stackHtmlContent.length - 1]) pop(lt);
    }
    const mathInText =
      !exits && MATH_IN_TEXT.has(name) && stackNs.length > 0 && stackNs[stackNs.length - 1] === NS_MATH && MATH_TEXT_INTEGRATION.has(stackName[stackName.length - 1] as string);
    // Right inside an annotation-xml, an svg is an svg, which the tree builder inserts by its HTML rules.
    const svgInAnnotation = !exits && name === "svg" && stackNs[stackNs.length - 1] === NS_MATH && stackName[stackName.length - 1] === "annotation-xml";
    const html5 = exits || svgInAnnotation || (insertionNs() === NS_HTML && !mathInText);
    const raw = html5 ? rawText.find((t) => t === name) : undefined;
    visitor.startTag?.(name, lt, end, selfClosing, raw !== undefined);
    if (html5) {
      if (IGNORED_IN_BODY.has(name)) {
        documentOpen++;
        continue;
      }
      if (TABLE_PARTS.has(name) && context !== NS_HTML && (open.get("table") ?? 0) === 0 && (open.get("template") ?? 0) === 0) continue;
      if (name !== "svg" && name !== "math") closeImplied(name, lt);
      if (raw !== undefined) {
        const found = raw === "plaintext" ? -1 : rawTextEnd(html, raw, i);
        const close = found < 0 ? n : found;
        if (CODE_TEXT.includes(raw) && foreignCode === 0) visitor.code(i, close);
        if (found >= 0) push(NS_HTML, raw, false, i);
        i = textStart = close;
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
  text(n);
  if (foreignCode > 0) visitor.code(foreignCodeStart, n);
}

/** scanTag in MarkupScan.kt, -1 where the markup ends first; with `templates`, a template construct between the attributes is skipped, and one that starts in a name ends it. */
function scanTag(html: string, from: number, visitor: Visitor, templates = false): number {
  const n = html.length;
  let i = from;
  while (i < n && !isSpace(html[i]) && html[i] !== ">" && html[i] !== "/") i++;
  const tag = html.slice(from, i);
  const cuts = (c: string | undefined) => templates && (c === "{" || c === "<");
  while (i < n) {
    const c = html[i];
    const construct = templates ? templateConstructEnd(html, i) : i;
    if (construct > i) {
      i = construct;
      continue;
    }
    if (cuts(c)) {
      i++;
      continue;
    }
    if (c === ">") return i + 1;
    if (isSpace(c) || c === "/") {
      i++;
      continue;
    }
    // The first character always starts the name, even an `=`, which the tokenizer takes as part
    // of the name here; only after it does an `=` end the name and open a value.
    const nameStart = i++;
    while (i < n && !isSpace(html[i]) && html[i] !== ">" && html[i] !== "=" && html[i] !== "/" && !cuts(html[i])) i++;
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
        visitor.attribute(tag, name, nameStart, k + 1, end, true);
        i = close < 0 ? n : close + 1;
      } else {
        const start = k;
        while (k < n && !isSpace(html[k]) && html[k] !== ">") k++;
        visitor.attribute(tag, name, nameStart, start, k, false);
        i = k;
      }
    } else {
      visitor.attribute(tag, name, nameStart, -1, -1, false);
    }
  }
  return -1;
}


// prettier-ignore
/** OWN_END_TAG_RULE in MarkupScan.kt: the end tags the tree builder has a rule of its own for. */
const OWN_END_TAG_RULE = new Set([
  "address", "article", "aside", "blockquote", "button", "center", "details", "dialog", "dir", "div", "dl",
  "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "main", "menu", "nav", "ol",
  "pre", "search", "section", "summary", "ul", "form", "p", "li", "dd", "dt", "h1", "h2", "h3", "h4", "h5",
  "h6", "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u",
  "applet", "marquee", "object", "template", "body", "html", "table", "caption", "colgroup", "tbody", "tfoot",
  "thead", "tr", "td", "th", "select", "option", "optgroup",
]);
/** An end tag a special element stops before it reaches an open element of its name. */
const BLOCKED = -1;
/** An end tag for no element open in the markup. */
const NOT_OPEN = -2;

/** A tag's end, or the end of the markup where the tag does not close. */
const orEnd = (end: number, n: number) => (end < 0 ? n : end);

/** Where the next `<` or `{` at or after `from` is, or -1. */
function nextTagOrBrace(html: string, from: number): number {
  for (let i = from; i < html.length; i++) if (html[i] === "<" || html[i] === "{") return i;
  return -1;
}

/** templateCommentEnd in MarkupScan.kt: where a Pebble, Twig, Mustache or Handlebars comment at `at` ends, or -1 where none closes. */
function templateCommentEnd(html: string, at: number): number {
  const close = html.startsWith("{#", at) ? "#}" : html.startsWith("{{!--", at) ? "--}}" : html.startsWith("{{!", at) ? "}}" : null;
  if (close === null) return -1;
  const end = html.indexOf(close, at + 2);
  return end < 0 ? -1 : end + close.length;
}

/** templateTagAt in MarkupScan.kt: the opening and closing of a FreeMarker or JSP comment, or of a FreeMarker directive or macro call. */
function templateTagAt(html: string, at: number): [string, string] | null {
  if (html.startsWith("<#--", at)) return ["<#--", "-->"];
  if (html.startsWith("<%--", at)) return ["<%--", "--%>"];
  if (html.startsWith("<#", at) || html.startsWith("<@", at)) return ["<#", ">"];
  if (html.startsWith("</#", at) || html.startsWith("</@", at)) return ["</#", ">"];
  return null;
}

/**
 * Where a template construct that starts at `at` inside a tag ends, or `at` when none starts
 * there: `<% %>`, `{{ }}`, `{% %}`, `{# #}`, a FreeMarker directive, or a JTE or Velocity
 * directive with its parenthesised condition. Its `>` does not end the tag.
 */
export function templateConstructEnd(html: string, at: number): number {
  const after = (close: string, from: number) => {
    const idx = html.indexOf(close, from);
    return idx < 0 ? at : idx + close.length;
  };
  if (html.startsWith("<%", at)) return after("%>", at + 2);
  if (html.startsWith("{{", at)) return after("}}", at + 2);
  if (html.startsWith("{%", at)) return after("%}", at + 2);
  if (html.startsWith("{#", at)) return after("#}", at + 2);
  if (html.startsWith("<#", at) || html.startsWith("</#", at) || html.startsWith("<@", at)) return after(">", at + 2);
  const call = /^[@#][A-Za-z]+\s*\(/.exec(html.slice(at, at + 32));
  if (!call) return at;
  let depth = 0;
  for (let i = at + call[0].length - 1; i < html.length; i++) {
    if (html[i] === "(") depth++;
    else if (html[i] === ")" && --depth === 0) return i + 1;
  }
  return at;
}

