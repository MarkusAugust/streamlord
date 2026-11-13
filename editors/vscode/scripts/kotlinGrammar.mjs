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
const ONE = "meta.kotlin-template.one.streamlord";
const TWO = "meta.kotlin-template.two.streamlord";
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
  return [
    { begin: r`\G(?<=\$\$` + quote + ")", end: "(?=" + quote + ")", name: TWO, contentName: content, patterns: [...escapes, ...inner] },
    { begin: r`\G`, end: "(?=" + quote + ")", name: ONE, contentName: content, patterns: [...escapes, ...single, ...inner] },
  ];
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
  const at = (test) => alternation(expression.filter(([, s]) => test(s.arg)).map(([n]) => n));
  const first = at((a) => a === 0);
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
      patterns: [true, false].map((two) => ({
        begin: (two ? r`(\$\$""")` : r`(""")`) + r`[ \t]*$`,
        beginCaptures: { 1: { name: RAW_START } },
        end: '(""")',
        endCaptures: { 1: { name: RAW_END } },
        name: STRING,
        patterns: [
          {
            begin: r`^(?=[ \t]*<(?:[A-Za-z]|!--|!(?i:doctype)))`,
            end: '(?=""")',
            name: two ? TWO : ONE,
            contentName: "meta.embedded.block.html",
            patterns: [...(two ? [] : [{ include: "#dollar-escape" }]), { include: "text.html.basic" }],
          },
          { begin: r`^(?=[ \t]*\S)`, end: '(?=""")', name: (two ? TWO : ONE) + " string.quoted.triple.kotlin", patterns: two ? [] : [{ include: "#dollar-escape" }] },
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
      patterns: [
        { begin: r`\G(?<=\$\$""")`, end: '(?=""")', name: TWO, contentName: EMB, patterns: [{ include: "source.datastar" }] },
        { begin: r`\G(?<=""")`, end: '(?=""")', name: ONE, contentName: EMB, patterns: [{ include: "source.datastar" }] },
        { begin: r`\G(?<=\$\$")`, end: '(?=")', name: TWO, contentName: EMB, patterns: [{ include: "source.datastar" }, { include: "#kotlin-escapes" }] },
        { begin: r`\G(?<=")`, end: '(?=")', name: ONE, contentName: EMB, patterns: [{ include: "source.datastar" }, { include: "#kotlin-escapes" }] },
      ],
    },
    "expression-call-first-arg": expressionCall(
      "dataOnClick(\"...\"), dataText(\"...\") and friends: the first argument is the expression, in any kind of string, or passed by name. After a receiver too, where a grammar may colour the call from its dot.",
      RECV + r`\b((?:` + first + r`))(\s*\(\s*` + NAMED + ")",
      { 2: FN },
      4,
    ),
    "expression-call-second-arg": expressionCall(
      "dataOn(\"click\", \"...\"), dataComputed(\"name\", \"...\"): the second argument is the expression. The first is a plain string, or the signal dataSignals and dataComputed name.",
      RECV + r`\b((?:` + second + r`))(\s*\(\s*)(\$*"[^"\n]*")(\s*,\s*` + NAMED + ")",
      {
        2: FN,
        4: {
          patterns: [
            {
              match: r`(?<=dataSignals\(|dataComputed\()(\$*")([A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*)((?:\.[A-Za-z0-9_]+)*)(")`,
              captures: { 1: { name: DQ_START }, 2: { name: SIGNAL }, 3: { name: PATH }, 4: { name: DQ_END } },
            },
            { match: ".+", name: "string.quoted.double.kotlin" },
          ],
        },
      },
      6,
    ),
    "expression-call-lone-object": expressionCall(
      "dataClass(\"{hidden: $a}\"), dataSignals(\"{...}\"): a lone object is the expression of a call whose expression is its last argument.",
      RECV + r`\b((?:` + last + r`))(\s*\(\s*` + NAMED + ")",
      { 2: FN },
      4,
      r`(?=\s*\{)`,
    ),
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

/** The text a grammar is committed as. */
export const grammarText = (grammar) => JSON.stringify(grammar, null, 2) + "\n";
