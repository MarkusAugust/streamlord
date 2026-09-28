import type { ShikiConfig } from "astro"
import { defineConfig } from "astro/config"
import type { ThemeRegistration } from "shiki"
import darkPlus from "shiki/themes/dark-plus.mjs"
import lightPlus from "shiki/themes/light-plus.mjs"
import {
  DARK_RULES,
  LIGHT_RULES,
  type Rule,
} from "../editors/vscode/src/palette.ts"
import datastarExpression from "../editors/vscode/syntaxes/datastar-expression.tmLanguage.json"
import htmlInjection from "../editors/vscode/syntaxes/html-injection.tmLanguage.json"
import kotlinInjection from "../editors/vscode/syntaxes/kotlin-injection.tmLanguage.json"
import { rehypeTables } from "./src/plugins/rehype-tables.mjs"
import { remarkTabs } from "./src/plugins/remark-tabs.mjs"

/**
 * The documentation highlights Datastar expressions with the same grammars and
 * the same colours as the VS Code extension.
 *
 * The grammars are read straight out of `editors/vscode/syntaxes/`, and the
 * token colours straight out of the extension's `palette.ts`. Nothing is
 * copied, so the two can never drift: change the extension's palette and the
 * next documentation build follows.
 *
 * `palette.ts` must stay free of `vscode` imports for this to work. It is pure
 * data today, and there is no reason for it to become anything else.
 */
/**
 * `"html"` has to come first, and that is not a style choice.
 *
 * The Kotlin injection includes `text.html.basic`, so that HTML inside a
 * triple-quoted Kotlin string is highlighted as HTML. If Shiki compiles the
 * Kotlin grammar before the HTML one, `text.html.basic` is built as a
 * dependency with no injections attached, and the compiled grammar is cached —
 * after which our HTML injection never applies and every `data-*` attribute in
 * an HTML block renders as a plain attribute. Loading the base HTML language
 * first means it is compiled with the injection in place.
 *
 * Measured, not guessed: with the order reversed, `data-on-input__debounce.300ms`
 * comes out as one span instead of prefix, plugin, separator and modifier.
 */
// Astro types `langs` as registration objects only, while Shiki also accepts
// the name of a bundled language at runtime. The cast is for that one string.
const datastarLangs = [
  "html",
  {
    ...datastarExpression,
    name: "datastar-expression",
  },
  {
    ...kotlinInjection,
    name: "streamlord-kotlin-injection",
    injectTo: ["source.kotlin"],
  },
  {
    ...htmlInjection,
    name: "streamlord-html-injection",
    injectTo: ["text.html.basic", "text.html.derivative", "source.kotlin"],
  },
] as ShikiConfig["langs"]

/**
 * A bundled theme with the Streamlord rules appended.
 *
 * Only the concepts Datastar adds get a rule. Everything that is ordinary
 * Kotlin or HTML keeps the theme's own colours, which is why a reader who uses
 * VS Code sees the code the way their editor would show it.
 */
const withStreamlordRules = (
  theme: ThemeRegistration,
  rules: Rule[],
): ThemeRegistration => ({
  ...theme,
  settings: [...(theme.settings ?? []), ...rules],
})

export default defineConfig({
  site: "https://streamlord.netlify.app",
  markdown: {
    remarkPlugins: [remarkTabs],
    rehypePlugins: [rehypeTables],
    shikiConfig: {
      themes: {
        light: withStreamlordRules(lightPlus, LIGHT_RULES),
        dark: withStreamlordRules(darkPlus, DARK_RULES),
      },
      langs: datastarLangs,
      wrap: false,
    },
  },
})
