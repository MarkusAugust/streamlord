// Fills in conformance/dsl/*.json: what the free Datastar client does with the HTML Streamlord's
// DSL writes. Run by hand after `updateClientReadings`: `npm run dsl`. The Kotlin tests write each
// case's name, call, html and probe; this script writes its `read` and nothing else.
//
// Each case runs in a fresh happy-dom window holding the case's elements before the client starts.
// The elements are built from parse5's tree, because happy-dom's own parser leaves most character
// references in attribute values undecoded. Timers run on a clock this script turns, so a wait is
// read as the number the client passed, and the listeners the client adds are recorded on the
// prototype that owns addEventListener, since happy-dom's EventTarget.prototype is not that one.
//
// A Pro attribute has no plugin in the free client. For each Pro name a recording plugin is
// registered through the bundle's exported `attribute`, so what is read is only how the free engine
// splits the attribute into its key and modifiers. Nothing from Datastar Pro is read here.
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import { transformSync } from 'esbuild'
import { Window } from 'happy-dom'
import * as parse5 from 'parse5'

const here = dirname(fileURLToPath(import.meta.url))
const fixtures = resolve(here, '..', 'dsl')
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

// As in dom.mjs: the module bundle as a strict IIFE whose exports land on `Datastar`.
const bundles = {}
const bundle = (file) => {
  if (!bundles[file]) {
    const source = readFreeClient(`bundles/${file}`)
    bundles[file] = {
      code: `"use strict";\n${transformSync(source, { format: 'iife', globalName: 'Datastar' }).code}`,
      sha256: createHash('sha256').update(source).digest('hex'),
    }
  }
  return bundles[file]
}
const bundleFor = (prefix) => (prefix === 'data-star-' ? 'datastar-aliased.js' : 'datastar.js')

// Runs inside the window before the client: the clock, the listener, observer and fetch records,
// and the probe an expression calls to show that it ran.
const harness = `
  globalThis.MathMLElement ??= class MathMLElement {}
  const labels = new Map()
  // happy-dom 18.0.1 hands out a form as a Proxy from its constructor, while closest() returns the
  // object behind it, so a node not found by identity is matched by its parent and its markup.
  const twin = (target) => {
    if (!target || target.nodeType !== 1) return undefined
    const found = [...labels].filter(([e]) => e.parentNode === target.parentNode && e.outerHTML === target.outerHTML)
    return found.length === 1 ? found[0][1] : undefined
  }
  const label = (target) =>
    target === window || target === globalThis || (target?.document === document && 'location' in target)
      ? 'window'
      : target === document
        ? 'document'
        : labels.get(target) ?? twin(target) ?? target?.constructor?.name ?? String(target)
  const record = { listeners: [], timers: [], observers: [], requests: [], probes: [], errors: [], viewTransitions: 0 }
  let recording = false
  const pending = []
  let nextTimer = 1
  const schedule = (kind) => (fn, ms, ...args) => {
    const id = nextTimer++
    // happy-dom times its own work, such as reading a response body, with the window's timers:
    // those run on the clock too, but only the client's are recorded.
    const caller = new Error().stack.split('\\n')[2] ?? ''
    if (recording && !caller.includes('node_modules/happy-dom')) record.timers.push([kind, ms ?? null])
    pending.push({ id, kind, ms: ms ?? 0, fn: () => fn(...args) })
    return id
  }
  window.setTimeout = schedule('setTimeout')
  window.setInterval = schedule('setInterval')
  window.clearTimeout = window.clearInterval = (id) => {
    const i = pending.findIndex((t) => t.id === id)
    if (i >= 0) pending.splice(i, 1)
  }
  window.requestAnimationFrame = schedule('requestAnimationFrame')
  window.cancelAnimationFrame = window.clearTimeout
  const onListener = (target, type, options) => {
    if (!recording) return
    const shown = typeof options === 'object' && options !== null ? { ...options } : options === undefined ? null : options
    record.listeners.push([label(target), type, shown])
  }
  window.IntersectionObserver = class {
    constructor(callback, options) {
      this.callback = callback
      record.observers.push({ options: options ?? null, targets: [] })
      this.index = record.observers.length - 1
      observers.push(this)
    }
    observe(target) {
      record.observers[this.index].targets.push(label(target))
      this.target = target
    }
    unobserve() {}
    disconnect() {}
  }
  const observers = []
  document.startViewTransition = (update) => {
    record.viewTransitions++
    update()
  }
  window.probe = (...args) => {
    record.probes.push(args.map((a) => (a instanceof Event ? a.type : a)))
  }
  let respond = { status: 204 }
  window.fetch = async (url, init = {}) => {
    const headers = init.headers instanceof Headers ? Object.fromEntries(init.headers) : { ...(init.headers ?? {}) }
    const body =
      init.body == null ? null : init.body instanceof FormData ? [...init.body] : typeof init.body === 'string' ? init.body : String(init.body)
    record.requests.push({ url: String(url), method: init.method ?? 'GET', headers, body })
    return new Response(respond.body ?? '', { status: respond.status, headers: respond.headers ?? {} })
  }
  globalThis.harness = {
    record,
    labels,
    observers,
    onListener,
    respondWith: (answer) => (respond = answer),
    pending: () => pending.length,
    start: () => (recording = true),
    stop: () => (recording = false),
    // Runs every timer due, earliest wait first, until none is left or a bound is reached.
    turn: (limit = 50) => {
      let runs = 0
      while (pending.length && runs++ < limit) {
        pending.sort((a, b) => a.ms - b.ms || a.id - b.id)
        const timer = pending.shift()
        try {
          timer.fn()
        } catch (e) {
          record.errors.push(String(e?.message ?? e).split('\\n')[0])
        }
      }
    },
    store: () =>
      JSON.stringify(Datastar.root, (key, value) =>
        value && typeof value === 'object' && 'nodeType' in value ? '<' + label(value) + '>' : value),
  }
`

