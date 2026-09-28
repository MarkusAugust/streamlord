/**
 * The Datastar grammars and the colours they are painted with.
 *
 * Extracted from astro.config.ts so the landing page can highlight its example with the
 * same rules as every markdown fence. One definition, two callers.
 */
import type { ThemeRegistration } from "shiki"
import lightPlus from "shiki/themes/light-plus.mjs"
import tokyoNight from "shiki/themes/tokyo-night.mjs"

import {
  DARK_RULES,
  LIGHT_RULES,
  type Rule,
} from "../../editors/vscode/src/palette.ts"
import datastarExpression from "../../editors/vscode/syntaxes/datastar-expression.tmLanguage.json"
import htmlInjection from "../../editors/vscode/syntaxes/html-injection.tmLanguage.json"
import kotlinInjection from "../../editors/vscode/syntaxes/kotlin-injection.tmLanguage.json"

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
]

/**
 * A bundled theme with the Streamlord rules appended.
 *
 * Tokyo Night rather than Dark+ for the dark side. Measured on a real Kotlin block, every
 * bundled theme leaves 53–56% of the characters in its default foreground, because readable
 * code is mostly monochrome — a theme cannot make Kotlin colourful, since Shiki has the
 * TextMate grammar and not the language server's semantic tokens. What a theme does change
 * is the ground it sits on, and #1a1b26 carries the blue-black of the rest of this site
 * where #1E1E1E is the most neutral grey there is.
 *
 * Only the concepts Datastar adds get a rule. Everything that is ordinary
 * Kotlin or HTML keeps the theme's own colours, which is why a reader who uses
 * VS Code sees the code the way their editor would show it.
 */
const withStreamlordRules = (
  theme: ThemeRegistration,
  rules: Rule[],
): ThemeRegistration => {
  // A bundled theme keeps its rules in `tokenColors`; a raw TextMate theme uses `settings`.
  // Shiki normalises both, so reading only one silently produces a theme with nothing in it
  // but our sixteen Datastar rules — every Kotlin keyword, string and type left uncoloured,
  // and no error anywhere. That is exactly what happened, so the shape is checked here.
  const base = theme as ThemeRegistration & {
    tokenColors?: ThemeRegistration["settings"]
  }
  const own = base.tokenColors ?? base.settings

  if (!own?.length) {
    throw new Error(
      `The theme "${base.name ?? "?"}" has neither tokenColors nor settings. ` +
        "Appending the Streamlord rules to it would throw its own colours away.",
    )
  }

  return base.tokenColors
    ? { ...base, tokenColors: [...base.tokenColors, ...rules] }
    : { ...base, settings: [...own, ...rules] }
}

export const shikiThemes = {
  light: withStreamlordRules(lightPlus, LIGHT_RULES),
  dark: withStreamlordRules(tokyoNight, DARK_RULES),
}

export { datastarLangs }
