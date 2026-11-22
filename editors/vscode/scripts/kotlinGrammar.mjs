/**
 * The Kotlin injection grammars, syntaxes/kotlin-injection.tmLanguage.json and
 * syntaxes/kotlin-dsl-injection.tmLanguage.json, built from the catalog: the attribute names, the
 * DSL's expression calls and the server's HTML and script calls are its lists. Written by
 * `npm run generate`; test/generated.test.ts fails when a committed file is not what this writes.
 */

const r = String.raw;

const RAW_START = "punctuation.definition.string.begin.kotlin string.quoted.triple.kotlin";
const RAW_END = "punctuation.definition.string.end.kotlin string.quoted.triple.kotlin";
const DQ_START = "punctuation.definition.string.begin.kotlin string.quoted.double.kotlin";
const DQ_END = "punctuation.definition.string.end.kotlin string.quoted.double.kotlin";
const FN = { name: "entity.name.function.kotlin" };
/** How many dollars open a template in a string, as its prefix says: the marker the template injections read. Kotlin has no use for more than three. */
const MARKERS = { 3: "meta.kotlin-template.three.streamlord", 2: "meta.kotlin-template.two.streamlord", 1: "meta.kotlin-template.one.streamlord" };
const ONE = MARKERS[1];
/** The text before a string's body that says it opens with [n] dollars: a lookbehind at the body's start. */
const opened = (n, quote) => (n === 1 ? "" : r`(?<=` + "\\$".repeat(n) + quote + ")");
const EMB = "meta.embedded.inline.datastar";
/** Every string this grammar opens has it, so no injected rule starts at its closing quote: an injection wins over a rule's end at the same place. */
const STRING = "meta.kotlin-string.streamlord";
const MARKER = "meta.language-marker.streamlord";
const SIGNAL = "variable.other.constant.signal.datastar";
const PATH = "variable.other.property.signal-path.datastar";

/** A receiver before a call, which a Kotlin grammar may colour from its dot. */
const RECV = r`(?:(\??\.)\s*)?`;
const NAMED = r`(?:expression\s*(=)\s*)?`;
/** The name of an argument before an expression's, passed by name: `event = `, `name = `. */
const OTHER_NAMED = r`(?:(?!expression\b)[A-Za-z_][A-Za-z0-9_]*\s*(=)\s*)?`;
const ASSIGN = { name: "keyword.operator.assignment.kotlin" };

/**
 * The groups of [pattern]: how many capture, and which of them is a named argument's `(=)`, which
 * keeps the colour Kotlin's grammar gives it.
 */
function groupsOf(pattern) {
  let count = 0;
  const assigns = [];
  for (let i = 0; i < pattern.length; i++) {
    if (pattern[i] === "\\") i++;
    else if (pattern[i] === "[") while (i < pattern.length && pattern[i] !== "]") i += pattern[i] === "\\" ? 2 : 1;
    else if (pattern[i] === "(" && pattern[i + 1] !== "?") {
      count++;
      if (pattern.startsWith("(=)", i)) assigns.push(count);
    }
  }
  return { count, assigns };
}

/** The captures of a begin that opens a string in its last group: the named arguments' `=` among [captures], and the quote. */
function opening(begin, captures, quote) {
  const { count, assigns } = groupsOf(begin);
  return { ...Object.fromEntries(assigns.map((n) => [n, ASSIGN])), ...captures, [count]: quote };
}
const SIGNAL_NAME = r`([A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*)((?:\.[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*)*)`;
const RAW_QUOTE = r`(\$*""")`;
/** What opens HTML in a double-quoted literal, as the analysis reads it: a tag, a comment or a doctype, after white space or an escaped line break. */
const DQ_TAG_AHEAD = r`(?=<(?:[A-Za-z]|!--|!(?i:doctype))|(?:[ \t]|\\[nrt])+<(?:[A-Za-z]|!--|!(?i:doctype))(?:[^"\\\n]|\\.)*")`;
/** Both kinds of literal, HTML however they open, each with [before] its quote and [after] it what its place asks for. */
const htmlLiterals = (before, after) =>
  [true, false].map((raw) =>
    embedded(before + r`(\$*` + (raw ? '"""' : '"') + ")" + after(raw ? '"""' : '"'), { 1: { name: raw ? RAW_START : DQ_START } }, raw, "meta.embedded.block.html", "text.html.basic"),
  );
/**
 * Just after the brace of interpolate's block, by the qualified name and after prefixes too, read behind the literal so
 * that Kotlin's grammar keeps the name and the brace, and the brackets it opens.
 */
