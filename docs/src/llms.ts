/**
 * The documentation for language models, after https://llmstxt.org: `llms.txt` is
 * the index, a page per line with what it is about, and `llms-full.txt` is every
 * page in one file. Both follow the navigation, so a page is listed here when it
 * is in the sidebar and in the order it is read.
 */
import { getCollection } from "astro:content"
import { href, NAVIGATION } from "./navigation"
import { DATASTAR_VERSION, DESCRIPTION, MAVEN_GROUP, TAGLINE } from "./project"

const SUMMARY = `${TAGLINE} ${DESCRIPTION}`

const ABOUT = `The SDK speaks the Datastar ${DATASTAR_VERSION} Server-Sent Events protocol and is published to Maven Central under \`${MAVEN_GROUP}\`. The editors, a VS Code extension and an IntelliJ plugin, read Datastar as a language wherever it is written and need no part of the SDK.`

type Page = { slug: string; title: string; description: string; body: string }

async function pages(): Promise<Map<string, Page>> {
  const entries = await getCollection("docs")
  return new Map(
    entries.map((e) => [
      e.id,
      {
        slug: e.id,
        title: e.data.title,
        description: e.data.description,
        body: e.body ?? "",
      },
    ]),
  )
}

const url = (site: URL, slug: string) => new URL(href(slug), site).href

/** The index: a section per part of the sidebar, a line per page. */
export async function llmsTxt(site: URL): Promise<string> {
  const all = await pages()
  const sections = NAVIGATION.map((section) => {
    const lines = section.pages.flatMap(({ slug }) => {
      const page = all.get(slug)
      return page
        ? [`- [${page.title}](${url(site, slug)}): ${page.description}`]
        : []
    })
    return `## ${section.title}\n\n${lines.join("\n")}`
  })
  const full = new URL("/llms-full.txt", site).href
  return `# Streamlord\n\n> ${SUMMARY}\n\n${ABOUT} Every page in one file: ${full}\n\n${sections.join("\n\n")}\n`
}

/** Every page in reading order, as the Markdown it is written in. */
export async function llmsFullTxt(site: URL): Promise<string> {
  const all = await pages()
  const bodies = NAVIGATION.flatMap((section) => section.pages).flatMap(
    ({ slug }) => {
      const page = all.get(slug)
      if (!page) return []
      return [
        `# ${page.title}\n\nSource: ${url(site, slug)}\n\n> ${page.description}\n\n${plain(page.body, site).trim()}`,
      ]
    },
  )
  return `# Streamlord\n\n> ${SUMMARY}\n\n${ABOUT}\n\n${bodies.join("\n\n---\n\n")}\n`
}

/**
 * The Markdown as a reader outside the site needs it: a fence keeps its language
 * and loses the attributes only this site's build reads (`sample=`, `tab=`), and
 * a link within the site becomes a full address.
 */
function plain(body: string, site: URL): string {
  return body
    .replace(
      /^(\s*(?:`{3,}|~{3,}))(\S*)[^\n]*$/gm,
      (_, fence: string, first: string) =>
        first.includes("=") ? fence : fence + first,
    )
    .replace(
      /\]\((\/[^)\s]*)\)/g,
      (_, path: string) => `](${new URL(path, site).href})`,
    )
}