// The case's elements as parse5 reads its HTML, put into the document attribute by attribute.
const build = (document, node, labels) => {
  if (node.nodeName === '#text') return document.createTextNode(node.value)
  if (node.nodeName === '#comment') return document.createComment(node.data)
  const element = document.createElementNS(node.namespaceURI, node.tagName)
  for (const { name, value } of node.attrs) element.setAttribute(name, value)
  for (const child of node.content?.childNodes ?? node.childNodes) element.appendChild(build(document, child, labels))
  return element
}

const elementsOf = (root) => {
  const out = []
  const walk = (node) => {
    if (node.nodeType === 1) out.push(node)
    for (const child of node.childNodes) walk(child)
  }
  for (const child of root.childNodes) walk(child)
  return out
}

const attributesOf = (element) => [...element.attributes].map((a) => [a.name, a.value])

// happy-dom's classes are shared by every window, so addEventListener is wrapped once, on the
// prototypes that own it, and reports to the case running now. The script's own listeners are
// added while no case listens.
let listening = null
let adding = false
// A promise the client lets fail, such as an action's after its last retry, is the case's error.
let rejected = null
process.on('unhandledRejection', (reason) => {
  if (!rejected) throw reason
  rejected.push(String(reason?.message ?? reason).split('\n')[0])
})
const wrapped = new WeakSet()
const wrapListeners = (window) => {
  for (const object of [window.document.createElement('div'), window.document, window]) {
    let proto = object
    while (proto && !Object.prototype.hasOwnProperty.call(proto, 'addEventListener')) proto = Object.getPrototypeOf(proto)
    if (!proto || wrapped.has(proto)) continue
    wrapped.add(proto)
    const add = proto.addEventListener
    proto.addEventListener = function (type, listener, options) {
      // A prototype lower in the chain may call this one's method on: count the outer call only.
      if (adding) return add.call(this, type, listener, options)
      adding = true
      try {
        listening?.(this, type, options)
        return add.call(this, type, listener, options)
      } finally {
        adding = false
      }
    }
  }
}

const parsedAttributes = (fragment) => {
  const out = []
  const walk = (node) => {
    if (node.attrs) out.push(node.attrs.map(({ name, value }) => [name, value]))
    for (const child of node.content?.childNodes ?? node.childNodes ?? []) walk(child)
  }
  for (const child of fragment.childNodes) walk(child)
  return out
}

const settle = async () => {
  for (let i = 0; i < 5; i++) await new Promise((r) => setImmediate(r))
}

// Turns the clock and lets promises run until nothing is left to do, or a bound is reached.
const drain = async (h) => {
  for (let i = 0; i < 40; i++) {
    h.turn()
    await settle()
    if (!h.pending()) return
  }
}

