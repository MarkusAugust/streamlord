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
const NAMED = r`(?:expression\s*=\s*)?`;
const SIGNAL_NAME = r`([A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*)((?:\.[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*)*)`;
const RAW_QUOTE = r`(\$*""")`;
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

/** A string that holds HTML or JavaScript: raw or plain, its templates marked. */
function embedded(begin, captures, raw, content, inner) {
  return {
    begin,
    beginCaptures: captures,
    end: raw ? '(""")' : '(")',
    endCaptures: { 1: { name: raw ? RAW_END : DQ_END } },
    patterns: marked(raw ? '"""' : '"', raw, content, [{ include: inner }], raw ? [{ include: "#dollar-escape" }] : []),
    name: STRING,
  };
}

/** Two rules, a raw string and a plain one, opened by [head] with the string in group [group]. */
function expressionCall(comment, head, captures, group, lookahead = "") {
  return {
    comment,
    patterns: [true, false].map((raw) => ({
      begin: head + (raw ? RAW_QUOTE : QUOTE) + lookahead,
      beginCaptures: { ...captures, [group]: { name: raw ? RAW_START : DQ_START } },
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
    beginCaptures: { 1: { name: raw ? RAW_START : DQ_START } },
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
  const html = Object.keys(sites.html).filter((n) => !n.startsWith("$"));
  const script = Object.keys(sites.script).filter((n) => !n.startsWith("$"));
  const htmlNamed = [...new Set(html.map((n) => sites.html[n].named))].join("|");
  const scriptNamed = [...new Set(script.map((n) => sites.script[n].named))].join("|");
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
          match: r`(@Language\(\s*"(?i:html)"\s*\))(?=\s*(?:va[lr]\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:)`,
          captures: { 1: { name: "storage.type.annotation.kotlin" } },
        },
        {
          comment:
            "The annotation on a function or property: ends after the next triple-quoted literal, or after a double-quoted literal that ends the line (`val x = \"<b>\"`). A double-quoted string before that, such as a parameter default, is a plain string.",
          begin: r`(@Language\(\s*"(?i:html)"\s*\))`,
          beginCaptures: { 1: { name: "storage.type.annotation.kotlin" } },
          end: r`(?<=""")|(?<=")\s*$`,
          patterns: [{ include: "#html-triple-string" }, { include: "#html-double-string" }, { include: "#plain-double-string" }],
          name: MARKER,
        },
        {
          begin: r`(//\s*language=(?i:html)\b[^\n]*)`,
          beginCaptures: { 1: { name: "comment.line.double-slash.kotlin" } },
          end: r`(?<=""")|(?<=")\s*$`,
          patterns: [{ include: "#html-triple-string" }, { include: "#html-double-string" }, { include: "#plain-double-string" }],
          name: MARKER,
        },
        embedded(r`(\$*""")(?=\s*<(?:[A-Za-z]|!--|!(?i:doctype)))`, { 1: { name: RAW_START } }, true, "meta.embedded.block.html", "text.html.basic"),
        embedded(r`(\$*")(?=<(?:[A-Za-z]|!--))`, { 1: { name: DQ_START } }, false, "meta.embedded.block.html", "text.html.basic"),
      ],
    },
    "html-string-next-line": {
      comment:
        "A raw string that ends its first line: HTML when its next line opens with a tag, as `val h = \"\"\"` and `\"\"\".trimIndent()` write it, else a string. Its first line cannot tell which, so it is read here either way, with the templates its dollars open.",
      patterns: [3, 2, 1].map((n) => ({
        begin: "(" + "\\$".repeat(n - 1) + '""")' + r`[ \t]*$`,
        beginCaptures: { 1: { name: RAW_START } },
        end: '(""")',
        endCaptures: { 1: { name: RAW_END } },
        name: STRING,
        patterns: [
          {
            begin: r`^(?=[ \t]*<(?:[A-Za-z]|!--|!(?i:doctype)))`,
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
      ...embedded(r`(\$*")(?=<(?:[A-Za-z]|!--))`, { 1: { name: DQ_START } }, false, "meta.embedded.block.html", "text.html.basic"),
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
      4,
    ),
    "expression-call-second-arg": expressionCall(
      "dataOn(\"click\", \"...\"), dataComputed(\"name\", \"...\"): the second argument is the expression, the first a plain string. No capture of a rule that may run many times on a line has patterns of its own: the engine copies the line up to each such capture, which a long line pays for on every call.",
      RECV + r`\b((?:` + second + r`))(\s*\(\s*)(\$*"[^"\n]*")(\s*,\s*` + NAMED + ")",
      { 2: FN, 4: { name: "string.quoted.double.kotlin" } },
      6,
    ),
    "expression-call-signal-pair": expressionCall(
      "dataSignals(\"name\", \"...\"), dataComputed(\"name\", \"...\"): the first argument names a signal, the second is the expression.",
      RECV + r`\b(dataSignals|dataComputed)(\s*\(\s*)(\$*")` + SIGNAL_NAME + r`(")(\s*,\s*` + NAMED + ")",
      { 2: FN, 4: { name: DQ_START }, 5: { name: SIGNAL }, 6: { name: PATH }, 7: { name: DQ_END } },
      9,
    ),
    "expression-call-lone-object": expressionCall(
      "dataClass(\"{hidden: $a}\"), dataSignals(\"{...}\"): a lone object is the expression of a call whose expression is its last argument.",
      RECV + r`\b((?:` + last + r`))(\s*\(\s*` + NAMED + ")",
      { 2: FN },
      4,
      r`(?=\s*\{)`,
    ),
    "expression-call-every-arg": {
      comment:
        "fitExpr(\"...\", \"...\", ...): every string argument is an expression, passed by its place or its name, on any line. What a bracket inside holds is Kotlin's.",
      begin: RECV + r`\b((?:` + every + r`))(\s*\()`,
      beginCaptures: { 2: FN },
      end: r`\)`,
      patterns: [{ include: "#kotlin-brackets" }, ...expressionString(r`(?:[A-Za-z_][A-Za-z0-9_]*\s*=\s*)?`), { include: "source.kotlin" }],
    },
    "expression-option": {
      comment:
        "payloadExpr = \"...\" and the other options of FetchOptions whose value is an expression, and each value of headerExprs = mapOf(\"X-Id\" to \"...\"), whose name is a plain string.",
      patterns: [
        expressionCall("An option set to an expression, on no receiver.", r`(?<!\.)\b(` + plainOptions + r`)(\s*)(=)(\s*)`, { 3: { name: "keyword.operator.assignment.kotlin" } }, 5),
        {
          begin: r`(?<!\.)\b(` + mapOptions + r`)(\s*)(=)(\s*)(mapOf|mutableMapOf|hashMapOf|linkedMapOf)(\s*\()`,
          beginCaptures: { 3: { name: "keyword.operator.assignment.kotlin" }, 5: FN },
          end: r`\)`,
          patterns: [
            { include: "#kotlin-brackets" },
            expressionCall("A value after its name and to.", r`(\$*"[^"\n]*")(\s*)(to)(\s*)`, { 1: { name: "string.quoted.double.kotlin" }, 3: FN }, 5),
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
    patterns: ["dsl-file", "attributes-index", "html-call", "script-call", "html-string", "html-string-next-line"].map((k) => ({ include: "#" + k })),
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
      "attributes-index",
      "html-call",
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
