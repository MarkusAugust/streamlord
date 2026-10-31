// Regenerates the fixtures under conformance/dom by running the free Datastar client in
// happy-dom. Run by hand when the reference changes: `npm run dom`. The case inputs are read from
// the fixtures themselves (each case's `sse`, each scenario's `store` and `steps`, and the script
// bodies listed at the end of this file); everything else in them is written by this script.
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import vm from 'node:vm'
import { Window } from 'happy-dom'
import * as parse5 from 'parse5'
import { ascii, conformance, freeBundle } from './common.mjs'

const fixtures = resolve(conformance, 'dom')

const client = freeBundle('datastar.js').code

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

// Whether a patch of elements without a selector holds an element, as the HTML tokenizer reads its
// comments inside the client's wrapper. happy-dom runs a comment opened by `<!-->` or `<!--->` on
// to the next `-->`, where the standard ends it at once, so these come from parse5.
const holdsElement = (input) => {
  const wrapped = parse5.parse(`<body><template>${input}</template></body>`)
  const nodes = find(wrapped, 'template').content.childNodes
  return { input, nodes: nodes.map((node) => node.nodeName), element: nodes.some((node) => !node.nodeName.startsWith('#')) }
}

const commentBodies = [
  '<!--><b>y</b>',
  '<!---><b>y</b>',
  '<!-- x --!><b>y</b>',
  '<!-- x --><b>y</b>',
  '<!----><b>y</b>',
  '<!-- x ---><b>y</b>',
  '<!-- x -- y --><b>y</b>',
  '<!-- x --!> y',
  '<!-- <b>y</b> -->',
  '<!-- <b>y</b>',
  '<!-->',
  '<!--',
  '<!--><!-- <b>y</b>',
  '<!-- x --!><!-- <b>y</b> -->',
  'a <!-- <b> --> c',
  'a < b',
]

const fixture = (name) => resolve(fixtures, name)
const read = (name) => JSON.parse(readFileSync(fixture(name), 'utf8'))
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
write('comment-ends.json', onePerLine(commentBodies.map(holdsElement)))
