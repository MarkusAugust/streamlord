/**
 * Generates src/styles/theme.css from fristil.tema.json, then puts the navy back.
 *
 * Fristil owns the lightness of every colour and the consumer owns the hue, which is how
 * the contrast promises hold by construction. The two neutral layers deliberately take
 * only a fraction of the brand's chroma, 8 percent for the page and 12 percent for a
 * raised surface, because a page with colour in it looks painted rather than neutral.
 *
 * This site wants the colour. Its large flat surfaces are the thing you notice, and at
 * 8 percent of #0b0f1c there is no navy left in them. Raising the brand's own chroma
 * instead does not work: the neutral family also carries the body text and the borders,
 * and they turn blue long before the page does.
 *
 * So the three layers are rewritten here to the same lightness Fristil chose and the
 * brand's full chroma. Lightness is what the promises are made of, so leaving it alone
 * is what keeps them, and `fristil sjekk-tema` below is what proves it rather than
 * asserts it.
 */
import { execFileSync } from "node:child_process"
import { writeFileSync } from "node:fs"

const RECIPE = "fristil.tema.json"
const OUT = "src/styles/theme.css"

/**
 * The three layers, as the value Fristil generates and the value we want instead.
 *
 * Both sides are written out so a replacement cannot quietly miss. Fristil's numbers are
 * derived from `ROLES` and `NEUTRAL_LAYERS`, and when a version changes one of them the
 * expected value stops matching and this script says so, which is the whole point: a
 * stale colour that still parses is the failure worth guarding against.
 */
const LAYERS = [
  { token: "neutral-raised", from: "#e4e4e7", to: "#dde4f8" },
  { token: "neutral-surface", from: "#f0f2f6", to: "#edf2ff" },
  { token: "neutral-canvas", from: "#111213", to: "#0d111e" },
  { token: "neutral-surface", from: "#232427", to: "#1f2332" },
  { token: "neutral-raised", from: "#323335", to: "#2d3241" },
]

const fristil = (...args: string[]) =>
  execFileSync("bunx", ["@fristil/designsystem", ...args], { encoding: "utf8" })

let css = fristil("tema", RECIPE)

for (const { token, from, to } of LAYERS) {
  const pattern = new RegExp(`(--fs-color-${token}:\\s*)${from}\\b`, "g")
  const hits = css.match(pattern)?.length ?? 0
  // The light block is written once and the dark one twice, for the media query and for
  // the explicit data-theme. A light value therefore lands once and a dark value twice.
  if (hits === 0) {
    throw new Error(
      `--fs-color-${token} is no longer ${from}. Fristil has changed the lightness or ` +
        `the chroma of that layer, so the replacement in LAYERS is out of date.`,
    )
  }
  css = css.replace(pattern, `$1${to}`)
}

// Written from the command's output rather than its --ut flag, so that the layers can be
// rewritten before the file exists. The flag ends the file with one newline and stdout
// carries a second, which biome then reports as a formatting error.
writeFileSync(OUT, `${css.trimEnd()}\n`)

// Inherits stdio so the tool's own words reach the terminal, and its exit code ends the
// run: a theme that breaks a promise must not be committable.
execFileSync("bunx", ["@fristil/designsystem", "sjekk-tema", OUT], {
  stdio: "inherit",
})
