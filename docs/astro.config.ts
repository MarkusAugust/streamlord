import type { ShikiConfig } from "astro"
import { defineConfig } from "astro/config"
import { rehypeTables } from "./src/plugins/rehype-tables.mjs"
import { remarkTabs } from "./src/plugins/remark-tabs.mjs"
import { datastarLangs, hangingIndent, shikiThemes } from "./src/shiki"

export default defineConfig({
  site: "https://streamlord-docs.netlify.app",
  markdown: {
    remarkPlugins: [remarkTabs],
    rehypePlugins: [rehypeTables],
    shikiConfig: {
      themes: shikiThemes,
      // Lines carry the depth they were written at, so a phone can wrap them
      // without flattening the code. See hangingIndent and CodeBlock.astro.
      transformers: [hangingIndent],
      langs: datastarLangs as ShikiConfig["langs"],
      wrap: false,
    },
  },
})
