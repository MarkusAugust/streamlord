import org.intellij.lang.annotations.Language
import io.github.markusaugust.streamlord.core.domain.*
import org.springframework.web.bind.annotation.*
import org.springframework.stereotype.Controller
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import io.github.markusaugust.streamlord.core.domain.interpolate
import a.b as c
import java.util.List as JList

// review309/cases/annotation_html
object Case0 {
@Deprecated("<b>old</b>") fun a() = 1
@Language("HTML") @Deprecated("Hey <b>x</b>") fun b() = 1
@Language("HTML")
@Suppress("Hey <b>$x</b>")
val c = 1
fun d(name: String) = interpolate { @Suppress("x") "<p>${+name}</p>" }
val e = Trusted("<b>x</b>")
fun f(content: @Composable (String) -> Unit) = content("<b>x</b>")
val g: @Composable (String) -> Unit = { "Hey <b>$it</b>".length }
}

// review309/cases/class_headers
object Case36 {
@Suppress("unused")
open class A
@Deprecated("x")
internal data class B(val a: Int)
private
class C
public abstract class D<T> :
    A(),
    Comparable<T>
class E constructor(val x: Int) : A() {
    constructor() : this(1)
}
class F @Inject constructor(private val svc: Service) {
    fun html(name: String) = "<p>$name</p>"
}
value class G(val v: String)
fun interface H { fun run(): String }
annotation class Marker(val value: String = "<b>x</b>")
@Marker("<p>y</p>") class I
}

// review309/cases/spring
object Case44 {
@Controller
@RequestMapping("/users")
class UserController(
    private val repo: UserRepository,
    private val service: UserService?,
) : BaseController(), Logging {
    @GetMapping("/{id}", produces = ["text/html"])
    @ResponseBody
    fun show(@PathVariable id: Long, @RequestParam(required = false) q: String?): String {
        val user = repo.findById(id).orElseThrow()!!
        val name = user.name ?: "anon"
        val count = user.posts.size
        val items = user.posts
            .filter { it.published }
            .map { "<li>${it.title}</li>" }
        return "<div><h1>$name</h1><p>$count</p><ul>${items.joinToString("")}</ul><p>$q</p></div>"
    }

    @PostMapping("/{id}/rename")
    fun rename(@PathVariable id: Long, @RequestParam name: String) = repo.rename(id, name)

    @GetMapping("/page")
    fun page(@RequestParam title: String) = createHTML().html {
        body {
            h1 { +title }
            for (i in 1..3) p { +"Item $i" }
            div("card") {
                attributes["data-on:click"] = "@get('/x')"
            }
        }
    }

    @GetMapping("/fragment") fun fragment(@RequestParam(defaultValue = "x") who: String): String =
        "<p>Hello $who</p>"

    @Language("HTML")
    private fun layout(title: String, content: String): String = """
        <!doctype html>
        <html><head><title>$title</title></head><body>$content</body></html>
    """.trimIndent()

    @Language("HTML")
    @GetMapping("/marked")
    @ResponseBody
    fun marked(@RequestParam who: String): String = "Hello <b>$who</b>"

    @GetMapping("/marked2") @Language("HTML") @ResponseBody
    fun marked2(@RequestParam("who") who: String): String = "Hello <b>$who</b>"

    @Language("HTML") @GetMapping(value = ["/a", "/b"], produces = ["text/html"])
    fun marked3(@RequestParam("who") who: String): String = "Hello <b>$who</b>"

    fun takes(@Language("HTML") @RequestParam("b") body: String, @RequestParam("x") x: String = "Hello <b>$x</b>") = body

    companion object {
        private const val LIMIT = 10
        @JvmStatic val log = LoggerFactory.getLogger(UserController::class.java)
    }
}

data class User(val id: Long, val name: String?, val posts: List<Post> = emptyList())
data class Post(val title: String, val published: Boolean)
interface UserRepository : JpaRepository<User, Long> {
    fun rename(id: Long, name: String): Int
}
}