const AFTER_INTERPOLATE = r`(?:^|[^\w.\x60])(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?interpolate[ \t]{0,4}(?:\([^()\n]{0,60}(?:\([^()\n]{0,60}\)[^()\n]{0,20})?\)[ \t]{0,4})?\{[ \t]{0,8}`;
/** Just after `Trusted(` and its argument's name: a literal there is an argument, so this is a call and no declaration. */
const AFTER_TRUSTED = r`(?:^|[^\w.\x60])Trusted[ \t]{0,4}\([ \t]{0,4}(?:html[ \t]{0,4}=[ \t]{0,4})?`;
/** What makes Trusted's argument markup, as the analysis reads it: a tag on its line. */
const TRUSTED_TAG = r`(?=(?:[^"\\\n]|\\.)*<[A-Za-z/!])`;
/** Not the first argument of an annotation, `@Deprecated("<b>x</b>")`, which says something of the code; `this@Page.f(` names a label. */
const ANNOTATION_HEAD = r`(?:^|[^\w.>\x60])@(?:[a-z]{1,8}:)?[A-Za-z_][\w.]{0,60}[ \t]{0,4}\([ \t]{0,4}(?:[A-Za-z_]\w{0,30}[ \t]{0,4}=[ \t]{0,4})?`;
/**
 * The quote [quote], opening a literal that [ahead] says is HTML and that is no annotation's argument. What lies ahead is
 * read first and then what lies behind, the cheap test first, so the look behind runs at few quotes.
 */
const notAnnotation = (quote, ahead) =>
  r`(\$*` + quote + ")" + ahead + r`(?:(?<![(=][ \t]{0,4}\${0,4}` + quote + r`)|(?<!` + ANNOTATION_HEAD + r`\${0,4}` + quote + "))";
const QUOTE = r`(\$*"(?!""))`;

/** Longest first, so that no name is taken for the start of a longer one. */
const alternation = (names) => [...new Set(names)].sort((a, b) => b.length - a.length || (a < b ? -1 : 1)).join("|");

/** The body of a string whose dollars decide what opens a Kotlin template, read from the text that opened it. */
function marked(quote, raw, content, inner, single = []) {
  const escapes = raw ? [] : [{ include: "#kotlin-escapes" }];
  return [3, 2, 1].map((n) => ({
    begin: r`\G` + opened(n, quote),
    end: "(?=" + quote + ")",
    name: MARKERS[n],
    contentName: content,
    patterns: [...escapes, ...(n === 1 ? single : []), ...inner],
  }));
}

/** A raw string on one line, ended by the last three quotes of a run. */
const RAW_STRING = r`"""(?:[^"\n]|"(?!""(?!")))*"""`;
/** What a template holds between its braces: the strings and chars in it whole, and braces [depth] deep. */
const inTemplate = (depth) =>
  r`(?:[^{}"'\n]|` + RAW_STRING + r`|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)'` + (depth > 0 ? r`|\{` + inTemplate(depth - 1) + r`\}` : "") + ")*";
/**
 * The text of a literal up to the quote that closes it on its line, where [dollars] open a template: a template is
 * passed whole, so a quote inside one ends nothing. Atomic, since what it passed is never read another way.
 */
const bodyOf = (raw, dollars) =>
  "(?>(?:" + (raw ? r`[^"\n$]|"(?!""(?!"))` : r`[^"\\\n$]|\\.`) + "|" + r`\$`.repeat(dollars) + r`\{` + inTemplate(3) + r`\}|\$)*)`;

/**
 * A string that holds HTML or JavaScript: raw or plain, its templates marked. One that ends on its line is a single
 * match with its text in a capture, since the grammar included there reads no further than the capture: a tag, a
 * comment or a script it has open at the closing quote ends with the literal. In a region it would take the quote and
 * the lines after it. A raw literal that goes on past its line can only be a region.
 */
function embedded(begin, captures, raw, content, inner) {
  const quote = raw ? '"""' : '"';
  const single = raw ? [{ include: "#dollar-escape" }] : [];
  const text = (n) => ({ name: MARKERS[n] + " " + content, patterns: [...(raw ? [] : [{ include: "#kotlin-escapes" }]), ...(n === 1 ? single : []), { include: inner }] });
  // The body by the dollars the literal opens with, most first, as the region's rules read them.
  const bodies = (group) => "(?:" + [3, 2, 1].map((n) => opened(n, quote) + (group ? "(" : "") + bodyOf(raw, n) + (group ? ")" : "")).join("|") + ")";
  const first = groupsOf(begin).count + 1;
  const onItsLine = {
    match: begin + bodies(true) + "(" + quote + ")",
    captures: { ...captures, [first]: text(3), [first + 1]: text(2), [first + 2]: text(1), [first + 3]: { name: raw ? RAW_END : DQ_END } },
    name: STRING,
  };
  if (!raw) return onItsLine;
  const goesOn = {
    begin: begin + "(?!" + bodies(false) + quote + ")",
    beginCaptures: captures,
    end: '(""")',
    endCaptures: { 1: { name: RAW_END } },
    patterns: marked(quote, raw, content, [{ include: inner }], single),
    name: STRING,
  };
  return { patterns: [onItsLine, goesOn] };
}

