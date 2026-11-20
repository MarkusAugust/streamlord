import org.intellij.lang.annotations.Language

// review-309/marks2.kt: kdoc-paren-getter
object Case681 {
class P {
    @Language("HTML")
    /** The page (shown on top). */
    val page: String get() = "Hey <b>x</b>"
}
}

// review-309/marks2.kt: kdoc-paren-fun
object Case682 {
@Language("HTML")
/** The page (shown on top). */
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks2.kt: kdoc-paren-val
object Case683 {
@Language("HTML")
// shown (on top)
val page = "Hey <b>x</b>"
}

// review-309/marks2.kt: var-typed-paren
object Case684 {
class P {
    @Language("HTML")
    var page: String = "Hey <b>x</b>"
        private set
}
}

// review-309/marks2.kt: getter-block-return
object Case685 {
class P(val ok: Boolean) {
    @Language("HTML")
    val s: String
        get() {
            if (ok) return "Hey <b>x</b>"
            return ""
        }
}
}

// review-309/marks2.kt: fun-paren-body
object Case686 {
@Language("HTML")
fun page(name: String) = ("Hey <b>$name</b>")
}

// review-309/marks2.kt: fun-return-after-local-val
object Case687 {
@Language("HTML")
fun page(name: String): String {
    val head = "Head text"
    return "Hey <b>$name</b> $head"
}
}

// review-309/marks2.kt: fun-expr-next-line-mark
object Case688 {
@Language("HTML")
fun page(name: String) =
    "Hey <b>$name</b>"

fun other(name: String) = "plain $name"
}

// review-309/marks2.kt: ctor-val-mark-literal-next
object Case689 {
class P(
    @Language("HTML") val body: String,
    val title: String = "plain",
)
}

// review-309/marks2.kt: mark-in-param-of-lambda-type
object Case690 {
fun f(@Language("HTML") render: (String) -> String = { "Hey <b>$it</b>" }) = render("x")
}

// review-309/marks2.kt: mark-get-site-val-noinit
object Case691 {
class P {
    @get:Language("HTML")
    val page: String
        get() = "Hey <b>x</b>"
}
}

// review-309/marks2.kt: marked-object-fun-generic-ret
object Case692 {
object V {
    @Language("HTML")
    fun list(items: List<String>): String = items.joinToString("") { "<li>$it</li>" }
    fun name() = "plain"
}
}

// review-309/marks2.kt: expr-continues-template
object Case693 {
@Language("HTML")
fun page(name: String) = "Hey <b>$name</b>" +
    "<i>more</i>"
}

// review-309/marks2.kt: ktor-respond-mark-var
object Case694 {
suspend fun handle(call: Call, name: String) {
    @Language("HTML")
    val body = """
        Hello <b>$name</b>
    """.trimIndent()
    call.respondText(body)
}
}

// review-309/marks2.kt: marked-fun-interface-no-body
object Case695 {
interface V {
    @Language("HTML")
    fun render(name: String): String
    fun title() = "plain"
}
}

// review-309/marks2.kt: marked-val-elvis-call
object Case696 {
fun f(x: String?) {
    @Language("HTML") val s = x?.let { "Hey <b>$it</b>" } ?: "none"
}
}

// review-309/unicode.kt: unicode-mark
object Case698 {
@Language("HTML") @JvmName("hæ")
fun hælse(navn: String) = "Hei <b>$navn</b>"
}

// r4mark/cases: l17_for_header_chain
object Case701 {
fun list(item: String, items: List<Int>): String {
    val out = StringBuilder()
    for (item: Int in items
        .filter { it > 0 }) {
        out.append("<li>$item</li>")
    }
    return out.toString()
}
}

// r5review/cases: d14_getter
object Case715 {
class A {
    val a = q!!
    val title get() = "<b>x</b>"
    val b: List<Int>
        get() = listOf(1)
    val c = foo as String?
    val d get() = "<p>y</p>"
}
}

// r5review/cases: d18_annot_stmt
object Case719 {
fun f(n: String, xs: List<Int>) {
    for (n: Int in xs) @Suppress("x") use(n)
    println("<p>$n</p>")
}
}

// r5review/cases: d19_expr_annot
object Case720 {
fun outer(n: String) {
    fun one(n: Int) = @Suppress("UNCHECKED_CAST") (n as Int)
    println("<p>$n</p>")
}
}

// r5review/cases: m01_getter
object Case731 {
val name = load()
@Language("HTML") val html: String get() = "Hi <b>$name</b>"
}

// r5review/cases: m02_var
object Case732 {
val name = load()
@Language("HTML") var out = "Hi <b>$name</b>"
}

// r5review/cases: m03_open
object Case733 {
open class Page { @Language("HTML") open fun head(title: String): String = "Title <b>$title</b>" }
}

// r5review/cases: m04_ifwhen
object Case734 {
fun f(ok: Boolean, n: String, k: Int) {
    @Language("HTML") val v = if (ok) "a <b>$n</b>" else "b <i>$n</i>"
    @Language("HTML") val w = when (k) { 1 -> "x <b>$n</b>" else -> "y" }
    @Language("HTML") val e = n.takeIf { ok } ?: "z <b>$n</b>"
}
}

// r5review/cases: m06_others
object Case736 {
val name = load()
@Language("HTML") @JvmName("hey") fun hey() = "Hey <b>$name</b>"
@JvmName("hoy") @Language("HTML") fun hoy() = "Hoy <b>$name</b>"
@Language("HTML") @Deprecated("old", ReplaceWith("new()")) fun old() = "Old <b>$name</b>"
@Language("HTML") @Suppress("A", "B") @JvmName("x2") fun x2() = "X2 <b>$name</b>"
@Language("HTML") @get:JvmName("gg") val gg: String get() = "Gg <b>$name</b>"
@Deprecated("<b>x</b>") fun dep() = 1
@Language("HTML") @Foo("Inside <b>$name</b>") fun inside() = 2
@Suppress(names = ["<p>a</p>"]) fun arr() = 3
@Language("HTML") @Foo(bar = Baz("q")) fun nested() = "Nested <b>$name</b>"
}

// r5review/cases: m07_params
object Case737 {
fun a(@Language("HTML") @Named("body") body: String, title: String = "Hi <b>x</b>") = body + title
fun b(@Language("HTML") @Deprecated("x", ReplaceWith("y")) body: String, title: String = "Hi <b>x</b>") = body + title
fun c(@Language("HTML") @Pattern(regexp = "^(a|b)$") body: String, title: String = "Hi <b>x</b>") = body + title
fun d(@Language("HTML") body: String, title: String = "Hi <b>x</b>") = body + title
class P(@Language("HTML") @param:Foo("a") val body: String = "Own <b>x</b>", val t: String = "T <b>x</b>")
class Q(@Language("HTML") val body: String = "Own <b>x</b>", val t: String = "T <b>x</b>")
}

// r5review/cases: m08_spring
object Case738 {
class C {
    @GetMapping("/page", produces = ["text/html"])
    @Language("HTML")
    fun page(@RequestParam("name") name: String): String = "Page <b>$name</b>"

