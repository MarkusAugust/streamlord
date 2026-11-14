/**
 * The HTML injection grammars, built from the catalog: syntaxes/html-injection.tmLanguage.json, which reads a
 * Datastar attribute in a tag, and syntaxes/html-element-injection.tmLanguage.json, the same rules each read
 * only where nothing but attributes follow it. Written by `npm run generate`; test/generated.test.ts fails
 * when a committed file is not what this writes.
 */

const r = String.raw;

/** Longest first, so that no name is taken for the start of a longer one. */
const alternation = (names) => [...new Set(names)].sort((a, b) => b.length - a.length || (a < b ? -1 : 1)).join("|");

const SEPARATOR = { name: "punctuation.separator.key-value.html" };
const quote = (single, edge) => ({ name: `string.quoted.${single ? "single" : "double"}.html punctuation.definition.string.${edge}.html` });
/** A Kotlin `\$` is an escape before the signal; `${'$'}` writes a dollar. */
const KOTLIN_DOLLARS = [
  { match: r`\\(?=\$)`, name: "constant.character.escape.kotlin" },
  { match: r`\$\{'\$'\}`, name: "constant.character.escape.dollar.kotlin" },
];
const ENTITY = { match: "&amp;|&lt;|&gt;|&#(?!3[49];)\\d+;", name: "constant.character.entity.html" };

/** The three ways a value is quoted: in double quotes, in single, and in the escaped quotes of a plain Kotlin string, which ends the value where the Kotlin string ends. */
const QUOTES = [
  { open: '"', close: '(")' },
  { open: "'", close: "(')", single: true },
  { open: r`\\"`, close: r`(\\")|(?=")` },
];

function value(q, contentName, patterns) {
  return {
    begin: r`(\s*=\s*)(` + q.open + ")",
    beginCaptures: { 1: SEPARATOR, 2: quote(q.single, "begin") },
    end: q.close,
    endCaptures: { 1: quote(q.single, "end") },
    contentName,
    patterns,
  };
}

/** A string in escaped quotes, as JSON is written in a plain Kotlin string: '{\"a\": 1}', and its key, as a quoted key is one. */
const ESCAPED_JSON = [
  {
    match: r`(?:(?<=[{,])|^)(\s*)(\\"(?:[^"\\]|\\[^"])*\\")(\s*)(:)(?!:)`,
    captures: { 2: { name: "variable.other.property.key.datastar" }, 4: { name: "punctuation.separator.key-value.datastar" } },
  },
  {
    comment: "A region, so that a template engine's placeholder inside it is the engine's. It ends at the Kotlin string's end too.",
    begin: r`\\"`,
    end: r`\\"|(?=")`,
    name: "string.quoted.double.datastar",
    patterns: [{ match: r`\\\\.`, name: "constant.character.escape.datastar" }],
  },
];

const NOT_UNQUOTED = r`[\s"'=<>` + "`" + r`\\]`;

const KINDS = {
  expression: {
    comment: "An attribute whose value is an expression or a filter: the value read as one, in any quotes or none.",
    values: [
      ...QUOTES.map((q) =>
        value(q, "meta.value.datastar meta.embedded.inline.datastar", [
          ENTITY,
          ...ESCAPED_JSON,
          { include: q.single ? "source.datastar#expression-single" : "source.datastar" },
          ...KOTLIN_DOLLARS,
        ]),
      ),
      {
        comment: "A value without quotes, data-show=$open, as HTML allows: up to white space or the end of the tag.",
        begin: r`(\s*=\s*)(?!` + NOT_UNQUOTED + ")",
        beginCaptures: { 1: SEPARATOR },
        end: "(?=" + NOT_UNQUOTED + "|$)",
        contentName: "meta.value.datastar meta.embedded.inline.datastar",
        patterns: [{ include: "source.datastar" }],
      },
    ],
  },
  signal: {
    comment: "An attribute whose value names a signal, data-bind=\"user.name\": the name as the client reads it, when the value is one name.",
    values: QUOTES.map((q) =>
      value(q, "meta.value.datastar", [
        {
          match: r`(?<=["'])\s*([A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*)((?:\.[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*)*)\s*(?=\\?["'])`,
          captures: { 1: { name: "variable.other.constant.signal.datastar" }, 2: { name: "variable.other.property.signal-path.datastar" } },
        },
      ]),
    ),
  },
  plain: {
    comment: "An attribute whose value is a name, a selector or text, not an expression: its name is Datastar's, its value a string as HTML has it.",
    values: QUOTES.map((q) =>
      value(q, `meta.value.datastar string.quoted.${q.single ? "single" : "double"}.html`, [
        { match: r`\\(?=\$)`, name: "constant.character.escape.kotlin" },
        { match: "&quot;|&amp;|&lt;|&gt;|&#\\d+;", name: "constant.character.entity.html" },
      ]),
    ),
  },
};

