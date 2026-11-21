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

    /** The page declared from `F`, and the reads of [names] in a template, with the class [klass] in its own file. */
    private fun pageOf(
        klass: String,
        vararg names: String,
        pagePackage: String = "app",
        imports: String = "",
        others: Map<String, String> = emptyMap(),
    ): List<String> {
        files.clear()
        files += others
        write("src/main/kotlin/app/F.kt", "package app\n\n$klass\n")
        write("src/main/kotlin/app/Page.kt", "package $pagePackage\n$imports\nfun p() = div { dataSignals(codec.toJsonValue(F())) }\n")
        write("src/main/resources/templates/list.html", "<p data-text=\"${names.joinToString(" + ") { "${'$'}$it" }}\"></p>")
        return told()
    }

    @Test
    fun `a class whose names are not all in its constructor is left to the codec`() {
        for (klass in listOf(
            "data class F(val a: String = \"\") {\n    val b: String get() = a\n}",
            "class F() {\n    var b: Int = 0\n}",
            "open class Base(val b: String)\nclass F(val a: String = \"\") : Base(\"x\")",
            "@JsonIgnoreProperties(\"a\")\ndata class F(val a: String = \"\")",
            "data class F(@JsonAlias(\"q\") val a: String = \"\")",
            "data class F(@JsonProperty(access = JsonProperty.Access.READ_ONLY) val a: String = \"\")",
        )) {
            assertEquals(emptyList(), pageOf(klass, "b", "q"), klass)
        }
    }

    @Test
    fun `a class of a kind that holds no signals is no Jackson class`() {
        for (klass in listOf(
            "enum class F(val a: String) { X(\"x\") }",
            "@JvmInline\nvalue class F(val a: String)",
            "annotation class F(val a: String)",
        )) {
            assertEquals(emptyList(), pageOf(klass, "b"), klass)
        }
    }

    @Test
    fun `a Serializable class is kotlinx's however its annotations are written`() {
        for (klass in listOf(
            "@kotlinx.serialization.Serializable\ndata class F(@SerialName(\"q\") val a: String = \"\")",
            "@Serializable\n@Schema(description = \"a (b)\")\ndata class F(@SerialName(\"q\") val a: String = \"\")",
        )) {
            assertEquals(listOf("list.html: unknown-signal '\$a'"), pageOf(klass, "q", "a"), klass)
        }
    }

    @Test
    fun `a JsonProperty names the signal however it is written`() {
        for (klass in listOf(
            "data class F(@JsonProperty(required = true, value = \"q\") val a: String = \"\")",
            "data class F(@JsonProperty(\"q\") @Schema(description = \"x (y)\") val a: String = \"\")",
            "data class F(\n    @JsonProperty(\"q\") // the query\n    val a: String = \"\",\n)",
        )) {
            assertEquals(listOf("list.html: unknown-signal '\$a'"), pageOf(klass, "q", "a"), klass)
        }
    }

    @Test
    fun `a private constructor property is no signal, as Jackson writes none`() {
        assertEquals(
            listOf("list.html: unknown-signal '\$b'"),
            pageOf("class F(val a: String = \"\", private val b: String = \"\")", "a", "b"),
        )
    }

    @Test
    fun `a class is the one the page's package and imports name, and one in a comment is none`() {
        assertEquals(emptyList(), pageOf("data class F(val a: String = \"\")", "q", pagePackage = "web", imports = "import lib.F\n"))
        assertEquals(
            listOf("list.html: unknown-signal '\$q'"),
            pageOf("data class F(val a: String = \"\")", "a", "q", pagePackage = "web", imports = "import app.F\n"),
        )
        assertEquals(emptyList(), pageOf("// was: class F(val old: String)\nclass F<T>(val items: List<T>)", "items"))
    }

    @Test
    fun `an import of a naming strategy for JsonNaming renames nothing else`() {
        write(
            "src/main/kotlin/app/Snake.kt",
            "package app\n\nimport com.fasterxml.jackson.databind.PropertyNamingStrategies\n\n@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)\ndata class Snake(val pageNo: Int = 0)\n",
        )
        write("src/main/resources/templates/other.html", """<div data-signals="{q: ''}"><p data-text="${'$'}qq"></p></div>""")
        assertEquals(listOf("other.html: unknown-signal '\$qq'"), told())
    }

    @Test
    fun `a naming strategy leaves the classes to the codec and checks markup as before`() {
        for ((file, text) in listOf(
            "src/main/resources/application.yml" to "spring.jackson.property-naming-strategy: SNAKE_CASE\n",
            "src/main/kotlin/app/Json.kt" to "package app\n\nval json = Json { namingStrategy = JsonNamingStrategy.SnakeCase }\n",
        )) {
            files.clear()
            write(file, text)
            write("src/main/resources/templates/list.html", """<div data-signals="{query: ''}"><p data-text="${'$'}qurey"></p></div>""")
            assertEquals(listOf("list.html: unknown-signal '\$qurey'"), told(), file)
        }
    }

    @Test
    fun `a naming strategy of something other than a codec renames no signal`() {
        val db = mapOf("src/main/kotlin/app/Db.kt" to "package app\n\nval cfg = DatabaseConfig { namingStrategy = SnakeCase }\n")
        assertEquals(listOf("list.html: unknown-signal '\$query'"), pageOf("data class F(val a: String = \"\")", "a", "query", others = db))
    }

    @Test
    fun `a constructor is read as code, its strings and default values aside`() {
        assertEquals(emptyList(), pageOf("data class F(val a: String = \"x)\", val b: Int = 0)", "a", "b"))
        assertEquals(
            listOf("list.html: unknown-signal '\$tmp'"),
            pageOf("data class F(val a: Int = run { val tmp: Int = 1; tmp })", "a", "tmp"),
        )
    }

    @Test
    fun `an empty JsonProperty keeps the property's name`() {
        assertEquals(emptyList(), pageOf("data class F(@JsonProperty(\"\") val a: Int = 0)", "a"))
    }

    @Test
    fun `a kotlinx class and a Jackson class of one name are told apart by their packages`() {
        files.clear()
        write("src/main/kotlin/app/a/F.kt", "package app.a\n\n@Serializable\ndata class F(val x: Int = 0)\n")
        write("src/main/kotlin/app/b/F.kt", "package app.b\n\n@JsonNaming(SnakeCaseStrategy::class)\ndata class F(val bX: Int = 0)\n")
        write("src/main/kotlin/app/b/Page.kt", "package app.b\n\nfun p() = div { dataSignals(codec.toJsonValue(F())) }\n")
        write("src/main/resources/templates/list.html", "<p data-text=\"${'$'}b_x\"></p>")
        assertEquals(emptyList(), told())
    }

    @Test
    fun `a Jackson class whose extras add no names is read`() {
        for (klass in listOf(
            "@JsonIgnoreProperties(ignoreUnknown = true)\ndata class F(val a: String = \"\")",
            "data class F(val a: String = \"\") {\n    fun twice() = a + a\n\n    companion object {\n        val EMPTY = F()\n    }\n}",
            "data class F(val a: String = \"\") : java.io.Serializable",
        )) {
            assertEquals(listOf("list.html: unknown-signal '\$b'"), pageOf(klass, "a", "b"), klass)
        }
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
