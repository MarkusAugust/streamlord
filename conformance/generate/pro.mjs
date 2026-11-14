// Writes conformance/reference/pro-actions.json from the actions page of Datastar's reference: the
// signature it gives each Pro action the catalog lists, `npm run pro`. Pro's code is never read; the
// page is public. Run it when the reference changes, or when the catalog gains a Pro action.
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const PAGE = 'https://data-star.dev/reference/actions'
const conformance = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const catalog = JSON.parse(readFileSync(resolve(conformance, '..', 'catalog', 'datastar-1.0.5.json'), 'utf8'))
const pro = catalog.actions.filter((a) => a.pro).map((a) => a.name)

const response = await fetch(PAGE)
if (!response.ok) throw new Error(`${PAGE}: ${response.status} ${response.statusText}`)
// The page's text, its tags and entities taken out, as a reader sees it.
const text = (await response.text())
  .replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/g, '')
  .replace(/<[^>]+>/g, '')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&')

const signatures = {}
for (const name of pro) {
  // The heading `@name() #Pro`, then the signature, whose brackets may hold one level more: () => any.
  const found = new RegExp(`@${name}\\(\\) #Pro(@${name}\\((?:[^()]|\\([^()]*\\))*\\))`).exec(text)
  if (!found) throw new Error(`${PAGE} gives no signature of @${name}`)
  signatures[name] = found[1]
}

const fixture = {
  $comment:
    "The signatures of the Datastar Pro actions as https://data-star.dev/reference/actions writes them, written by conformance/generate/pro.mjs from the page as it stood when fetched. Pro's code is never read; the page is public. ClientOracleTest holds the catalog's kinds and optional parameters of each Pro action to them.",
  url: PAGE,
  fetched: new Date().toISOString().slice(0, 10),
  signatures,
}
writeFileSync(resolve(conformance, 'reference', 'pro-actions.json'), `${JSON.stringify(fixture, null, 2)}\n`)
console.log(`pro-actions.json: ${Object.keys(signatures).length} signatures`)