/** The parts of a name after its plugin: `:click`, `__debounce`, `.500ms`. */
const NAME_PARTS = [
  {
    match: r`(:)((?:[\p{L}\p{N}.-]|_(?!_))+)`,
    captures: { 1: { name: "entity.other.attribute-name.datastar punctuation.separator.key.datastar" }, 2: { name: "entity.other.attribute-name.datastar.key" } },
  },
  {
    match: r`(__)([A-Za-z][A-Za-z0-9-]*)?`,
    captures: { 1: { name: "entity.other.attribute-name.datastar punctuation.definition.modifier.datastar" }, 2: { name: "keyword.other.modifier.datastar" } },
  },
  ...[
    [r`(\d+(?:ms|s))(?![A-Za-z0-9-]|_(?!_))`, "constant.numeric.duration.datastar"],
    [r`(\d+)(?![A-Za-z0-9-]|_(?!_))`, "constant.numeric.datastar"],
    [r`((?:[A-Za-z0-9-]|_(?!_))*)`, "variable.other.enummember.modifier-arg.datastar"],
  ].map(([arg, name]) => ({
    match: r`(\.)` + arg,
    captures: { 1: { name: "entity.other.attribute-name.datastar punctuation.separator.argument.datastar" }, 2: { name } },
  })),
];

/** The catalog's value kinds each rule reads. */
export const VALUE_KINDS = { expression: ["expression", "filter"], signal: ["signal"], plain: ["text", "none"] };

export function htmlInjectionGrammar(catalog) {
  const names = (kinds) => alternation(catalog.attributes.filter((a) => kinds.includes(a.valueKind)).map((a) => a.name));
  const repository = {
    "attribute-name": {
      comment:
        "data-on:click__debounce.500ms.leading -> prefix | plugin | :key | __modifier.argument, as one match, which the Kotlin grammar reads a name of kotlinx.html's attributes[...] with. Not anchored: capture sub-patterns run mid-line, where ^ never matches.",
      match: r`((?i:data-(?:star-)?))([A-Za-z][A-Za-z-]*)((?::(?:[\p{L}\p{N}.-]|_(?!_))+)?(?:__(?:[A-Za-z0-9.-]|_(?!_))+)*)`,
      captures: {
        1: { name: "entity.other.attribute-name.datastar.prefix" },
        2: { name: "entity.other.attribute-name.datastar.plugin" },
        3: { patterns: NAME_PARTS },
      },
    },
    "attribute-value-escaped": {
      comment:
        "Any other attribute's value in escaped quotes, as a plain Kotlin string writes it: class=\\\"btn\\\". HTML's grammar would read the backslash as an unquoted value and the quote after it as an error.",
      match: r`(?<==)(\\")((?:[^"\\]|\\[^"])*)(\\")`,
      captures: {
        1: { name: "string.quoted.double.html punctuation.definition.string.begin.html" },
        2: { name: "string.quoted.double.html" },
        3: { name: "string.quoted.double.html punctuation.definition.string.end.html" },
      },
    },
  };
  for (const [kind, { comment, values }] of Object.entries(KINDS)) {
    repository[`datastar-attribute-${kind}`] = {
      comment:
        comment +
        " The catalog's attributes only: the page's own data-color, or a typo such as data-onn, is left to HTML, and the check after the name keeps data-on-intersects out. In any case, as the browser lowercases a name. The parts of the name and the value are rules of the attribute's region, which ends after the value's quote, or where white space or the tag's end follows a name with none. No capture has patterns of its own: the engine copies the line up to each such capture, which a line of many attributes pays for on every one.",
      begin: r`\b((?i:data-(?:star-)?))((?i:` + names(VALUE_KINDS[kind]) + r`))(?=[:=\s/>"]|__|$)`,
      beginCaptures: { 1: { name: "entity.other.attribute-name.datastar.prefix" }, 2: { name: "entity.other.attribute-name.datastar.plugin" } },
      end: r`(?<=["'])|(?=\s*/?>|")|(?=\s+[^\s=])|(?=\s*$)`,
      name: "meta.attribute.datastar",
      patterns: [...values, ...NAME_PARTS],
    };
  }
  return {
    $schema: "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    comment:
      "Generated by scripts/htmlGrammar.mjs (npm run generate) from catalog/datastar-1.0.5.json. Do not edit by hand. Not inside another attribute's value: th:attr=\"data-signals=${x}\" is Thymeleaf's.",
    scopeName: "streamlord.html.injection",
    injectionSelector:
      "L:meta.tag -comment -meta.attribute.datastar -meta.embedded.inline.datastar -meta.value.datastar -string.quoted.double.html -string.quoted.single.html",
    patterns: ["datastar-attribute-expression", "datastar-attribute-signal", "datastar-attribute-plain", "attribute-value-escaped"].map((k) => ({ include: "#" + k })),
    repository,
  };
}