// review309/cases2/r_annotation_operand
object Case48 {
fun f(fallback: String) {
    @Suppress("UNCHECKED_CAST") val name = fallback as T
    val title = "<b>x</b>"
}
fun g(n: String) {
    fun size(n: Int) = @Suppress("x") n
    println("<p>$n</p>")
}
}

// review309/cases2/r_vararg_modifier
object Case56 {
class Page(@Language("HTML") @Named("t") private val template: String, private val title: String = "Hi <b>$x</b>")
class Page2(@Language("HTML") private val template: String, private val title: String = "Hi <b>$x</b>")
class Page3(@Language("HTML") @Value("\${tpl}") override val template: String, val title: String = "Hi <b>$x</b>")
fun f(@Language("HTML") @Named("x") vararg body: String, z: String = "Hey <b>$z</b>") = body
fun g(@Language("HTML") vararg body: String, z: String = "Hey <b>$z</b>") = body
fun h(@Language("HTML") @Named("x") body: List<String>, z: String = "Hey <b>$z</b>") = body
fun i(@Language("HTML") body: List<String>, z: String = "Hey <b>$z</b>") = body
fun j(@Language("HTML") @Named("x") body: CharSequence, z: String = "Hey <b>$z</b>") = body
fun k(@Language("HTML") body: CharSequence, z: String = "Hey <b>$z</b>") = body
}

// review309/cases3/v_annotation_expr
object Case60 {
fun outer(n: String) {
    fun size(n: Int) = @Suppress("UNCHECKED_CAST") n
    println("<p>$n</p>")
}
fun outer2(n: String, rows: List<Int>) {
    for (n: Int in rows) @Suppress("x") use(n)
    println("<p>$n</p>")
}
}

// review309/cases4/w_kdoc_mark
object Case71 {
/**
 * Pass HTML here; mark it @Language("HTML") at the call site.
 */
fun render(name: String) = "Hello <b>$name</b>"
}

// review309/cases4/w_spring_ctor_mark
object Case72 {
@Component
class Mailer(
    @Language("HTML") @Value("\${mail.template}") private val template: String,
    private val subject: String = "Hello <b>there</b>",
    private val footer: String = "Bye <i>$who</i>",
)
}

// r8-review/hand/H1Words
object Case75 {
class Box {
    companion object
    val a1 = 1

    data object X
    val a2 = 2
}

enum class E {
    A,
    @Deprecated("old") B
}

sealed interface I

class W {
    override val x: Int = 1
    private lateinit var lv: String
    const val CV = 3
    @Inject lateinit var inj: String
    val q: Int
        get() = 4
    var r = cv
        get() = field
    val after = r
}

fun returns(n: Int): String {
    val a = n.toString() + nm
    return a
}

fun returnLabel(xs: List<Int>): String {
    xs.forEach { if (it > 0) return@forEach }
    val v1 = xs.size.toString() + cc
    "<p>$v1</p>".also(::println)
    return "<p>$v1</p>"
}

fun throwing(e: Exception): String {
    val t = e.message ?: throw e
    val u = t + zz
    return "<p>$u</p>"
}

fun loops(xs: List<Int>): String {
    var out = ""
    for (x in xs) {
        if (x < 0) break
        if (x == 0) continue
        out += x
    }
    return "<p>$out</p>"
}

fun arms(x: Int): String {
    val w = when (x) { 1 -> "a" else -> "b" }
    val w2 = when (x) {
        1 -> one
        else -> two
    }
    return "<p>$w $w2</p>"
}

fun <T : Any> typed(t: T): String {
    val v = t.toString() + aa
    return "<p>$v</p>"
}

fun notIn(x: Int, y: List<Int>): String {
    val b = x !in y
    val s = b.toString() + qq
    return "<p>$s</p>"
}

typealias Name = String

/**
 * KDoc that ends in words
 * like this one
 */
fun kdoc(n: Name): String {
    val v = "Hey $n and ${n.length} more"
    val w = v + ww
    return "<p>$w</p>"
}

fun infix(a: Int): String {
    val p = a to
        2
    val q = a shl 2
    val r = p.toString() + q
    return "<p>$r</p>"
}

fun commentWord(x: Int): String {
    val v = /* nothing */ none
    val w = v + other
    return "<p>$w</p>"
}

fun commentElse(x: Int): String {
    val v = when (x) {
        1 -> one
        else -> /* none */ Unit
    }
    val w = v.toString() + tail
    return "<p>$w</p>"
}

fun returnComment(): Boolean {
    val v = /* default */ false
    val w = v.toString()
    return w == "x"
}

fun ann(x: Any): String {
    val v = @Suppress("UNCHECKED_CAST") cast
    val w = v.toString() + more
    return "<p>$w</p>"
}

fun called(x: Int): String {
    val v = listOf(x) to
        x
    val w = listOf(x).size
    return "<p>$w</p>"
}

fun ifBranch(ok: Boolean): String {
    var v = ""
    if (ok) v
    val w = v + "x"
    return "<p>$w</p>"
}

fun byWord(): String {
    val v by lazy { "x" }
    val w = v + "y"
    return "<p>$w</p>"
}
}