const eventFor = (window, type) => {
  const init = { bubbles: true, cancelable: true }
  if (type.startsWith('key')) return new window.KeyboardEvent(type, init)
  if (['click', 'mousedown', 'mouseup'].includes(type)) return new window.MouseEvent(type, init)
  return new window.Event(type, init)
}

// An SSE body holding one datastar-patch-elements event that morphs [html] in, as the server sends it.
const patchEvent = (html) => `event: datastar-patch-elements\n${html.split('\n').map((line) => `data: elements ${line}`).join('\n')}\n\n`

const runCase = async (c, pro) => {
  const fragmentHtml = c.html.startsWith('<html') ? null : c.html
  const window = new Window({ url: 'http://localhost/' })
  const { document } = window
  wrapListeners(window)
  listening = null
  rejected = []
  const uncaught = []
  window.addEventListener('error', (event) => uncaught.push(String(event.error?.message ?? event.message).split('\n')[0]))
  vm.runInContext(harness, window)
  const { harness: h } = window

  let elements
  let parsed
  if (fragmentHtml === null) {
    const tree = parse5.parse(c.html)
    const html = tree.childNodes.find((n) => n.nodeName === 'html')
    for (const { name, value } of html.attrs) document.documentElement.setAttribute(name, value)
    const body = html.childNodes.find((n) => n.nodeName === 'body')
    for (const child of body.childNodes) document.body.appendChild(build(document, child))
    elements = [document.documentElement, ...elementsOf(document.body)]
    parsed = [html.attrs.map(({ name, value }) => [name, value]), ...parsedAttributes(body)]
  } else {
    const fragment = parse5.parseFragment(fragmentHtml)
    for (const child of fragment.childNodes) document.body.appendChild(build(document, child))
    elements = elementsOf(document.body)
    parsed = parsedAttributes(fragment)
  }
  const counts = new Map()
  for (const element of elements) {
    const tag = element.localName
    const n = counts.get(tag) ?? 0
    counts.set(tag, n + 1)
    h.labels.set(element, element.id || (n === 0 ? tag : `${tag}${n + 1}`))
  }
  listening = (target, type, options) => h.onListener(target, type, options)
  const client = bundle(bundleFor(c.prefix ?? 'data-'))
  try {
    vm.runInContext(client.code, window)
    h.start()
    if (pro.attributes.length) {
      vm.runInContext(
        `for (const name of ${JSON.stringify(pro.attributes)}) Datastar.attribute({ name, apply({ key, mods, value }) {
          probe('pro', name, key ?? null, Object.fromEntries([...mods].map(([k, v]) => [k, [...v]])), value)
        } })`,
        window,
      )
    }
    if (pro.actions.length) {
      vm.runInContext(
        `for (const name of ${JSON.stringify(pro.actions)}) Datastar.action({ name, apply(ctx, ...args) {
          probe('pro-action', name, ...args.map((a) => (typeof a === 'function' ? 'function' : a instanceof RegExp ? String(a) : a)))
        } })`,
        window,
      )
    }
  } catch (e) {
    h.record.errors.push(String(e?.message ?? e).split('\n')[0])
  }
  if (c.probe?.respond) h.respondWith(c.probe.respond)
  await drain(h)
  const storeBefore = h.store()
  if (c.probe?.eval) {
    try {
      vm.runInContext(c.probe.eval, window)
    } catch (e) {
      h.record.errors.push(String(e?.message ?? e).split('\n')[0])
    }
    await drain(h)
  }
  if (c.probe?.patch !== undefined) {
    h.respondWith({ status: 200, headers: { 'Content-Type': 'text/event-stream' }, body: patchEvent(c.probe.patch) })
    // The request the patch comes by is the harness's, not the case's, so nothing is recorded meanwhile.
    h.stop()
    const trigger = document.createElement('div')
    trigger.setAttribute(`${c.prefix ?? 'data-'}init`, "@get('/patch')")
    document.head.append(trigger)
    await drain(h)
    h.record.requests.pop()
    trigger.remove()
    h.start()
    elements = elementsOf(document.body)
    for (const element of elements) if (!h.labels.has(element)) h.labels.set(element, element.id || element.localName)
  }
  for (const [at, prop, value] of c.probe?.set ?? []) {
    const element = elements.find((e) => h.labels.get(e) === at)
    if (!element) throw new Error(`${c.name}: no element ${at} to set ${prop} on`)
    element[prop] = value
  }

  // The events a case sends, by default one per listener the client added: to the element it is
  // on, or to the document for a window or document listener, so a listener for outside hears one.
  const fired = []
  const listeners = [...h.record.listeners]
  const targets = new Set(['window', 'document', ...elements.map((e) => h.labels.get(e))])
  const dispatches =
    c.probe?.dispatch ??
    [...new Map(listeners.filter(([at, type]) => targets.has(at) && !type.startsWith('datastar-')).map(([at, type]) => [`${at} ${type}`, [at, type]])).values()]
  for (const [at, type] of dispatches) {
    const target = at === 'window' || at === 'document' ? document : elements.find((e) => h.labels.get(e) === at)
    if (!target) throw new Error(`${c.name}: no element ${at} to send ${type} to`)
    for (let round = 1; round <= (c.probe?.rounds ?? 2); round++) {
      const before = h.record.probes.length
      const reached = []
      const watch = (event) => reached.push(event.type)
      const was = listening
      listening = null
      document.documentElement.addEventListener(type, watch)
      listening = was
      // A burst sends the event again before any timer runs, as a quick second click does.
      let event
      for (let n = 0; n < (c.probe?.burst ?? 1); n++) {
        event = eventFor(window, type)
        try {
          target.dispatchEvent(event)
        } catch (e) {
          h.record.errors.push(String(e?.message ?? e).split('\n')[0])
        }
      }
      const sync = h.record.probes.length - before
      await drain(h)
      document.documentElement.removeEventListener(type, watch)
      fired.push({ at, type, round, sync, total: h.record.probes.length - before, defaultPrevented: event.defaultPrevented, bubbled: reached.length > 0 })
    }
  }
  for (const observer of h.observers) {
    for (const isIntersecting of [true, false]) {
      const before = h.record.probes.length
      observer.callback([{ isIntersecting, intersectionRatio: isIntersecting ? 1 : 0, target: observer.target }], observer)
      await drain(h)
      fired.push({ at: 'intersection', type: isIntersecting ? 'enter' : 'exit', total: h.record.probes.length - before })
    }
  }

  const read = {
    attributes: parsed,
    errors: [...h.record.errors, ...uncaught.filter((e) => !h.record.errors.includes(e)), ...rejected],
    listeners: h.record.listeners,
    timers: h.record.timers,
    observers: h.record.observers,
    requests: h.record.requests,
    probes: h.record.probes,
    fired,
    storeBefore,
    store: h.store(),
    after: elements.map((e) => [h.labels.get(e), attributesOf(e), e === document.documentElement ? null : e.textContent]),
    viewTransitions: h.record.viewTransitions,
  }
  listening = null
  await window.happyDOM.close()
  return read
}

