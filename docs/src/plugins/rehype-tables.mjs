/**
 * Gives every markdown table Fristil's table, inside Fristil's scroll wrapper.
 *
 * Markdown writes a bare `<table>`, and Fristil styles `.fs-table` — so without this
 * the documentation's tables render with no padding, no header weight and no row
 * rules. This adds the class rather than restyling `table` in our own CSS, so the
 * tables look like the design system's tables and follow it when it changes.
 *
 * The wrapper is the `.fs-table-scroll` pattern Fristil documents for consumers: a
 * table of three columns with code in the cells is wider than a phone, and dragging
 * it sideways reads better than breaking `streamlord-json-jackson2` across four
 * lines. `tabindex` so the keyboard can reach the columns past the edge, and
 * `role="region"` with a label from the heading above it, since a markdown table has
 * no `<caption>` to name it.
 */
export function rehypeTables() {
  return (tree) => walk(tree, { heading: "" })
}

function walk(node, state) {
  if (!node.children) return

  node.children = node.children.map((child) => {
    if (child.type === "element" && /^h[1-6]$/.test(child.tagName)) {
      state.heading = textOf(child)
    }

    walk(child, state)

    if (child.type !== "element" || child.tagName !== "table") return child

    child.properties = { ...child.properties, className: ["fs-table"] }

    return {
      type: "element",
      tagName: "div",
      properties: {
        className: ["fs-table-scroll"],
        tabindex: "0",
        role: "region",
        "aria-label": state.heading ? `Table: ${state.heading}` : "Table",
      },
      children: [child],
    }
  })
}

function textOf(node) {
  if (node.type === "text") return node.value
  return (node.children ?? []).map(textOf).join("")
}
