package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A class that is not `@Serializable` is Jackson's, and its signals go by the names Jackson writes:
 * the property's, or the one `@JsonProperty` gives, without the ones `@JsonIgnore` leaves out. Where
 * the project renames them, with `@JsonNaming`, a naming strategy in its code or in Spring's
 * settings, the names are the codec's alone, as for a class the analysis cannot read.
 */
class JacksonSignalNamesTest {
    private val files = LinkedHashMap<String, String>()

    private fun write(
        path: String,
        text: String,
    ) {
        files[path] = text
    }

    /** Each signal finding of the project, as `file: code 'the text it marks'`. */
    private fun told(): List<String> =
        Analyzer()
            .analyzeProject(MemoryTree(files))
            .filter { it.issue.code == IssueCode.UNKNOWN_SIGNAL || it.issue.code == IssueCode.SIGNAL_NEVER_DECLARED }
            .map { p ->
                val marked = files.getValue(p.path).substring(p.issue.start, p.issue.end)
                "${p.path.substringAfterLast('/')}: ${p.issue.code.id} '$marked'"
            }

    private val form =
        """
        package app

        data class SearchForm(
            @JsonProperty("q") val query: String = "",
            val pageNo: Int = 0,
            @JsonIgnore val secret: String = "",
            @get:JsonProperty("sort") val order: String = "",
        )
        """.trimIndent()

    private val page =
        """
        package app

        fun Route.search() {
            get("/") { call.respondHtml { body { div { dataSignals(call.streamlord.codec.toJsonValue(SearchForm())) } } } }
        }
        """.trimIndent()

    @Test
    fun `a page declared from a Jackson class declares the names Jackson writes`() {
        write("src/main/kotlin/app/SearchForm.kt", form)
        write("src/main/kotlin/app/Page.kt", page)
        val reads = listOf("q", "pageNo", "sort", "qurey", "query", "secret").joinToString(" + ") { "${'$'}$it" }
        write("src/main/resources/templates/list.html", """<p data-text="$reads"></p>""")
        assertEquals(
            listOf("list.html: unknown-signal '\$qurey'", "list.html: unknown-signal '\$query'", "list.html: unknown-signal '\$secret'"),
            told(),
        )
    }

    @Test
    fun `a Jackson class a handler reads is judged by the names Jackson reads`() {
        write("src/main/kotlin/app/SearchForm.kt", form)
        write("src/main/kotlin/app/Routes.kt", "package app\n\nfun Route.r() { post(\"/s\") { val f = call.readSignals<SearchForm>() } }\n")
        write("src/main/resources/templates/list.html", """<div data-signals="{q: '', sort: ''}"></div>""")
        assertEquals(listOf("SearchForm.kt: signal-never-declared 'pageNo'"), told())
    }

    @Test
    fun `a class that is no class of signals, an enum or one without properties, declares nothing`() {
        write("src/main/kotlin/app/Kinds.kt", "package app\n\nenum class Kind(val label: String) { A(\"a\") }\nclass Empty()\n")
        write("src/main/kotlin/app/Page.kt", "package app\n\nfun p() = div { dataSignals(codec.toJsonValue(Empty())) }\n")
        write("src/main/resources/templates/list.html", """<p data-text="${'$'}label"></p>""")
        assertEquals(listOf("list.html: unknown-signal '\$label'"), told())
    }

    @Test
    fun `JsonNaming on the class leaves its names to the codec`() {
        write(
            "src/main/kotlin/app/SearchForm.kt",
            "package app\n\n@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)\ndata class SearchForm(val pageNo: Int = 0)\n",
        )
        write("src/main/kotlin/app/Page.kt", page)
        write("src/main/resources/templates/list.html", """<p data-text="${'$'}page_no + ${'$'}anything"></p>""")
        assertEquals(emptyList(), told())
    }

    @Test
    fun `a naming strategy set in code leaves every class's names to the codec`() {
        for ((file, text) in listOf(
            "Mapper.kt" to
                "package app\n\nval mapper = jacksonMapperBuilder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()\n",
            "Json.kt" to "package app\n\nval json = Json { namingStrategy = JsonNamingStrategy.SnakeCase }\n",
        )) {
            files.clear()
            write("src/main/kotlin/app/SearchForm.kt", form)
            write("src/main/kotlin/app/Page.kt", page)
            write("src/main/kotlin/app/$file", text)
            write("src/main/resources/templates/list.html", """<p data-text="${'$'}page_no + ${'$'}anything"></p>""")
            assertEquals(emptyList(), told(), file)
        }
    }

    @Test
    fun `a naming strategy in Spring's settings leaves every class's names to the codec`() {
        for ((file, text) in listOf(
            "application.yml" to "spring:\n  jackson:\n    property-naming-strategy: SNAKE_CASE\n",
            "application-dev.properties" to "spring.jackson.property-naming-strategy=SNAKE_CASE\n",
            "application.yaml" to "spring.jackson.propertyNamingStrategy: SNAKE_CASE\n",
        )) {
            files.clear()
            write("src/main/kotlin/app/SearchForm.kt", form)
            write("src/main/kotlin/app/Page.kt", page)
            write("src/main/resources/$file", text)
            write("src/main/resources/templates/list.html", """<p data-text="${'$'}page_no + ${'$'}anything"></p>""")
            assertEquals(emptyList(), told(), file)
        }
    }

    @Test
    fun `settings that rename nothing, or only in a comment, leave the names Jackson's`() {
        write("src/main/kotlin/app/SearchForm.kt", form)
        write("src/main/kotlin/app/Page.kt", page)
        write("src/main/resources/application.yml", "# spring.jackson.property-naming-strategy: SNAKE_CASE\nserver:\n  port: 8080\n")
        write("src/main/resources/templates/list.html", """<p data-text="${'$'}qurey"></p>""")
        assertEquals(listOf("list.html: unknown-signal '\$qurey'"), told())
    }

    @Test
    fun `the settings files are read as settings, and other files of the same kinds are not read`() {
        assertEquals(SourceLanguage.SETTINGS, ProjectFiles.languageOf("src/main/resources/application.yml"))
        assertEquals(SourceLanguage.SETTINGS, ProjectFiles.languageOf("src/main/resources/application-prod.yaml"))
        assertEquals(SourceLanguage.SETTINGS, ProjectFiles.languageOf("config/application.properties"))
        assertEquals(null, ProjectFiles.languageOf("src/main/resources/logback.yml"))
        assertEquals(null, ProjectFiles.languageOf("build/resources/main/application.yml"))
    }
}