/** Nothing but attributes, quoted or not, up to the end of what is read: an SVG start tag's capture, never an element's text. */
const ATTRIBUTES_ONLY = `(?=(?:"[^"]*"|'[^']*'|[^<>"'])*$)`;

const RULES = ["datastar-attribute-expression", "datastar-attribute-signal", "datastar-attribute-plain"];

/** A rule of the HTML injection with its own includes named from outside that grammar. */
function qualified(value) {
  if (Array.isArray(value)) return value.map(qualified);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([k, v]) => [k, k === "include" && v.startsWith("#") ? "streamlord.html.injection" + v : qualified(v)]),
    );
  }
  return value;
}

export function htmlElementInjectionGrammar(htmlInjection) {
  return {
    $schema: "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    comment:
      "Generated by scripts/htmlGrammar.mjs (npm run generate) from html-injection.tmLanguage.json. The Datastar attributes of an SVG or MathML start tag written on one line, which VS Code's HTML grammar reads in a capture of the element's region, where no meta.tag scope stands. The element's text has the same scopes, so an attribute is read only where nothing but attributes follow it: the capture ends before the tag's >, and text runs on to the next tag's <.",
    scopeName: "streamlord.html.element.injection",
    injectionSelector: "L:meta.element -meta.tag -comment -meta.attribute.datastar -meta.embedded.inline.datastar -meta.value.datastar",
    patterns: RULES.map((name) => {
      const { comment, ...rule } = qualified(htmlInjection.repository[name]);
      return { ...rule, begin: ATTRIBUTES_ONLY + rule.begin };
    }),
  };
}

const PLACEHOLDER = "meta.template.placeholder.streamlord";
const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * syntaxes/placeholders-html-injection.tmLanguage.json, built from catalog/template-syntax.json, the
 * list the analysis leaves out of its checks: an engine's construct in a value of an HTML file.
 */
export function htmlPlaceholdersGrammar(syntax) {
  // A directive is its word, and the condition in parentheses after it where one follows.
  const words = Object.entries(syntax.directives).map(([sigil, list]) => escape(sigil) + "(?:" + list.join("|") + ")").join("|");
  const AFTER = "(?:^|(?<=[\\s>]))";
  return {
    $schema: "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    comment:
      "Generated by scripts/htmlGrammar.mjs (npm run generate) from catalog/template-syntax.json, the analysis's list. A template engine's constructs in a Datastar attribute's value in an HTML file, which Thymeleaf, Pebble and the other engines VS Code reads as HTML write before the browser reads the value: the analysis leaves them out, and they get no Datastar scope. A ${ inside a JavaScript template is JavaScript's own. The engines with a language of their own have their placeholders from their own grammar.",
    scopeName: "streamlord.placeholders.html.injection",
    injectionSelector:
      "L:text.html.derivative meta.value.datastar -string.template.datastar -meta.template.placeholder.streamlord, L:text.html.basic meta.value.datastar -string.template.datastar -meta.template.placeholder.streamlord",
    patterns: [
      ...syntax.pairs.map(([open, close]) => ({ begin: escape(open), end: escape(close), name: PLACEHOLDER })),
      { begin: syntax.braces.map(escape).join("|"), end: "\\}", name: PLACEHOLDER, patterns: [{ include: "#braces" }] },
      { begin: AFTER + "(?:" + words + ")\\b\\s*\\(", end: "\\)", name: PLACEHOLDER, patterns: [{ include: "#parens" }] },
      { match: AFTER + "(?:" + words + ")\\b", name: PLACEHOLDER },
    ],
    repository: {
      braces: { begin: "\\{", end: "\\}", patterns: [{ include: "#braces" }] },
      parens: { begin: "\\(", end: "\\)", patterns: [{ include: "#parens" }] },
    },
  };
}