/** Two rules, a raw string and a plain one, opened by [head] with the string in group [group]. */
function expressionCall(comment, head, captures, lookahead = "") {
  return {
    comment,
    patterns: [true, false].map((raw) => ({
      begin: head + (raw ? RAW_QUOTE : QUOTE) + lookahead,
      beginCaptures: opening(head + (raw ? RAW_QUOTE : QUOTE), captures, { name: raw ? RAW_START : DQ_START }),
      end: raw ? '(""")' : '(")',
      endCaptures: { 1: { name: raw ? RAW_END : DQ_END } },
      patterns: [{ include: "#expression-string-body" }],
      name: STRING,
    })),
  };
}

/** A string that starts at [anchor] and holds an expression. */
const expressionString = (anchor) =>
  [true, false].map((raw) => ({
    begin: anchor + NAMED + (raw ? RAW_QUOTE : QUOTE),
    beginCaptures: opening(anchor + NAMED + (raw ? RAW_QUOTE : QUOTE), {}, { name: raw ? RAW_START : DQ_START }),
    end: raw ? '(""")' : '(")',
    endCaptures: { 1: { name: raw ? RAW_END : DQ_END } },
    patterns: [{ include: "#expression-string-body" }],
    name: STRING,
  }));

export function kotlinInjectionGrammar(catalog) {
  const sites = catalog.kotlinCallSites;
  const expression = Object.entries(sites.expression).filter(([name]) => !name.startsWith("$"));
  // A call whose later parameters take an expression too, fitExpr, has every string argument read as one.
  const at = (test) => alternation(expression.filter(([, s]) => test(s.arg) && !s.more).map(([n]) => n));
  const first = at((a) => a === 0);
  const every = alternation(expression.filter(([, s]) => s.more).map(([n]) => n));
  const options = Object.entries(sites.expressionOptions).filter(([name]) => !name.startsWith("$"));
  const plainOptions = alternation(options.filter(([, o]) => !o.map).map(([n]) => n));
  const mapOptions = alternation(options.filter(([, o]) => o.map).map(([n]) => n));
  const second = at((a) => a === 1 || a === "last");
  const last = at((a) => a === "last");
  const signalAttributes = alternation(catalog.attributes.filter((a) => a.valueKind === "signal").flatMap((a) => a.kotlin));
  const signalPairs = alternation(catalog.attributes.filter((a) => a.keyCase === "camel" && a.valueKind === "expression").flatMap((a) => a.kotlin));
  // Core's calls that take markup or a script first, by that parameter's name; a companion's function is called on its class.
  const taking = (what) => Object.entries(sites.events).filter(([n, e]) => !n.startsWith("$") && !n.includes(".") && e.takes === what).map(([n]) => n);
  // What a call takes first is also the name of that parameter.
  const [htmlNamed, scriptNamed] = ["elements", "script"];
  const html = taking(htmlNamed);
  const script = taking(scriptNamed);
  const attributes = (kinds) => alternation(catalog.attributes.filter((a) => kinds(a.valueKind)).map((a) => a.name));
  const expressionAttributes = attributes((k) => k === "expression");
  const allAttributes = attributes(() => true);

  const repository = {
    "dsl-file": {
      comment:
        "From the DSL's import, or its own package, to the end of the file: a file calls the DSL's top-level functions only through one, so only there are its calls Streamlord's. The region adds a scope and Kotlin's grammar inside it, nothing else; streamlord.kotlin.dsl.injection colours the calls in it, at any depth, and this grammar stays out of it so it does not open again.",
      name: "meta.dsl-file.streamlord",
      begin: r`^(?=[ \t]*(?:import[ \t]+io\.github\.markusaugust\.streamlord\.html\.|package[ \t]+io\.github\.markusaugust\.streamlord\.html(?![\p{L}\p{Nd}_])))`,
      end: "(?!)",
      patterns: [{ include: "source.kotlin" }],
    },
    "attributes-index": {
      comment:
        "kotlinx.html's own way to write an attribute, attributes[\"data-on:click\"] = \"...\": Datastar in every file, for the catalog's attributes only, the check after the name keeping data-on-intersects out. The name in the key as an attribute name. A literal assigned to an attribute that takes an expression is the expression when it is the whole statement, as the diagnostics read it: not the first half of a concatenation, and not a raw string.",
      patterns: [
        {
          begin:
            r`\b(attributes)(\s*\[\s*)(")(data-(?:star-)?(?:` +
            expressionAttributes +
            r`)(?=[:"]|__)[A-Za-z0-9_:.-]*)(")(\s*\])(\s*)(=)(\s*)(\$*")(?!"")(?=(?:[^"\\]|\\.)*"[ \t]*(?:[;}]|$))`,
          beginCaptures: {
            3: { name: DQ_START },
            4: { patterns: [{ include: "streamlord.html.injection#attribute-name" }] },
            5: { name: DQ_END },
            8: { name: "keyword.operator.assignment.kotlin" },
            10: { name: DQ_START },
          },
          end: '(")',
          endCaptures: { 1: { name: DQ_END } },
          patterns: marked('"', false, EMB, [{ include: "source.datastar" }]).map((p) => ({ ...p, patterns: [{ include: "source.datastar" }, { include: "#kotlin-escapes" }] })),
          name: STRING,
        },
        {
          match: r`\b(attributes)(\s*\[\s*)(")(data-(?:star-)?(?:` + allAttributes + r`)(?=[:"]|__)[A-Za-z0-9_:.-]*)(")(\s*\])`,
          captures: {
            3: { name: DQ_START },
            4: { patterns: [{ include: "streamlord.html.injection#attribute-name" }] },
            5: { name: DQ_END },
          },
        },
      ],
    },
    "html-string": {
      comment:
        "HTML in a string that is not handed to a Streamlord call: a function that returns markup, a val with a fragment. Recognised by its first character, or by IntelliJ's injection marker (@Language(\"HTML\") or // language=HTML) before it.",
      patterns: [
        {
          comment: "The annotation on a parameter (`@Language(\"HTML\") html: String,`): consumed, reaches nothing.",
          match: r`(@(?:org\.intellij\.lang\.annotations\.)?Language\(\s*"(?i:html)"\s*\))(?=\s*(?:va[lr]\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:)`,
          captures: { 1: { name: "storage.type.annotation.kotlin" } },
        },
        {
          comment:
            "The annotation on a function or property: ends after the next triple-quoted literal, or after a double-quoted literal that ends the line (`val x = \"<b>\"`). A double-quoted string before that, such as a parameter default, is a plain string.",
          begin: r`(@(?:org\.intellij\.lang\.annotations\.)?Language\(\s*"(?i:html)"\s*\))`,
          beginCaptures: { 1: { name: "storage.type.annotation.kotlin" } },
          end: r`(?<=""")|(?<=")\s*$|(?<=")(?=\s*\+)`,
          patterns: [{ include: "#html-triple-string" }, { include: "#html-double-string" }, { include: "#marked-double-string" }, { include: "#plain-double-string" }],
          name: MARKER,
        },
        {
          begin: r`(//\s*language=(?i:html)\b[^\n]*)`,
          beginCaptures: { 1: { name: "comment.line.double-slash.kotlin" } },
          end: r`(?<=""")|(?<=")\s*$|(?<=")(?=\s*\+)`,
          patterns: [{ include: "#html-triple-string" }, { include: "#html-double-string" }, { include: "#marked-double-string" }, { include: "#plain-double-string" }],
          name: MARKER,
        },
        embedded(notAnnotation('"""', r`(?=\s*<(?:[A-Za-z]|!--|!(?i:doctype)))`), { 1: { name: RAW_START } }, true, "meta.embedded.block.html", "text.html.basic"),
        embedded(notAnnotation('"', DQ_TAG_AHEAD), { 1: { name: DQ_START } }, false, "meta.embedded.block.html", "text.html.basic"),
      ],
    },
    "html-string-next-line": {
      comment:
        "A raw string that ends its first line: HTML when its next line opens with a tag, after trimMargin's `|` too, as `val h = \"\"\"` and `\"\"\".trimIndent()` write it, else a string. Its first line cannot tell which, so it is read here either way, with the templates its dollars open.",
      patterns: [3, 2, 1].map((n) => ({
        begin: r`(?<!\$)(` + "\\$".repeat(n === 1 ? 0 : n) + '""")' + r`[ \t]*$`,
        beginCaptures: { 1: { name: RAW_START } },
        end: '(""")',
        endCaptures: { 1: { name: RAW_END } },
        name: STRING,
        patterns: [
          {
            begin: r`^(?=[ \t]*\|?<(?:[A-Za-z]|!--|!(?i:doctype)))`,
            end: '(?=""")',
            name: MARKERS[n],
            contentName: "meta.embedded.block.html",
            patterns: [...(n === 1 ? [{ include: "#dollar-escape" }] : []), { include: "text.html.basic" }],
          },
          { begin: r`^(?=[ \t]*\S)`, end: '(?=""")', name: MARKERS[n] + " string.quoted.triple.kotlin", patterns: n === 1 ? [{ include: "#dollar-escape" }] : [] },
        ],
      })),
    },
    "html-triple-string": embedded(r`(\$*""")`, { 1: { name: RAW_START } }, true, "meta.embedded.block.html", "text.html.basic"),
    "html-double-string": {
      comment: "A double-quoted literal that opens with a tag, under an HTML marker: HTML.",
      ...embedded(r`(\$*")` + DQ_TAG_AHEAD, { 1: { name: DQ_START } }, false, "meta.embedded.block.html", "text.html.basic"),
    },
    "marked-double-string": {
      comment: "The double-quoted literal the marker ends at, the one that ends the line or is joined to more: HTML, however it opens (`@Language(\"HTML\") val p = \"Hello <b>\" + rest`).",
      ...embedded(r`(\$*")(?=(?:[^"\\\n]|\\.)*"[ \t]*(?:;[ \t]*|\+.*)?$)`, { 1: { name: DQ_START } }, false, "meta.embedded.block.html", "text.html.basic"),
    },
    "plain-double-string": {
      comment:
        "Any other double-quoted literal under an HTML marker, such as a parameter default: a string, nothing more. Listed after html-double-string, which wins at the same quote.",
      begin: r`(\$*")`,
      beginCaptures: { 1: { name: "punctuation.definition.string.begin.kotlin" } },
      end: '(")',
      endCaptures: { 1: { name: "punctuation.definition.string.end.kotlin" } },
      name: "string.quoted.double.kotlin",
      patterns: [{ include: "#kotlin-escapes" }],
    },
    "kotlin-escapes": {
      patterns: [
        { match: r`\\[\$"'\\ntbr]|\\u[0-9A-Fa-f]{4}`, name: "constant.character.escape.kotlin" },
        { match: r`\$\{'\$'\}`, name: "constant.character.escape.dollar.kotlin" },
      ],
    },
    "dollar-escape": { match: r`\$\{'\$'\}`, name: "constant.character.escape.dollar.kotlin" },
    "expression-string-body": {
      comment:
        "The body of a string that holds an expression. Whether one dollar or two open a Kotlin template is read from the text that opened the string, which a lookbehind at the body's start sees; streamlord.kotlin.template.injection colours the templates.",
      patterns: ['"""', '"'].flatMap((quote) =>
        [3, 2, 1].map((n) => ({
          begin: r`\G` + (n === 1 ? r`(?<=` + quote + ")" : opened(n, quote)),
          end: "(?=" + quote + ")",
          name: MARKERS[n],
          contentName: EMB,
          patterns: [{ include: "source.datastar" }, ...(quote === '"' ? [{ include: "#kotlin-escapes" }] : [])],
        })),
      ),
    },
    "expression-call-first-arg": expressionCall(
      "dataOnClick(\"...\"), dataText(\"...\") and friends: the first argument is the expression, in any kind of string, or passed by name. After a receiver too, where a grammar may colour the call from its dot.",
      RECV + r`\b((?:` + first + r`))(\s*\(\s*` + NAMED + ")",
      { 2: FN },
    ),
    "expression-call-second-arg": expressionCall(
      "dataOn(\"click\", \"...\"), dataComputed(\"name\", \"...\"): the second argument is the expression, the first a plain string. No capture of a rule that may run many times on a line has patterns of its own: the engine copies the line up to each such capture, which a long line pays for on every call.",
      RECV + r`\b((?:` + second + r`))(\s*\(\s*` + OTHER_NAMED + r`)(\$*"[^"\n]*")(\s*,\s*` + NAMED + ")",
      { 2: FN, 5: { name: "string.quoted.double.kotlin" } },
    ),
    "expression-call-signal-pair": expressionCall(
      "dataSignals(\"name\", \"...\"), dataComputed(\"name\", \"...\"): the first argument names a signal, the second is the expression.",
      RECV + r`\b(` + signalPairs + r`)(\s*\(\s*(?:name\s*(=)\s*)?)(\$*")` + SIGNAL_NAME + r`(")(\s*,\s*` + NAMED + ")",
      { 2: FN, 5: { name: DQ_START }, 6: { name: SIGNAL }, 7: { name: PATH }, 8: { name: DQ_END } },
    ),
    "expression-call-lone-object": expressionCall(
      "dataClass(\"{hidden: $a}\"), dataSignals(\"{...}\"): a lone object is the expression of a call whose expression is its last argument.",
      RECV + r`\b((?:` + last + r`))(\s*\(\s*` + NAMED + ")",
      { 2: FN },
      r`(?=\s*\{)`,
    ),
    "expression-call-named": {
      comment:
        "dataOn(expression = \"...\", event = \"click\"): the expression passed by name after another argument, which the rules above do not read. Only the string after expression = is one.",
      begin: RECV + r`\b((?:` + second + r`))(\s*\()(?=[^()\n]*\bexpression\s*=)`,
      beginCaptures: { 2: FN },
      end: r`\)`,
      patterns: [{ include: "#kotlin-brackets" }, ...expressionString(r`\bexpression\s*(=)\s*`), { include: "source.kotlin" }],
    },
    "expression-call-every-arg": {
      comment:
        "fitExpr(\"...\", \"...\", ...): every string argument is an expression, passed by its place or its name, on any line. What a bracket inside holds is Kotlin's.",
      begin: RECV + r`\b((?:` + every + r`))(\s*\()`,
      beginCaptures: { 2: FN },
      end: r`\)`,
      patterns: [{ include: "#kotlin-brackets" }, ...expressionString(r`(?:[A-Za-z_][A-Za-z0-9_]*\s*(=)\s*)?`), { include: "source.kotlin" }],
    },
    "expression-option": {
      comment:
        "payloadExpr = \"...\" and the other options of FetchOptions whose value is an expression, and each value of headerExprs = mapOf(\"X-Id\" to \"...\"), whose name is a plain string.",
      patterns: [
        expressionCall("An option set to an expression, on no receiver.", r`(?<!\.)\b(` + plainOptions + r`)(\s*)(=)(\s*)`, {}),
        {
          begin: r`(?<!\.)\b(` + mapOptions + r`)(\s*)(=)(\s*)(mapOf|mutableMapOf|hashMapOf|linkedMapOf)(\s*\()`,
          beginCaptures: { 3: { name: "keyword.operator.assignment.kotlin" }, 5: FN },
          end: r`\)`,
          patterns: [
            { include: "#kotlin-brackets" },
            expressionCall("A value after its name and to.", r`(\$*"[^"\n]*")(\s*)(to)(\s*)`, { 1: { name: "string.quoted.double.kotlin" }, 3: FN }),
            { include: "source.kotlin" },
          ],
        },
      ],
    },
    "kotlin-brackets": {
      comment: "Brackets inside a call this grammar reads, which hold Kotlin: another call's arguments, signal(\"m\").",
      begin: r`\(`,
      end: r`\)`,
      patterns: [{ include: "#kotlin-brackets" }, { include: "source.kotlin" }],
    },
    "signal-name-call": {
      comment: "dataBind(\"name\"), dataRef, dataIndicator: the argument names a signal.",
      match: RECV + r`\b((?:` + signalAttributes + r`))(\s*\(\s*)(\$*")` + SIGNAL_NAME + '(")',
      captures: { 2: FN, 4: { name: DQ_START }, 5: { name: SIGNAL }, 6: { name: PATH }, 7: { name: DQ_END } },
    },
    "expression-call-next-line": {
      comment:
        "A call whose arguments start on the next line: the expression is the first string at the start of a line for dataText and friends, the string after the first and its comma for dataOn and friends. The region ends after it, or at the closing parenthesis.",
      patterns: [
        {
          begin: RECV + r`\b((?:` + first + r`))(\s*\()[ \t]*$`,
          beginCaptures: { 2: FN },
          end: r`(?<=")|(?=\))`,
          patterns: [...expressionString(r`^\s*`), { include: "source.kotlin" }],
        },
        {
          begin: RECV + r`\b((?:` + second + r`))(\s*\()\s*(\$*"[^"\n]*")\s*,[ \t]*$`,
          beginCaptures: { 2: FN, 4: { name: "string.quoted.double.kotlin" } },
          end: r`(?<=")|(?=\))`,
          patterns: [...expressionString(r`^\s*`), { include: "source.kotlin" }],
        },
        {
          begin: RECV + r`\b((?:` + second + r`))(\s*\()[ \t]*$`,
          beginCaptures: { 2: FN },
          end: r`(?<=")|(?=\))`,
          patterns: [
            {
              begin: r`^\s*(\$*"[^"\n]*")\s*,`,
              beginCaptures: { 1: { name: "string.quoted.double.kotlin" } },
              end: r`(?<=")|(?=\))`,
              patterns: [...expressionString(r`\G\s*`), ...expressionString(r`^\s*`), { include: "source.kotlin" }],
            },
            { include: "source.kotlin" },
          ],
        },
      ],
    },
    "html-call": {
      comment: "patchElements(\"\"\"<div>...</div>\"\"\") and friends: HTML inside the string.",
      patterns: [true, false].map((raw) =>
        embedded(
          RECV + r`\b(` + html.join("|") + r`)(\s*\(\s*(?:(?:` + htmlNamed + r`)\s*=\s*)?\$*)` + (raw ? '(""")' : '(")'),
          { 2: FN, 4: { name: raw ? RAW_START : DQ_START } },
          raw,
          "meta.embedded.block.html",
          "text.html.basic",
        ),
      ),
    },
    "interpolate-call": {
      comment:
        "interpolate(\"Hello <b>...\", ...): core's format is HTML however it opens, first or after the prefixes the aliased bundle passes (`interpolate(listOf(\"data-star-\"), \"...\")`), or by name.",
      patterns: [true, false].map((raw) =>
        embedded(
          RECV +
            r`\b(interpolate)(\s*\(\s*)((?:(?:prefixes\s*=\s*)?listOf\s*\([^()\n]*\)\s*,\s*)?)((?:format\s*=\s*)?\$*)` +
            (raw ? '(""")' : '(")'),
          { 2: FN, 4: { patterns: [{ include: "source.kotlin" }] }, 6: { name: raw ? RAW_START : DQ_START } },
          raw,
          "meta.embedded.block.html",
          "text.html.basic",
        ),
      ),
    },
    "interpolate-call-next-line": {
      comment: "interpolate(\n    \"Hello <b>...\",\n): the format on a line of its own, as ktlint wraps a long call. The region ends after it, or at the closing parenthesis.",
      begin: RECV + r`\b(interpolate)(\s*\()[ \t]*$`,
      beginCaptures: { 2: FN },
      end: r`(?<=")|(?=\))`,
      patterns: [
        ...[true, false].map((raw) =>
          embedded(r`^\s*((?:format\s*=\s*)?\$*)` + (raw ? '(""")' : '(")'), { 2: { name: raw ? RAW_START : DQ_START } }, raw, "meta.embedded.block.html", "text.html.basic"),
        ),
        { include: "source.kotlin" },
      ],
    },
    "interpolate-block": {
      comment:
        "interpolate { \"Hello <b>${+name}</b>\" }: the literal that opens the block on its line, after prefixes and by the qualified name too, is markup however it opens where it writes a tag or marks a value, as the analysis reads the block. A literal further into a block is HTML when it opens with a tag, as anywhere: a rule reads one line.",
      patterns: [
        embedded(
          r`(\$*""")(?=(?:(?!""")[^\n])*?(?:<[A-Za-z/!]|\$\{\s*\+))(?<=\{[ \t]{0,8}\${0,4}""")(?<=` + AFTER_INTERPOLATE + r`\${0,4}""")`,
          { 1: { name: RAW_START } },
          true,
          "meta.embedded.block.html",
          "text.html.basic",
        ),
        embedded(
          r`(\$*")(?=(?:[^"\\\n]|\\.)*(?:<[A-Za-z/!]|\$\{\s*\+))(?<=\{[ \t]{0,8}\${0,4}")(?<=` + AFTER_INTERPOLATE + r`\${0,4}")`,
          { 1: { name: DQ_START } },
          false,
          "meta.embedded.block.html",
          "text.html.basic",
        ),
      ],
    },
    "trusted-call": {
      comment: "Trusted(\"bold <b>text</b>\"): core's markup, HTML however it opens.",
      patterns: htmlLiterals("", (quote) => TRUSTED_TAG + r`(?<=[(=][ \t]{0,4}\${0,4}` + quote + r`)(?<=` + AFTER_TRUSTED + r`\${0,4}` + quote + ")"),
    },
    "trusted-call-next-line": {
      comment:
        "Trusted(\n    \"bold <b>text</b>\",\n): the argument on a line of its own. The region opens at the end of the line, after Kotlin's grammar has read `Trusted(`, and ends after the argument or at the closing parenthesis.",
      begin: r`(?<=(?:^|[^\w.\x60])Trusted[ \t]{0,4}\()(?<!(?:class|fun)[ \t]{1,4}Trusted[ \t]{0,4}\()(?=[ \t]*$)`,
      end: r`(?<=")|(?=\))`,
      patterns: [...htmlLiterals(r`^\s*(?:html\s*=\s*)?`, () => TRUSTED_TAG), { include: "source.kotlin" }],
    },
    "script-call": {
      comment: "executeScript(\"\"\"...\"\"\"): JavaScript inside the string.",
      patterns: [true, false].map((raw) =>
        embedded(
          RECV + r`\b(` + script.join("|") + r`)(\s*\(\s*(?:(?:` + scriptNamed + r`)\s*=\s*)?\$*)` + (raw ? '(""")' : '(")'),
          { 2: FN, 4: { name: raw ? RAW_START : DQ_START } },
          raw,
          "meta.embedded.block.javascript",
          "source.js",
        ),
      ),
    },
  };

  return {
    $schema: "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    comment: "Generated by scripts/kotlinGrammar.mjs (npm run generate) from catalog/datastar-1.0.5.json. Do not edit by hand.",
    scopeName: "streamlord.kotlin.injection",
    injectionSelector: `L:source.kotlin -comment -string -meta.dsl-file.streamlord -${STRING} -${MARKER}`,
    patterns: [
      "dsl-file",
      "attributes-index",
      "html-call",
      "interpolate-call",
      "interpolate-call-next-line",
      "interpolate-block",
      "trusted-call",
      "trusted-call-next-line",
      "script-call",
      "html-string",
      "html-string-next-line",
    ].map((k) => ({ include: "#" + k })),
    repository,
  };
}

