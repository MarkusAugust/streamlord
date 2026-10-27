// Regenerates the fixtures under conformance/dom by running the free Datastar client in
// happy-dom. Run by hand when the reference changes: `npm run dom`. The case inputs are read from
// the fixtures themselves (each case's `sse`, each scenario's `store` and `steps`, and the script
// bodies listed at the end of this file); everything else in them is written by this script.
import { readdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import { transformSync } from 'esbuild'
import { Window } from 'happy-dom'
import * as parse5 from 'parse5'

const here = dirname(fileURLToPath(import.meta.url))
const fixtures = resolve(here, '..', 'dom')
const referenceRoot = resolve(here, '..', '..', 'datastar-reference')
const versions = readdirSync(referenceRoot).filter((d) => d.startsWith('datastar-'))
if (versions.length !== 1) throw new Error(`expected one datastar-<version> directory under ${referenceRoot}, found ${versions.length}`)
const version = versions[0].slice('datastar-'.length)
const reference = resolve(referenceRoot, versions[0])

// The only thing read is the free client's release tree, never a path outside it.
const readFreeClient = (relative) => {
  const root = realpathSync(reference) + sep
  const file = realpathSync(resolve(reference, relative))
  if (!file.startsWith(root)) throw new Error(`${relative} is outside the free client`)
  return readFileSync(file, 'utf8')
}

// The built free bundle is an ES module; as an IIFE it can run inside a happy-dom window, where
// its exports land on `Datastar`. A module is strict, so the IIFE has to be told: in sloppy mode
// the assignments that make the client throw on a __proto__ key would silently do nothing.
const client = `"use strict";\n${transformSync(readFreeClient('bundles/datastar.js'), { format: 'iife', globalName: 'Datastar' }).code}`

// Runs inside the window before the client. A happy-dom window is its own realm, so a patch has to
// be parsed in there for the client's isPojo to see a plain object. happy-dom has no MathML and no
// view transitions; the client only asks instanceof of the one and counts on the other to call back.
const harness = `
  globalThis.MathMLElement ??= class MathMLElement {}
  let viewTransitions = 0
  document.startViewTransition = (update) => {
    viewTransitions++
    update()
  }
  globalThis.harness = {
    viewTransitions: () => viewTransitions,
    patch: (signals, ifMissing) => Datastar.mergePatch(JSON.parse(signals), { ifMissing }),
    store: () => JSON.stringify(Datastar.root),
  }
`

const openPage = (body = '') => {
  const window = new Window({ url: 'http://localhost/' })
  const errors = []
  window.addEventListener('error', (event) => errors.push(event.error ?? event.message))
  window.document.body.innerHTML = body
  vm.runInContext(harness, window)
  vm.runInContext(client, window)
  return { window, harness: window.harness, errors, close: () => window.happyDOM.close() }
}

// JSON.stringify of the client's root after the patch, or THROWS and the root where it threw.
const fold = ({ harness }, signals, ifMissing) => {
  try {
    harness.patch(signals, ifMissing)
    return harness.store()
  } catch {
    return `THROWS ${harness.store()}`
  }
}

// One frame through the client's own fetch path: a page with the wrap and target elements and the
// signal keep, a fetch that answers with the frame as text/event-stream, and @get run by data-init.
const runFrame = async ({ name, sse }) => {
  const page = openPage('<div id="wrap"><div id="target">old</div></div>')
  const { window, harness, errors } = page
  const { document } = window
  await window.happyDOM.waitUntilComplete()
  harness.patch('{"keep":1}', false)

  window.fetch = async () =>
    new Response(new TextEncoder().encode(sse), { headers: { 'Content-Type': 'text/event-stream' } })
  const events = []
  const finished = new Promise((resolve) => {
    document.addEventListener('datastar-fetch', ({ detail }) => {
      if (detail.type.startsWith('datastar-')) events.push(detail.argsRaw)
      if (detail.type === 'finished') resolve()
    })
  })
  const trigger = document.createElement('div')
  trigger.setAttribute('data-init', "@get('/frame')")
  document.head.append(trigger)
  await finished

  if (errors.length) throw new Error(`${name}: the client threw: ${errors.map((e) => e.message ?? e).join('; ')}`)
  if (events.length !== 1) throw new Error(`${name}: ${events.length} Datastar events, the fixture holds one per case`)
  const result = {
    name,
    sse,
    argsRaw: events[0],
    viewTransitions: harness.viewTransitions(),
    body: document.body.innerHTML,
    signals: JSON.parse(harness.store()),
  }
  await page.close()
  return result
}

const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

const holdsProto = (value) =>
  value !== null &&
  typeof value === 'object' &&
  Object.keys(value).some((key) => key === '__proto__' || holdsProto(value[key]))

// The patch up to where the Kotlin fold refuses it: a key __proto__, or a key whose value holds one
// and would be assigned whole. The keys before it at every level stay, as the client had already
// folded them. Returns [cut patch, whether anything was cut].
const cutBeforeProto = (patch) => {
  const kept = {}
  for (const key in patch) {
    if (key === '__proto__') return [kept, true]
    const value = patch[key]
    if (isPlainObject(value)) {
      const [inner, cut] = cutBeforeProto(value)
      kept[key] = cut ? inner : value
      if (cut) return [kept, true]
    } else if (holdsProto(value)) {
      return [kept, true]
    } else {
      kept[key] = value
    }
  }
  return [kept, false]
}

// A fresh store given the scenario so far and then one patch as written: what the client shows
// for a patch the fold refuses, without that patch's prototype ending up in the store the
// scenario goes on from.
const foldApart = async (replay, signals, ifMissing) => {
  const page = openPage()
  for (const [text, missing] of replay) fold(page, text, missing)
  const result = fold(page, signals, ifMissing)
  await page.close()
  return result
}

const runScenario = async (scenario) => {
  const page = openPage()
  const replay = []
  const out = { name: scenario.name }
  if (scenario.store !== undefined) {
    out.store = scenario.store
    fold(page, scenario.store, false)
    replay.push([scenario.store, false])
  }
  out.steps = []
  for (const { signals, onlyIfMissing } of scenario.steps) {
    const step = { signals, onlyIfMissing }
    const [cut, refused] = cutBeforeProto(JSON.parse(signals))
    if (refused) {
      step.client = await foldApart(replay, signals, onlyIfMissing)
      step.refused = true
      const text = JSON.stringify(cut)
      step.kept = fold(page, text, onlyIfMissing)
      replay.push([text, onlyIfMissing])
    } else {
      step.client = fold(page, signals, onlyIfMissing)
      replay.push([signals, onlyIfMissing])
    }
    out.steps.push(step)
  }
  await page.close()
  return out
}

const runStoreCase = async ({ name, steps }) => {
  const page = openPage()
  for (const { signals, onlyIfMissing } of steps) fold(page, signals, onlyIfMissing)
  const client = page.harness.store()
  await page.close()
  return { name, steps, client }
}

// What the HTML tokenizer does with a script body, once inside the client's wrapper for a patch
// of elements and once as the whole document the client parses when the body looks like one.
const find = (node, name) => {
  if (node.nodeName === name) return node
  for (const child of node.content?.childNodes ?? node.childNodes ?? []) {
    const found = find(child, name)
    if (found) return found
  }
  return null
}
const textOf = (element) => element.childNodes.map((node) => node.value ?? '').join('')

const readScriptBody = (input) => {
  const wrapped = parse5.parse(`<body><template><script>${input}</script></template></body>`)
  const [first, ...rest] = find(wrapped, 'template').content.childNodes
  const script = first?.nodeName === 'script' ? textOf(first) : ''
  const template = { script, whole: script === input && rest.length === 0 }
  if (rest.length) template.after = rest.map((node) => parse5.serializeOuter(node)).join('')

  const whole = parse5.parse(`<script>${input}</script>`)
  const element = find(whole, 'script')
  const document = {
    script: element ? textOf(element) : '',
    in: element ? element.parentNode.nodeName : 'none',
    body: parse5.serialize(find(whole, 'body')),
  }

  // The client's three whole-document tests (patchElements.ts), on the text with its svg elements removed, as it applies them.
  const stripped = input.replace(/<svg(\s[^>]*>|>)([\s\S]*?)<\/svg>/gim, '')
  const matches = { body: /<\/body>/.test(stripped), head: /<\/head>/.test(stripped), html: /<\/html>/.test(stripped) }
  return { input, template, document, matches }
}

const scriptBodies = [
  "a = '</script>'",
  "a = '</SCRIPT>'",
  "a = '</ScRiPt>'",
  "a = '</scripts>'",
  "a = '</script\t>'",
  "a = '</script/'",
  "a = '</body>'",
  "a = '</BODY>'",
  "a = '</head>'",
  "a = '</html>'",
  "a = '<!--<script>'",
  "a = '<!--<SCRIPT>'",
  "a = '<!--<Script/>'",
  "a = '<!--<scripts>'",
  "a = '<script><!--'",
  "a = '<!--</script>'",
  // The forms Streamlord writes for the bodies above.
  "a = '<\\/script>'",
  "a = '<\\/SCRIPT>'",
  "a = '<\\/ScRiPt>'",
  "a = '<\\/scripts>'",
  "a = '<\\/script\t>'",
  "a = '<\\/script/'",
  "a = '<\\/body>'",
  "a = '<\\/head>'",
  "a = '<\\/html>'",
  "a = '<!--<\\x73cript>'",
  "a = '<!--<\\x53CRIPT>'",
  "a = '<!--<\\x53cript/>'",
  "a = '<!--<\\/script>'",
]

const fixture = (name) => resolve(fixtures, name)
const read = (name) => JSON.parse(readFileSync(fixture(name), 'utf8'))
// ASCII only, so a byte order mark or a line separator in a case shows in a diff.
const ascii = (text) => text.replace(/[^\x00-\x7f]/g, (ch) => `\\u${ch.charCodeAt(0).toString(16).padStart(4, '0')}`)
const onePerLine = (cases) => `[\n${cases.map((c) => ascii(JSON.stringify(c))).join(',\n')}\n]\n`
const write = (name, text) => {
  writeFileSync(fixture(name), text)
  console.log(`wrote ${name}`)
}

const decoder = []
for (const { name, sse } of read('decoder-cases.json')) decoder.push(await runFrame({ name, sse }))
write('decoder-cases.json', onePerLine(decoder))

const merge = []
for (const scenario of read('merge-differential.json')) merge.push(await runScenario(scenario))
write('merge-differential.json', onePerLine(merge))

const store = []
for (const { name, steps } of read('store-cases.json')) {
  store.push(await runStoreCase({ name, steps: steps.map(({ signals, onlyIfMissing }) => ({ signals, onlyIfMissing })) }))
}
write('store-cases.json', ascii(JSON.stringify(store, null, 1)))

write('script-escapes.json', onePerLine(scriptBodies.map(readScriptBody)))
