package io.github.markusaugust.streamlord.analysis

/** Which files of a project the analysis and both editors read, and which folders they leave out. */
public object ProjectFiles {
    /** The folders left unread below a project's root, with every hidden one: build output and dependencies. */
    public val SKIPPED_FOLDERS: Set<String> = setOf("build", "node_modules", ".gradle", "dist", "out", "target")

    /** HTML's own extensions. */
    public val HTML_EXTENSIONS: Set<String> = setOf("html", "htm", "xhtml")

    /** The template files read as markup by default, whatever language an editor gives them, lowercased and without dots. */
    public val TEMPLATE_EXTENSIONS: List<String> =
        listOf(
            "jte",
            "kte",
            "ftl",
            "ftlh",
            "vm",
            "mustache",
            "hbs",
            "handlebars",
            "peb",
            "pebble",
            "twig",
            "jinja",
            "jinja2",
            "j2",
            "cshtml",
            "razor",
            "php",
            "erb",
            "ejs",
            "liquid",
            "njk",
            "edge",
            "astro",
            "svelte",
            "vue",
            "tmpl",
            "gotmpl",
            "gohtml",
        )
}