// ASCII only, so a byte order mark or a line separator in a case shows in a diff.
const ascii = (text) => text.replace(/[^\x00-\x7f]/g, (ch) => `\\u${ch.charCodeAt(0).toString(16).padStart(4, '0')}`)

const files = process.argv.slice(2).length ? process.argv.slice(2) : ['free.json', 'pro.json']
for (const name of files) {
  const file = resolve(fixtures, name)
  const fixture = JSON.parse(readFileSync(file, 'utf8'))
  const pro = { attributes: fixture.proAttributes ?? [], actions: fixture.proActions ?? [] }
  const cases = []
  for (const c of fixture.cases) {
    const { read: _, ...input } = c
    cases.push({ ...input, read: await runCase(c, pro) })
  }
  const used = [...new Set(fixture.cases.map((c) => bundleFor(c.prefix ?? 'data-')))].sort()
  const header = {
    $comment: fixture.$comment,
    version,
    bundles: Object.fromEntries(used.map((b) => [b, bundle(b).sha256])),
  }
  if (fixture.proAttributes) header.proAttributes = fixture.proAttributes
  if (fixture.proActions) header.proActions = fixture.proActions
  const head = JSON.stringify(header, null, 2).slice(0, -2)
  writeFileSync(file, ascii(`${head},\n  "cases": [\n${cases.map((c) => `    ${JSON.stringify(c)}`).join(',\n')}\n  ]\n}\n`))
  console.log(`${name}: ${cases.length} cases`)
}
