/**
 * Turns a run of fenced code blocks sharing a `group=` into a Fristil tab set.
 *
 * ````
 * ```kotlin tab="Gradle" group=install sample=none
 * ```xml tab="Maven" group=install
 * ````
 *
 * Why remark and not rehype: Astro's Shiki replaces the whole `<pre>` element, so
 * properties put on the code node do not survive to the HTML. Measured, not assumed.
 * Emitting raw `html` nodes around the code nodes leaves the fences untouched, so
 * Shiki still highlights them and — more importantly — `:docs-samples` still reads
 * them exactly as it did before. The Gradle task must never have to learn what a tab is.
 *
 * `fs-tabs` is a wrapper component: give it a `.fs-tabs__list` of buttons and one
 * `.fs-tabs__panel` per button, and it sets the roles, the ids, the coupling and the
 * arrow keys itself. Nothing here duplicates that.
 */
export function remarkTabs() {
  return (tree) => {
    const children = []
    let index = 0

    while (index < tree.children.length) {
      const run = collectRun(tree.children, index)

      if (run.length < 2) {
        children.push(tree.children[index])
        index++
        continue
      }

      children.push(...wrap(run))
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

function wrap(run) {
  const labels = run.map((node, i) =>
    labelOf(node, node.lang ?? `Tab ${i + 1}`),
  )
  const group = groupOf(run[0])
  const title = titleOf(run[0], group)

  const buttons = labels
    .map(
      (label) =>
        `<button type="button" data-tab-label="${escapeHtml(label)}">${escapeHtml(label)}</button>`,
    )
    .join("")

  const out = [
    html(`<fs-tabs class="tabs" data-tab-group="${escapeHtml(group)}">
<div class="fs-tabs__list" aria-label="${escapeHtml(title)}">${buttons}</div>`),
  ]

  run.forEach((node) => {
    out.push(html(`<div class="fs-tabs__panel">`), node, html(`</div>`))
  })

  out.push(html(`</fs-tabs>`))
  return out
}

const html = (value) => ({ type: "html", value })

const escapeHtml = (value) =>
  String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/"/g, "&quot;")