    @Language("HTML")
    @GetMapping("/other")
    @ResponseBody
    fun other(@RequestParam(defaultValue = "x") n: String): String = "Other <b>$n</b>"
}
}

// r5review/cases: m09_mark_far
object Case739 {
val name = load()
@Language("HTML") @Suppress("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") fun far() = "Far <b>$name</b>"
}

// r5review/cases: m11_layouts
object Case741 {
class K(val name: String) {
    @Language("HTML")

    /** Doc "quoted". */
    val a: String
        get() = "A <b>$name</b>"

    @field:Language("HTML") val b = "B <b>$name</b>"
    @Language(value = "HTML") val c = "C <b>$name</b>"
    @Language("HTML", prefix = "<div>") val d = "D <b>$name</b>"
    @Language("HTML") private val e = "E <b>$name</b>"
    @Language("HTML") override fun toString() = "F <b>$name</b>"
    @Language("HTML") @Suppress("x") val g by lazy { "G <b>$name</b>" }
    @Language("HTML") @Foo("""raw""") val h = "H <b>$name</b>"
    @Language("HTML") @Foo('"') val i = "I <b>$name</b>"
    @Language("HTML") @Foo("a") @Language("HTML") val j = "J <b>$name</b>"
    @Language("HTML")
    @Throws(IllegalStateException::class)
    fun k(): String = "K <b>$name</b>"
}
}

// r7/hand: char_literal_between
object Case746 {
@Language("HTML")
val sep: String = if (c == ',') "Hey <b>x</b>" else ""
}

// r7/hand: fun_marked_param_list_default
object Case763 {
@Language("HTML")
fun page(a: String = "", n: Int) = "Hey <b>$n</b>"
}

// r7/hand: kdoc_marker_param_text
object Case767 {
/**
 * Mark a parameter: `@Language("HTML") body: String,`
 */
fun page(n: Int) = "Hey <b>$n</b>"
}

// r7/hand: kdoc_marker_plain
object Case768 {
/**
 * Write `@Language("HTML")` on it.
 */
fun page(n: Int) = "Hey <b>$n</b>"
}

// r7/hand: param_annot_nospace
object Case773 {
fun page(@Language("HTML") @Named("x")body: String) = "Hey <b>$body</b>"
}

// r7/hand: param_default_comma
object Case776 {
fun page(@Language("HTML") title: String = "a, b)", body: String) = "Hey <b>$body</b>"
}

// r7/hand: param_default_comma_only
object Case777 {
fun page(@Language("HTML") title: String = "a, b)") = "Hey <b>$title</b>"
}

// r7/hand: param_default_html_comma
object Case778 {
fun page(@Language("HTML") title: String = "Hi <b>a, b)</b>") = title
fun use(n: Int) = page("Hey <i>x</i>")
}

// r7/hand: type_args_annotation_eq
object Case792 {
class A {
    val xs: List<@Size(max = 3) String>
        get() = load()
    val n: Int = 1
}
fun page(a: A) = "<p>${a.n}</p>"
}

// r7/hand: type_args_plain_getter
object Case795 {
class A {
    val xs: List<String>
        get() = load()
    val n: Int = 1
}
}

// r7/hand: type_args_string_eq
object Case796 {
fun page(n: Int): String {
    val m = cast<@Named("a=b") String>
    val w = n
    return "<p>$w</p>"
}
}

// r7/probes: c01-param-backtick-annotation
object Case799 {
class P(@`A` val title: String) {
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c02-param-typearg-annotation
object Case800 {
class P(@A<String> val title: String) {
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c03-fun-param-backtick-annotation
object Case801 {
fun f(@`A` n: Int) = "<p>$n</p>"
val n = "<i>"
}

// r7/probes: c04-else-newline-open
object Case802 {
open class P {
    val a = if (c) x else
        open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c05-if-newline-const
object Case803 {
fun f(c: Boolean) {
    if (c)
        const
    val title = "<b>x</b>"
    println("<p>$title</p>")
}
}

// r7/probes: c07-for-const-script
object Case804 {
    fun case804() {
for (x in rows) const
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
    }
}

// r7/probes: c07b-for-const-fun
object Case805 {
fun f(rows: List<Int>) {
    for (x in rows) const
    val title = "<b>x</b>"
    println("<p>$title</p>")
}
}

// r7/probes: c07c-for-open-init
object Case806 {
open class P {
    init {
        for (x in rows) open
    }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c07d-for-private-script
object Case807 {
    fun case807() {
for (x in rows) private
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
    }
}

// r7/probes: c07e-for-open-script
object Case808 {
    fun case808() {
for (x in rows) open
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
    }
}

// r7/probes: c09-while-const
object Case810 {
fun f(c: Boolean) {
    while (c) const
    val title = "<b>x</b>"
    println("<p>$title</p>")
}
}

// r7/probes: c11-dotted-backtick-open
object Case811 {
open class P {
    @a.`b` open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c12-dotted-backtick-const
object Case812 {
@a.`b` const val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: c12b-dotted-backtick-private
object Case813 {
@`a`.b private val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: c12c-dotted-backtick-mark
object Case814 {
@`a`.b @Language("HTML") val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes: c12d-mark-dotted-backtick
object Case815 {
@Language("HTML") @`a`.b val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes: c13-funtype-private
object Case817 {
@A<(Int) -> Unit> private val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: c13b-funtype-const
object Case818 {
@A<(Int) -> Unit> const val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: c14-mark-funtype
object Case819 {
@Language("HTML") @A<(Int) -> Unit> val title = load()
fun page() = "<p>$title</p>"
}

