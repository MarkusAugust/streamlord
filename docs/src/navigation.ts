/**
 * The order of the documentation, in one place.
 *
 * The sidebar, the previous/next pager and the search index all read this, so
 * adding a page means adding one line here and one file under
 * `src/content/docs/`. A page that is not listed is not published.
 */
export type Section = {
  title: string
  pages: { slug: string; title: string }[]
}

export const NAVIGATION: Section[] = [
  {
    title: "Start here",
    pages: [
      { slug: "introduction", title: "Introduction" },
      { slug: "install", title: "Install and modules" },
      { slug: "protocol", title: "The protocol" },
      { slug: "first-stream", title: "Your first stream" },
    ],
  },
  {
    title: "The adapters",
    pages: [
      { slug: "ktor", title: "Ktor" },
      { slug: "spring-webmvc", title: "Spring WebMVC" },
      { slug: "spring-webflux", title: "Spring WebFlux" },
      { slug: "live-views", title: "Live views" },
      { slug: "signals", title: "Signals and codecs" },
    ],
  },
  {
    title: "Markup",
    pages: [
      { slug: "html-dsl", title: "The kotlinx.html DSL" },
      { slug: "strings", title: "Strings" },
      { slug: "templates", title: "Templates" },
      { slug: "casing", title: "Casing" },
      { slug: "choosing-a-style", title: "Choosing a style" },
      { slug: "components", title: "Components and plain JavaScript" },
    ],
  },
  {
    title: "The tooling",
    pages: [
      { slug: "dollar-trap", title: "The $ trap" },
      { slug: "testing", title: "Testing" },
      { slug: "editors", title: "The editors" },
    ],
  },
  {
    title: "Running it",
    pages: [
      { slug: "operations", title: "Operations" },
      { slug: "security", title: "Security" },
    ],
  },
  {
    title: "Beyond",
    pages: [
      { slug: "native-image", title: "Native image" },
      { slug: "datastar-pro", title: "Datastar Pro" },
      { slug: "java", title: "Java" },
      { slug: "architecture", title: "Architecture" },
      { slug: "changelog", title: "Changelog" },
      { slug: "roadmap", title: "Roadmap" },
      { slug: "licence", title: "Licence and cost" },
    ],
  },
]

/** Every page in reading order, which is what the pager walks. */
/** The first page of the reading order. The landing page points at it. */
export const FIRST_PAGE = "introduction"

/** Where a page lives. Everything that writes a link asks this, so it is decided once. */
export function href(slug: string): string {
  return `/${slug}/`
}

export const READING_ORDER = NAVIGATION.flatMap((section) => section.pages)

export function neighbours(slug: string) {
  const index = READING_ORDER.findIndex((page) => page.slug === slug)
  return {
    previous: index > 0 ? READING_ORDER[index - 1] : undefined,
    next:
      index >= 0 && index < READING_ORDER.length - 1
        ? READING_ORDER[index + 1]
        : undefined,
  }
}
