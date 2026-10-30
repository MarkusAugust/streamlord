/**
 * Turns a run of fenced code blocks sharing a `group=` into a LO-TEK tab set.
 *
 * ````
 * ```kotlin tab="Gradle" group=install sample=none
 * ```xml tab="Maven" group=install
 * ````
 *
 * Why remark and not rehype: Astro's Shiki replaces the whole `<pre>` element, so
 * properties put on the code node do not survive to the HTML. Measured, not assumed.
 * Emitting raw `html` nodes around the code nodes leaves the fences untouched, so
 * Shiki still highlights them and, more importantly, `:docs-samples` still reads
 * them exactly as it did before. The Gradle task must never have to learn what a tab is.
 *
 * `lt-tabs` is a wrapper component: give it a `.lt-tabs__list` of links, each to the id
 * of its `.lt-tabs__panel`, and it sets the roles, the coupling and the arrow keys
 * itself. Nothing here duplicates that. Without JavaScript the list is a table of
 * contents and the panels stand one after the other, so the code is still all there.
 */
export function remarkTabs() {
  return (tree) => {
    const children = []
    let index = 0
    // Panel ids are the page's, and a page can hold the same group more than once.
    let sets = 0

    while (index < tree.children.length) {
      const run = collectRun(tree.children, index)

      if (run.length < 2) {
        children.push(tree.children[index])
        index++
        continue
      }

      children.push(...wrap(run, ++sets))
      index += run.length
    }

    tree.children = children
  }
}

/** Consecutive code nodes carrying the same group. */
function collectRun(nodes, start) {
  const group = groupOf(nodes[start])
  if (!group) return []

  const run = []
  for (let i = start; i < nodes.length; i++) {
    if (groupOf(nodes[i]) !== group) break
    run.push(nodes[i])
  }
  return run
}

function groupOf(node) {
  if (node?.type !== "code" || !node.meta) return undefined
  return node.meta.match(/(?:^|\s)group=([\w-]+)/)?.[1]
}

function labelOf(node, fallback) {
  return node.meta?.match(/(?:^|\s)tab="([^"]+)"/)?.[1] ?? fallback
}

/** What the tab row is a choice between, for the screen reader. */
function titleOf(node, fallback) {
  return node.meta?.match(/(?:^|\s)label="([^"]+)"/)?.[1] ?? fallback
}

function wrap(run, set) {
  const labels = run.map((node, i) =>
    labelOf(node, node.lang ?? `Tab ${i + 1}`),
  )
  const group = groupOf(run[0])
  const title = titleOf(run[0], group)
  // Readable in the address, and numbered where two labels would give one id.
  const ids = []
  labels.forEach((label, i) => {
    const base = `tabs-${set}-${slug(label) || i + 1}`
    let id = base
    for (let n = 2; ids.includes(id); n++) id = `${base}-${n}`
    ids.push(id)
  })

  const links = labels
    .map(
      (label, i) =>
        `<li><a href="#${ids[i]}" data-tab-label="${escapeHtml(label)}">${escapeHtml(label)}</a></li>`,
    )
    .join("")

  const out = [
    html(`<lt-tabs class="tabs" data-tab-group="${escapeHtml(group)}">
<ul class="lt-tabs__list" aria-label="${escapeHtml(title)}">${links}</ul>`),
  ]

  run.forEach((node, i) => {
    out.push(
      html(`<div class="lt-tabs__panel" id="${ids[i]}">`),
      node,
      html(`</div>`),
    )
  })

  out.push(html(`</lt-tabs>`))
  return out
}

const slug = (value) =>
  String(value)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")

const html = (value) => ({ type: "html", value })

const escapeHtml = (value) =>
  String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/"/g, "&quot;")
