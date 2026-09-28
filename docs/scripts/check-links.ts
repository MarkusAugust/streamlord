/**
 * Fails when a link in the built documentation points nowhere.
 *
 * Only internal links are checked. An external one can break without this repository
 * changing, and a build that goes red because someone else's server is down teaches
 * everyone to ignore it.
 *
 * Anchors are checked too, because a cross-reference to a section that has been renamed
 * is the more common mistake and the harder one to notice.
 */
import { readdirSync, readFileSync, statSync } from "node:fs"
import { join } from "node:path"

const DIST = "dist"

const htmlFiles = (dir: string): string[] =>
  readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry)
    if (statSync(path).isDirectory()) return htmlFiles(path)
    return path.endsWith(".html") ? [path] : []
  })

const pages = htmlFiles(DIST)
const ids = new Map<string, Set<string>>()

for (const file of pages) {
  const html = readFileSync(file, "utf8")
  const found = new Set<string>()
  for (const match of html.matchAll(/\sid="([^"]+)"/g)) found.add(match[1])
  ids.set(file, found)
}

const problems: string[] = []

for (const file of pages) {
  const html = readFileSync(file, "utf8")
  const from = file.replace(/^dist/, "").replace(/\/index\.html$/, "") || "/"

  for (const match of html.matchAll(/href="(\/[^"]*)"/g)) {
    const [path, anchor] = match[1].split("#")

    // Assets are files; pages are directories with an index.html.
    const target = path.includes(".")
      ? join(DIST, path)
      : join(DIST, path, "index.html")

    if (!pages.includes(target) && !exists(target)) {
      problems.push(`${from} -> ${match[1]} (no such page)`)
      continue
    }

    if (anchor && !ids.get(target)?.has(anchor)) {
      problems.push(`${from} -> ${match[1]} (page exists, anchor does not)`)
    }
  }
}

function exists(path: string): boolean {
  try {
    statSync(path)
    return true
  } catch {
    return false
  }
}

if (problems.length > 0) {
  console.error("Broken internal links:")
  for (const problem of [...new Set(problems)].sort())
    console.error(`  ${problem}`)
  process.exit(1)
}

console.log(`Checked ${pages.length} pages. Every internal link resolves.`)