// r8-review/hand/H3Marks
object Case77 {
// @Language("HTML")
val c1 = "Hey <b>c1</b>"

/* @Language("HTML") */
val c2 = "Hey <b>c2</b>"

/**
 * Use it as `@Language("HTML") val x = ...`.
 */
val c3 = "Hey <b>c3</b>"

// language=HTML
val c4 = "Hey <b>c4</b>"

/* language=HTML */
val c5 = "Hey <b>c5</b>"

@Language("HTML")
val c6 = "Hey <b>c6</b>"

val s7 = """@Language("HTML")"""
val c7 = "Hey <b>c7</b>"

val s8 = "// language=HTML"
val c8 = "Hey <b>c8</b>"

@Language("HTML") // why
val c9 = "Hey <b>c9</b>"

@Language("HTML") /* @Language("TEXT") */
val c10 = "Hey <b>c10</b>"

/* // language=HTML */
val c11 = "Hey <b>c11</b>"

@Language("HTML")
// @Language("HTML") in a comment after a real one
val c12 = "Hey <b>c12</b>"

val t13 = "${"@Language(\"HTML\")"}"
val c13 = "Hey <b>c13</b>"

val t14 = "@Language(\"HTML\")"
val c14 = "Hey <b>c14</b>"

fun f15() = "${run { @Language("HTML") val z = "x"; z }}"
val c15 = "Hey <b>c15</b>"

@field:Language("HTML")
val c16 = "Hey <b>c16</b>"

fun use(a: String) = "<p>$c1 $c2 $c3 $c4 $c5 $c6 $c7 $c8 $c9 $c10 $c11 $c12 $c13 $c14 $c15 $c16</p>"
}

// r8-review/hand/H5Effects
object Case79 {
fun pageA(name: String): String {
    fun size(n: Int) = /* padded */ n
    "<p>$n</p>".length
    return "<p>$name</p>"
}

fun pageB(name: String): String {
    fun size(n: Int) = @Suppress("x") n
    println("<p>$n</p>")
    return "<p>$name</p>"
}

class Holder(val name: String) {
    val shown = /* raw */ name
        get() = "<b>fixed</b>"

    val other = @Suppress("x") name
        get() = "<b>fixed</b>"

    fun page() = "<p>$shown $other</p>"
}

fun pageC(name: String): String {
    val a = /* raw */ name
    val b = "<b>x</b>"
    return "<p>$a $b</p>"
}

fun pageD(name: String): String {
    val a = name
    val b = "<b>x</b>"
    return "<p>$a $b</p>"
}

@Language("HTML")
// @Language("HTML") again in a comment
val m1 = "Hey <b>m1</b>"

@Language("HTML") // say "hi"
val m2 = "Hey <b>m2</b>"

@Language("HTML") /* the "body" */
val m3 = "Hey <b>m3</b>"

@Language("HTML")
// a "quoted" word
val m4 = "Hey <b>m4</b>"

fun use() = "<p>$m1 $m2 $m3 $m4</p>"
}

