/**
 * The keywords, constants and globals of syntaxes/datastar-expression.tmLanguage.json, written from
 * catalog/javascript-words.json, the list the analysis colours IntelliJ's by. The rest of that
 * grammar is written by hand; these three rules are not.
 */
const match = (words) => String.raw`(?<![.$])\b(` + words.join("|") + String.raw`)\b`;

export function withExpressionWords(grammar, words) {
  const repository = { ...grammar.repository };
  repository.keywords = {
    comment: "Each keyword with the scope TypeScript's grammar gives it. Written by scripts/generate.mjs from catalog/javascript-words.json, with the constants and the globals.",
    patterns: Object.entries(words.keywords).map(([name, list]) => ({ match: match(list), name })),
  };
  repository.constants = { match: match(words.constants), name: "constant.language.datastar" };
  repository.globals = { match: match(words.globals), name: "support.class.global.datastar" };
  return { ...grammar, repository };
}
