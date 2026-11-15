/** The HTML injection grammars, built from the catalog; see htmlGrammar.mjs. */
export function htmlInjectionGrammar(catalog: unknown): object;
export function htmlElementInjectionGrammar(htmlInjection: unknown): object;
/** The catalog's value kinds each attribute rule of the HTML injection reads. */
export const VALUE_KINDS: { expression: string[]; signal: string[]; plain: string[] };
/** The HTML file's placeholders, built from catalog/template-syntax.json; see htmlGrammar.mjs. */
export function htmlPlaceholdersGrammar(syntax: unknown): object;
