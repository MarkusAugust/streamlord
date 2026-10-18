/**
 * Lets inline code break where a reader would: after a `.`, `::` or `/` between two names,
 * and after an opening `(`. Each gets a `<wbr>`, so `kotlinx.coroutines.DefaultExecutor`
 * wraps as `kotlinx.coroutines.` and `DefaultExecutor` on a phone instead of losing its last
 * letter to the next line, which is what `overflow-wrap` in CodeBlock.astro does when it is
 * the only way out.
 *
 * A dot before a digit stays put, so `1.11.0` never breaks, and so does a leading one, as in
 * `.prose`. Code inside a `<pre>` is a block with its own wrapping. A Maven coordinate, as in
 * the install page's dependency trees, breaks between its parts, and its artifact goes in an
 * `.unbroken` span so that `kotlinx-serialization-core` keeps its hyphens unless the line is
 * too narrow for the whole name.
 *
 * Raw HTML in a page, such as a diagram, reaches this as a string, not as elements, so a
 * `<code>` written by hand places its own `<wbr>`.
 */
export function rehypeCodeBreaks() {
  return (tree) => walk(tree, false)
}

const BREAK = /(?<=[\w)\]>])(?:\.|::|\/)(?=[A-Za-z_$@])|\((?=[^)])/g
const COORDINATE = /^([\w-]+(?:\.[\w-]+)+):([\w-]+)( [\w.-]+)?$/

const wbr = () => ({
  type: "element",
  tagName: "wbr",
  properties: {},
  children: [],
})
const text = (value) => ({ type: "text", value })

function walk(node, inPre) {
  if (!node.children) return
  for (const child of node.children) {
    if (child.type !== "element") continue
    const pre = inPre || child.tagName === "pre"
    if (child.tagName === "code" && !pre) {
      breakText(child)
    } else {
      walk(child, pre)
    }
  }
}

function breakText(node) {
  const only = node.children.length === 1 ? node.children[0] : null
  const coordinate = only?.type === "text" && only.value.match(COORDINATE)
  if (coordinate) {
    const [, group, artifact, version] = coordinate
    node.children = [
      ...group
        .split(".")
        .flatMap((part, i, all) => [
          text(i < all.length - 1 ? `${part}.` : `${part}:`),
          wbr(),
        ]),
      {
        type: "element",
        tagName: "span",
        properties: { className: ["unbroken"] },
        children: [text(artifact)],
      },
      ...(version ? [text(version)] : []),
    ]
    return
  }
  node.children = node.children.flatMap((child) => {
    if (child.type === "element") {
      breakText(child)
      return [child]
    }
    if (child.type !== "text") return [child]
    const parts = []
    let last = 0
    for (const match of child.value.matchAll(BREAK)) {
      const end = match.index + match[0].length
      parts.push(text(child.value.slice(last, end)), wbr())
      last = end
    }
    if (parts.length === 0) return [child]
    parts.push(text(child.value.slice(last)))
    return parts
  })
}
