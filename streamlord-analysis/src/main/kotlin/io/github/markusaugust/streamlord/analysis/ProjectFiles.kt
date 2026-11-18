package io.github.markusaugust.streamlord.analysis

/** Which files of a project the analysis and both editors read, and which folders they leave out. */
public object ProjectFiles {
    /** The folders left unread below a project's root, with every hidden one: build output and dependencies. */
    public val SKIPPED_FOLDERS: Set<String> = setOf("build", "node_modules", ".gradle", "dist", "out", "target")

    /**
     * How many markup files and how many Kotlin files an editor lists to read a project, each counted apart
     * so that the pages are not crowded out by sources, and the size above which it leaves a listed file unread.
     * A build reads every file.
     */
    public const val FILE_CAP: Int = 5000
    public const val SIZE_CAP: Int = 1_000_000

    /** HTML's own extensions. */
    public val HTML_EXTENSIONS: Set<String> = setOf("html", "htm", "xhtml")

    /** The template files read as markup by default, whatever language an editor gives them, lowercased and without dots. */
    public val TEMPLATE_EXTENSIONS: List<String> =
        (
            "jte kte ftl ftlh vm mustache hbs handlebars peb pebble twig jinja jinja2 j2 cshtml razor php erb ejs liquid njk edge " +
                "astro svelte vue tmpl gotmpl gohtml"
        ).split(' ')

    /**
     * How the file at [path] is read, a path from the project's root with `/` between its folders: Kotlin or markup
     * by its extension, and null for any other file and for one in a folder left unread.
     */
    public fun languageOf(path: String): SourceLanguage? {
        if (inSkippedFolder(path)) return null
        return when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "kt" -> SourceLanguage.KOTLIN
            in HTML_EXTENSIONS, in TEMPLATE_EXTENSIONS -> SourceLanguage.HTML
            else -> null
        }
    }

    /**
     * Whether the file at [path], a path from the project's root, lies in a folder left unread: one of
     * [SKIPPED_FOLDERS] or a hidden one, anywhere on the way down. A folder above the root is not asked about.
     */
    public fun inSkippedFolder(path: String): Boolean = path.split('/').dropLast(1).any(::skips)

    /** Whether a folder named [folder] is left unread, with all it holds. */
    private fun skips(folder: String): Boolean = folder in SKIPPED_FOLDERS || folder.startsWith(".")

    /** The template engine that writes into the file at [path], where its extension names one whose rules are its own. */
    public fun engineOf(path: String): TemplateEngine? =
        when (path.substringAfterLast('.', "").lowercase()) {
            "jte", "kte" -> TemplateEngine.JTE
            "ftl", "ftlh", "ftlx" -> TemplateEngine.FREEMARKER
            "vm", "vtl" -> TemplateEngine.VELOCITY
            "cshtml", "razor" -> TemplateEngine.RAZOR
            else -> null
        }

    /** The path of every file of [tree] that is read, in path order. */
    internal fun under(tree: ProjectTree): List<String> {
        val out = ArrayList<String>()

        fun walk(folder: String) {
            for (name in tree.list(folder)) {
                val path = if (folder.isEmpty()) name.removeSuffix("/") else folder + "/" + name.removeSuffix("/")
                when {
                    !name.endsWith("/") -> if (languageOf(path) != null) out += path
                    !skips(name.removeSuffix("/")) -> walk(path)
                }
            }
        }
        walk("")
        return out.sorted()
    }
}
