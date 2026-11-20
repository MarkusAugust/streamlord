import org.intellij.lang.annotations.Language
import io.github.markusaugust.streamlord.core.domain.*
import io.github.markusaugust.streamlord.spring.*
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*

// rev309/cases1: a285_basic
object Case0 {
@Language("HTML") @JvmName("m03x") fun m03(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_newline
object Case1 {
@Language("HTML")
@JvmName("m03x")
fun m03(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_suppress_then_mark
object Case2 {
@Suppress("unused") @Language("HTML") fun m03(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_mark_two_anns
object Case3 {
@Language("HTML") @Suppress("unused", "x") @JvmName(name = "q") fun m03(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_mark_nested_ann
object Case4 {
@Language("HTML") @Foo(Bar("x"), [Baz("y")]) fun m03(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_param_after_named
object Case5 {
fun card(@Language("HTML") @Named("body") body: String, title: String = "Hi <b>x</b>") = body + title
fun use(n: String) = card("<p>$n</p>")
}

// rev309/cases1: a285_param_nested
object Case6 {
fun card(@Language("HTML") @Named(value = Q("b")) body: String, title: String = "Hi <b>x</b>") = body
}

// rev309/cases1: a285_param_nospace
object Case8 {
fun card(@Language("HTML") @Named("x")body: String, title: String = "Hi <b>x</b>") = body
}

// rev309/cases1: a285_ann_literal_tag
object Case9 {
@Deprecated("<b>not</b> markup")
fun d() = 1
@Suppress("<p>x</p>") val e = "<p>yes</p>"
}

// rev309/cases1: a285_ann_in_mark_reach
object Case10 {
class P {
    @Language("HTML")
    @get:JvmName("title")
    val title: String = "Hi <b>there</b>"
}
}

// rev309/cases1: a285_mark_then_ann_noargs
object Case12 {
@Language("HTML") @JvmStatic fun m(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_mark_ann_param_default
object Case13 {
fun f(@Language("HTML") html: String? = null, x: Int) = html
fun g(name: String) = "Hey <b>$name</b>"
}

// rev309/cases1: a285_interp_in_ann
object Case14 {
fun a(name: String) = interpolate { "Hello <b>${+name}</b>" }
@Foo(interpolate { "<b>x</b>" }) fun q() = 1
}

// rev309/cases1: a285_trusted_ann
object Case15 {
@Foo(Trusted("<b>x</b>")) fun q() = 1
fun b() = Trusted("bold <b>text</b>")
}

// rev309/cases1: a285_htmlarg_in_ann
object Case16 {
fun wrap(@Language("HTML") body: String) = body
@Foo(wrap("bold")) fun q() = 1
fun r() = wrap("bold")
}

// rev309/cases1: a285_lambda_type_annotation
object Case17 {
@Language("HTML") val f: @Composable () -> String = { "Hi <b>x</b>" }
}

// rev309/cases1: a285_raw_mark
object Case19 {
@Language("HTML") @JvmField val page = """
    <div>${name}</div>
"""
}

// rev309/cases1: b296_nullable_getter
object Case21 {
class A(q: Any?) {
    val a = q as String?
    val title get() = "<b>x</b>"
}
}

// rev309/cases1: b296_typeargs_getter
object Case22 {
class A(q: Any?) {
    val a = q as List<Int>
    val title get() = "<b>x</b>"
}
}

// rev309/cases1: c283_loop_annotated_stmt
object Case67 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) @Suppress("x") println(n)
    println("<p>$n</p>")
}
}

// rev309/cases2: r_language_layouts
object Case83 {
class Views(private val name: String) {
    @Language("HTML")
    val header = "<header>$name</header>"

    @Language("HTML") val footer: String = "<footer>$name</footer>"

    @Language("HTML")
    fun greet(who: String): String {
        return "Hello <b>$who</b>"
    }

    @Language("HTML") fun short(who: String) = "Hi <i>$who</i>"

    @Language("HTML")
    @Suppress("unused")
    fun suppressed(who: String) = "Hi <i>$who</i>"

    @Suppress("unused")
    @Language("HTML")
    fun suppressed2(who: String) = "Hi <i>$who</i>"

    @Language("HTML") @Deprecated("use greet", ReplaceWith("greet(who)")) fun old(who: String) = "Yo <i>$who</i>"

    fun param(@Language("HTML") body: String, extra: String = "plain") = body + extra

    fun call(who: String) = param("Hey <b>$who</b>")

    @get:Language("HTML")
    val getter: String
        get() = "Get <b>$name</b>"

    @field:Language("HTML") @JvmField
    val field = "Field <b>$name</b>"

    @Language(value = "HTML", prefix = "<div>", suffix = "</div>") val pre = "Pre <b>$name</b>"
}
}

// rev309/cases2: r_mixed_anns_class
object Case93 {
@Serializable
data class Item(
    @SerialName("item_name") val name: String,
    @Language("HTML") @SerialName("desc") val description: String = "<p>none</p>",
    val price: Int,
)
fun show(item: Item) = "<li>${item.name}</li>"
}

// rev309/cases2: r_mark_ann_property_default_after
object Case94 {
class A(
    @Language("HTML") @Named("x") val body: String,
    val title: String = "Hi <b>x</b>",
)
}

// rev309/cases3: o_mark_inside_string
object Case116 {
val s = "@Language(\"HTML\") @JvmName(\"x\")"
val page = "Hi <b>x</b>"
}

// rev309/cases3: o_ann_use_site
object Case118 {
class A {
    @Language("HTML") @param:Named("q") @field:Foo("z") val q = "Hi <b>q</b>"
}
}

// rev309/cases3: o_ann_on_expression
object Case119 {
fun f(x: Any) {
    val a = @Suppress("UNCHECKED_CAST") (x as List<String>)
    val t = "<b>$a</b>"
}
}

// rev309/cases3: o_raw_with_quotes_in_ann
object Case131 {
@Language("HTML") @Sample("""quoted "x" """) val p = "Hi <b>x</b>"
}

// rev309/cases3: o_mark_far
object Case132 {
@Language("HTML") @Suppress("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") val p = "Hi <b>x</b>"
}

// rev309/cases3: o_mark_param_class_ctor
object Case133 {
class Card(@Language("HTML") @Named("b") val body: String)
val page = "Hi <b>x</b>"
}

// rev309/cases3: o_mark_param_no_type_string
object Case134 {
fun f(@Language("HTML") @Named("b") body: CharSequence) = body
val page = "Hi <b>x</b>"
}

// rev309/cases4: p_loop_stmt_annotation_lambda
object Case159 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) @Suppress("x") {
        println(n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases4: p_spring_mvc_full
object Case174 {
@Controller
class TodoController(private val todos: TodoRepository) {
    @GetMapping("/todos")
    @ResponseBody
    fun list(@RequestParam(defaultValue = "") filter: String): String {
        val items = todos.all()
            .filter { it.title.contains(filter) }
        val rendered = StringBuilder()
        for (t in items) rendered.append(row(t))
        @Language("HTML")
        val page = """
            <ul id="todos">$rendered</ul>
            <p>$filter</p>
        """
        return page
    }

    @Language("HTML")
    private fun row(t: Todo) = "<li data-id=\"${t.id}\">${t.title}</li>"

    @PostMapping("/todos/{id}/toggle")
    fun toggle(@PathVariable id: Long, @RequestHeader("X-Name") name: String?) = sse { stream ->
        val todo = todos.find(id)!!
        todos.save(todo.copy(done = !todo.done))
        stream.patchElements(interpolate { "<li id=\"t${+id}\">${+todo.title} $name</li>" })
    }
}
}

// rev309/cases6: s_mark_followed_by_two_line_annotation
object Case196 {
@Language("HTML")
@Deprecated(
    "old",
    ReplaceWith("x"),
)
fun f(name: String) = "Hi <b>$name</b>"
}

// rev309/cases6: s_mark_property_then_ann_on_next_decl
object Case197 {
class A(val name: String) {
    @Language("HTML") val x = 1
    @Named("q") val y = "Hi <b>$name</b>"
}
}

// r4mark/cases: m01_getter_next_line.kt
object Case198 {
class A(val name: String) {
    @Language("HTML")
    val a: String
        get() = "Hey <b>$name</b>"
}
}

// r4mark/cases: m02_block_getter.kt
object Case199 {
class A(val name: String) {
    @Language("HTML")
    val b: String
        get() {
            if (name.isEmpty()) return "Empty <i>x</i>"
            return "Hey <b>$name</b>"
        }
}
}

// r4mark/cases: m03_by_lazy.kt
object Case200 {
class A(val name: String) {
    @Language("HTML")
    val c by lazy { "Hey <b>$name</b>" }
    @Language("HTML")
    val d: String by lazy {
        "Ho <b>$name</b>"
    }
}
}

// r4mark/cases: m04_var_typed.kt
object Case201 {
class A(val name: String) {
    @Language("HTML")
    var d: String = "Hey <b>$name</b>"
    @Language("HTML") private var e: String? = "Ho <b>$name</b>"
}
}

// r4mark/cases: m05_const.kt
object Case202 {
@Language("HTML")
const val E = "Hey <b>x</b>"

object O {
    @Language("HTML") const val F = "Ho <b>x</b>"
    @Language("HTML") private const val G: String = "Hi <b>x</b>"
}
}

// r4mark/cases: m06_interface.kt
object Case203 {
interface I {
    @Language("HTML")
    fun f(n: String): String = "Hey <b>$n</b>"

    @Language("HTML")
    fun g(n: String): String {
        return "Ho <b>$n</b>"
    }

    @Language("HTML")
    fun h(): String
}
}

// r4mark/cases: m07_override.kt
object Case204 {
open class Base {
    @Language("HTML")
    open fun g(n: String): String = "Base <b>$n</b>"
}

class C : Base() {
    @Language("HTML")
    override fun g(n: String): String {
        if (n.isEmpty()) return "Empty <a>x</a>"
        val other = "plain $n"
        return "Hey <b>$n</b>"
    }
}
}

// r4mark/cases: m08_expr_next_line.kt
object Case205 {
@Language("HTML")
fun h(n: String) =
    "Hey <b>$n</b>"

@Language("HTML")
fun h2(n: String): String =
    """
    Raw <b>$n</b>
    """.trimIndent()
}

// r4mark/cases: m09_sum.kt
object Case206 {
@Language("HTML")
fun s(n: String) = "Hey <b>" + n + "</b> there"

@Language("HTML")
val t = "Ho <b>" + load() + "</b>"
}

// r4mark/cases: m10_if_when.kt
object Case207 {
@Language("HTML")
fun w(x: Boolean, n: String) = if (x) "Hey <b>$n</b>" else "Ho <i>$n</i>"

@Language("HTML")
fun v(x: Int, n: String) = when (x) {
    1 -> "One <b>$n</b>"
    else -> "Other <i>$n</i>"
}

@Language("HTML")
val u = if (flag) "Val <b>x</b>" else "Val2 <i>x</i>"
}

// r4mark/cases: m11_trim.kt
object Case208 {
@Language("HTML")
val page = """
    Page <b>$name</b>
""".trimIndent()

@Language("HTML")
fun page2(name: String) = """
    Page2 <b>$name</b>
""".trimIndent()
}

// r4mark/cases: m12_multidollar.kt
object Case209 {
@Language("HTML")
val md = $$"""Hey <b>$$name</b> $plain"""

@Language("HTML")
fun md2(name: String) = $$"""Ho <b>$${name}</b>"""
}

// r4mark/cases: m13_call_value.kt
object Case210 {
@Language("HTML")
val x = render("a")

@Language("HTML")
val y = listOf("b", "c").joinToString()

@Language("HTML")
fun z(n: String) = render("d")

@Language("HTML")
fun q(n: String): String {
    return error("x $n")
}
}

// r4mark/cases: m16_local_fun.kt
object Case213 {
@Language("HTML")
fun outer(n: String): String {
    fun inner(): String {
        return "plain one"
    }
    val list = listOf(1).map { return "lambda <b>x</b>" }
    return "Outer <b>$n</b>"
}
}

// r4mark/cases: m17_labelled.kt
object Case214 {
@Language("HTML")
fun list(items: List<String>): String {
    items.forEach { if (it.isEmpty()) return@forEach }
    return "Items <b>x</b>"
}

@Language("HTML")
fun list2(items: List<String>): String = run label@{
    if (items.isEmpty()) return@label "None <b>x</b>"
    "Some <b>x</b>"
}
}

// r4mark/cases: m18_annotations_between.kt
object Case215 {
@Language("HTML")
@Suppress("unused")
@JvmName("hey")
fun hey(who: String) = "Hey <b>$who</b>"

@Suppress("x") @Language("HTML") @JvmField
val v = "Ho <b>x</b>"

@Language("HTML") @Deprecated("old", ReplaceWith("other()"))
fun dep(n: String) = "Dep <b>$n</b>"
}

// r4mark/cases: m19_param_marks.kt
object Case216 {
fun f(@Language("HTML") html: String, other: String = "plain") = html

fun g(@Suppress("x") @Language("HTML") @Deprecated("y") html: String = "default <b>") = html

class K(@Language("HTML") val body: String = "Body <b>x</b>")

fun use() = f("Arg <b>x</b>", "not html")
}

// r4mark/cases: m20_return_call.kt
object Case217 {
@Language("HTML")
fun r(n: String): String {
    if (n.isEmpty()) return render("plain arg")
    return (
        "Paren <b>$n</b>"
    )
}

@Language("HTML")
fun r2(n: String): String = ("Paren2 <b>$n</b>")
}

// r4mark/cases: m21_elvis_value.kt
object Case218 {
@Language("HTML")
val e1 = cached ?: "Elvis <b>x</b>"

@Language("HTML")
fun e2(n: String?) = n ?: "Elvis2 <b>x</b>"

@Language("HTML")
fun e3(n: String): String {
    return n.ifEmpty { "Lambda <b>x</b>" }
}
}

// r4mark/cases: m22_no_literal_then_next.kt
object Case219 {
@Language("HTML")
val a = load()
val b = "next decl"

@Language("HTML")
fun c(n: String) = n
val d = "after fun"
}

// r4mark/cases: m23_marked_template.kt
object Case220 {
@Language("HTML")
val a = "${"inner"} Outer <b>x</b>"

@Language("HTML")
fun b(n: String) = "Prefix ${n.ifEmpty { "x" }} <b>y</b>"
}

// r4mark/cases: m24_extension.kt
object Case221 {
@Language("HTML")
fun String.wrap() = "Ext <b>$this</b>"

@Language("HTML")
val String.wrapped get() = "ExtProp <b>$this</b>"

@Language("HTML")
fun <T> gen(t: T) = "Gen <b>$t</b>"

@Language("HTML")
inline fun <reified T> gen2(t: T): String = "Gen2 <b>$t</b>"
}

// r4mark/cases: m25_companion.kt
object Case222 {
class Q {
    companion object {
        @Language("HTML")
        const val TPL = "Tpl <b>x</b>"

        @Language("HTML")
        fun make(n: String) = "Make <b>$n</b>"
    }
}
}

// r4mark/cases: m26_suspend.kt
object Case223 {
@Language("HTML")
suspend fun s(n: String): String {
    delay(1)
    return "Suspend <b>$n</b>"
}

@Language("HTML")
private fun p(n: String) = "Private <b>$n</b>"
}

// r4mark/cases: l01_postfix.kt
object Case225 {
fun f(x: String, xs: List<Int>) {
    var n = 0
    for (x in xs) n++
    println("<p>$x</p>")
    for (x in xs) n--
    println("<p>$x</p>")
    for (x in xs) x?.let { n += it }
    println("<p>$x</p>")
}
}

// r4mark/cases: l02_elvis_next.kt
object Case226 {
fun f(x: String, xs: List<Int?>) {
    var n = 0
    for (x in xs) n += x
        ?: 0
    println("<p>$x</p>")
    for (x in xs) use(x)
        .also { println("<p>$x</p>") }
    println("<p>$x</p>")
}
}

