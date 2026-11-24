import org.intellij.lang.annotations.Language

class P91Layout(val title: String) {
    @Language("HTML")
    val head: String by lazy { "P916 <title>$title</title>" }

    @get:Language("HTML")
    val nav: String
        get() {
            return "P917 <nav>$title</nav>"
        }

    @Language("HTML")
    val main: String
        get() = "P918 <main></main>"

    @Language("HTML")
    var body: String = ""
        set(value) {
            field = "P919 <body>$value</body>" // missed: #347
        }

    @Language("HTML")
    private val cache = mutableMapOf<String, String>()

    fun get(k: String) = cache.getOrPut(k) { "Q140 <i>$k</i>" }
}