export function kotlinDslInjectionGrammar() {
  return {
    $schema: "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    comment:
      "Generated by scripts/kotlinGrammar.mjs (npm run generate). Inside a file that imports the DSL (meta.dsl-file.streamlord, opened by streamlord.kotlin.injection): its calls, and everything else that grammar colours, from that grammar's own rules.",
    scopeName: "streamlord.kotlin.dsl.injection",
    injectionSelector: `L:meta.dsl-file.streamlord -comment -string -${STRING} -${MARKER}`,
    patterns: [
      "expression-call-every-arg",
      "expression-option",
      "expression-call-signal-pair",
      "expression-call-second-arg",
      "expression-call-lone-object",
      "expression-call-first-arg",
      "signal-name-call",
      "expression-call-next-line",
      "expression-call-named",
      "attributes-index",
      "html-call",
      "interpolate-call",
      "interpolate-call-next-line",
      "interpolate-block",
      "trusted-call",
      "trusted-call-next-line",
      "script-call",
      "html-string",
      "html-string-next-line",
    ].map((k) => ({ include: "streamlord.kotlin.injection#" + k })),
  };
}

/** The Kotlin templates of a string that [n] dollars open a template in; a shorter run of dollars is text, a signal's `$` among it. */
export function kotlinTemplateInjectionGrammar(n) {
  const dollars = "\\$".repeat(n);
  // One dollar after a backslash is an escaped one; more are matched from the first, so a longer run leaves its first dollars as text.
  const before = n === 1 ? r`(?<!\\)` : "";
  return {
    $schema: "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    comment: `Generated by scripts/kotlinGrammar.mjs (npm run generate). Kotlin's templates in a string streamlord.kotlin.injection reads that ${n} dollar${n === 1 ? "" : "s"} open${n === 1 ? "s" : ""} a template in: what a template inserts is Kotlin's, never a signal.`,
    scopeName: `streamlord.kotlin.template${n === 1 ? "" : n}.injection`,
    injectionSelector: `L:${MARKERS[n]} -meta.template.expression.kotlin`,
    patterns: [
      // trimMargin's | before a line of the string is the string's, at any depth of the HTML read inside it.
      { match: r`^[ \t]*(\|)`, captures: { 1: { name: "string.quoted.triple.kotlin" } } },
      {
        match: before + "(" + dollars + r`)([A-Za-z_][A-Za-z0-9_]*)`,
        name: "meta.template.expression.kotlin",
        captures: { 1: { name: "punctuation.definition.template-expression.begin.kotlin" }, 2: { name: "variable.other.readwrite.kotlin" } },
      },
      {
        begin: before + dollars + r`\{` + (n === 1 ? r`(?!'\$'\})` : ""),
        end: r`\}`,
        name: "meta.template.expression.kotlin",
        beginCaptures: { 0: { name: "punctuation.definition.template-expression.begin.kotlin" } },
        endCaptures: { 0: { name: "punctuation.definition.template-expression.end.kotlin" } },
        patterns: [{ include: "#braces" }, { include: "source.kotlin" }],
      },
    ],
    repository: { braces: { begin: r`\{`, end: r`\}`, patterns: [{ include: "#braces" }, { include: "source.kotlin" }] } },
  };
}

/** The text a grammar is committed as. */
export const grammarText = (grammar) => JSON.stringify(grammar, null, 2) + "\n";
