import type { ShikiConfig } from "astro"
import { defineConfig } from "astro/config"
import { rehypeTables } from "./src/plugins/rehype-tables.mjs"
import { remarkTabs } from "./src/plugins/remark-tabs.mjs"
import { datastarLangs, shikiThemes } from "./src/shiki"

export default defineConfig({
  site: "https://streamlord-docs.netlify.app",
  markdown: {
    remarkPlugins: [remarkTabs],
    rehypePlugins: [rehypeTables],
    shikiConfig: {
      themes: shikiThemes,
      langs: datastarLangs as ShikiConfig["langs"],
      wrap: false,
    },
  },
})
