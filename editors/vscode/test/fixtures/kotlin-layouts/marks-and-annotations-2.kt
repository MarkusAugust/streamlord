import org.intellij.lang.annotations.Language
import org.springframework.web.bind.annotation.*
@file:JvmName("Pages")

// r4mark/cases: l03_do_while.kt
object Case227 {
fun f(x: String, xs: List<Int>) {
    var n = 0
    for (x in xs) do {
        n++
    } while (n < x)
    println("<p>$x</p>")
    do {
        n--
    } while (n > 0)
    val x2 = "<p>$x</p>"
}
}

// r4mark/cases: l04_while.kt
object Case228 {
fun f(x: String, xs: List<Int>) {
    var i = 0
    while (i < 10) i++
    println("<p>$x</p>")
    while (i > 0)
        i--
    println("<p>$x</p>")
    for (x in xs)
        while (i < x) i++
    println("<p>$x</p>")
}
}

// r4mark/cases: l05_if_else_inside.kt
object Case229 {
fun f(x: String, xs: List<Int>) {
    var a = 0
    for (x in xs)
        if (x > 0)
            a++
        else
            println("<i>$x</i>")
    println("<p>$x</p>")
}
}

// r4mark/cases: l06_if_else_same_line.kt
object Case230 {
fun f(x: String, xs: List<Int>) {
    var a = 0
    for (x in xs) if (x > 0) a++ else println("<i>$x</i>")
    println("<p>$x</p>")
    for (x in xs) if (x > 0) a++
    else println("<b>$x</b>")
    println("<p>$x</p>")
}
}

// r4mark/cases: l07_if_around.kt
object Case231 {
fun f(x: String, xs: List<Int>, c: Boolean) {
    var n = 0
    if (c)
        for (x in xs) n++
    else
        println("<b>$x</b>")
    if (c) for (x in xs) println(x) else println("<i>$x</i>")
    println("<p>$x</p>")
}
}

// r4mark/cases: l08_when_in_loop.kt
object Case232 {
fun f(x: String, xs: List<Int>) {
    for (x in xs) when (x) {
        1 -> println("<b>$x</b>")
        else -> println("<i>$x</i>")
    }
    println("<p>$x</p>")
}
}

// r4mark/cases: l09_fun_expr_ends.kt
object Case233 {
fun outer(n: String) {
    fun a(n: Int) = n++
    println("<p>$n</p>")
    fun b(n: Int?) = n!!
    println("<p>$n</p>")
    fun c(n: Int) = n.toString()
        .padStart(3)
    println("<p>$n</p>")
    fun d(n: Int) = n
        ?: 0
    println("<p>$n</p>")
    fun e(n: List<Int>) = n as List<Int>
    println("<p>$n</p>")
}
}

// r4mark/cases: l10_value_ends.kt
object Case234 {
fun f(q: Any?, step: Int) {
    val a = q!!
    val b = q as? String
    val c = step
    val d = 1 until
        10
    val e = q is String
    val g = q !is
        String
    println("<p>$a $b $c $d $e $g</p>")
}
}

// r4mark/cases: l11_loop_labelled_and_semis.kt
object Case235 {
fun f(x: String, xs: List<Int>) {
    var n = 0
    outer@ for (x in xs) n++
    println("<p>$x</p>")
    for (x in xs) n++; println("<i>$x</i>")
    for (x in xs) { n++ }
    println("<b>$x</b>")
}
}

// r4mark/cases: l12_while_after_block.kt
object Case236 {
fun f(x: String, xs: List<Int>, c: Boolean) {
    var i = 0
    for (x in xs) if (c) { i++ }
    while (i > 0)
        i--
    println("<p>$x</p>")
    val v = run { 1 }
    while (i < 3)
        i++
    println("<p>$x $v</p>")
}
}

// r4mark/cases: l13_do_no_brace.kt
object Case237 {
fun f(x: String, xs: List<Int>) {
    var n = 0
    for (x in xs) do n++ while (n < x)
    println("<p>$x</p>")
}
}

// r4mark/cases: l15_plus_minus_ops.kt
object Case239 {
fun f(x: String, xs: List<Int>) {
    var n = 0
    for (x in xs) n += x +
        1
    println("<p>$x</p>")
    for (x in xs) n = n - -
        x
    println("<p>$x</p>")
    val a = n +
        +1
    val b = "<p>$a</p>"
}
}

// r4mark/cases2: k01_empty_params_if.kt
object Case240 {
@Language("HTML")
fun f(): String = if (flag) "A <b>x</b>" else "B <i>x</i>"

@Language("HTML")
fun g(n: String) = when {
    n.isEmpty() -> "C <b>x</b>"
    else -> "D <i>x</i>"
}
}

// r4mark/cases2: k02_return_if_elvis.kt
object Case241 {
@Language("HTML")
fun f(x: Boolean): String {
    return if (x) "RetIf <b>x</b>" else "RetElse <i>x</i>"
}

@Language("HTML")
fun g(x: String?): String {
    return x ?: "RetElvis <b>x</b>"
}

@Language("HTML")
fun h(x: Int): String {
    return when (x) {
        1 -> "RetWhen <b>x</b>"
        else -> "RetWhen2 <i>x</i>"
    }
}
}

// r4mark/cases2: k04_default_and_body.kt
object Case243 {
@Language("HTML")
fun f(n: String = "def") = "Body <b>$n</b>"

@Language("HTML")
fun g(n: String = "def2"): String {
    return "Body2 <b>$n</b>"
}
}

// r4mark/cases2: k05_local_marked.kt
object Case244 {
fun outer(m: String) {
    @Language("HTML")
    fun inner(n: String) = "Local <b>$n</b>"

    @Language("HTML")
    val local = "LocalVal <b>$m</b>"
    println(inner(m) + local)
}
}

// r4mark/cases2: k06_override_val.kt
object Case245 {
abstract class B {
    abstract val x: String
}

class C(val n: String) : B() {
    @Language("HTML")
    override val x: String = "Ov <b>$n</b>"

    @Language("HTML")
    override fun toString(): String = "ToS <b>$n</b>"
}
}

// r4mark/cases2: k08_object_expr.kt
object Case247 {
val o = object {
    @Language("HTML")
    fun f(n: String) = "Obj <b>$n</b>"
}

enum class E {
    A;

    @Language("HTML")
    fun f(n: String) = "Enum <b>$n</b>"
}
}

// r4mark/cases2: k09_annotation_multiline.kt
object Case248 {
@Language(
    "HTML",
)
fun f(n: String) = "Multi <b>$n</b>"

@Language(value = "HTML", prefix = "<ul>")
val v = "Prefix <li>x</li>"

@field:Language("HTML")
val w = "Field <b>x</b>"
}

// r4mark/cases2: k10_value_then_marked.kt
object Case249 {
@Language("HTML")
fun f(n: String) = render(n) + "Tail <b>$n</b>"

@Language("HTML")
val g = (n) + "Tail2 <b>x</b>"
}

// r4mark/cases2: l16_typed_chain.kt
object Case250 {
fun f(x: String, xs: List<Int>) {
    for (x: Int in xs) use(x)
        .also { println("<p>$x</p>") }
    for (x: Int in xs)
        if (x > 0) use(x)
        else println("<i>$x</i>")
    for (x: Int in xs) do use(x) while (x < 0)
    println("<b>$x</b>")
}
}

// r5review/cases: d33_header_annot
object Case251 {
fun pick(n: String, xs: List<Int>) {
    for (@Suppress("x") n: Int in xs) println(n)
    println("<b>$n</b>")
    for (n: Int in @Suppress("UNCHECKED_CAST") (xs as List<Int>))
        println(n)
    println("<i>$n</i>")
}
}

// r6review/cases: c09_typeargs.kt
object Case261 {
class T<K, V> {
    val a: List<int> = listOf()
    val b: Foo<in T> = Foo()
    val c: Map<out K, *> = mapOf()
    val d: Array<`Page`> = arrayOf()
    val e: List<_X> = listOf()
    val f: List<(Int) -> Unit> = listOf()
    val g: List<@A Int> = listOf()
    val h: List< Int > = listOf()
    val i = n<a && n>
        b
    val j = n<3 && n>
        b
    val k = n < a && n >
        b
    val l: Map<String, List<Int>> = mapOf()
    val m get() = "x"
    val o: Array<Array<Int>>
        get() = arrayOf()
    val p: List<Int>
    val p2 get() = "<b>x</b>"
    val r = x as List<String>
    val r2 get() = "y"
    val s = x is Map<*, *>
    val s2 get() = "z"
    val t: List< Int >
    val t2 get() = "w"
    val u: Array<in Int>
    val u2 get() = "v"
}
}

// r6review/cases: c11_marks.kt
object Case263 {
annotation class Named(val v: String)
@Language("HTML") fun f1(@Named("x") a: String) = "Hi <b>$a</b>"
@Language("HTML") fun f2(@Named("a(b") a: String) = "Hi <b>$a</b>"
@Language("HTML") fun f3(@Named("a\"b)") a: String) = "Hi <b>$a</b>"
fun g1(@Language("HTML") @Named("a)b") body: String, t: String = "Hi <i>g1</i>") = body + t
fun g2(@Language("HTML") @Named("a\"b(") body: String, t: String = "Hi <i>g2</i>") = body + t
fun g3(@Language("HTML") @Named(v = "(") body: String? = null, t: String = "Hi <i>g3</i>") = body + t
fun g4(@Language("HTML") @field:Named("x") @Suppress("a", "b") body: String, t: String = "Hi <i>g4</i>") = body + t
class C1(@Language("HTML") @get:JvmName("b") private val body: String, val t: String = "Hi <b>C1</b>")
class C2(@Language("HTML") @Named("(") val body: String, val t: String = "Hi <b>C2</b>")
@Language("HTML") @Named("x") @Suppress("y") val v1 = "Hi <b>v1</b>"
@Language("HTML") @Named(v = "a)") fun f4() = "Hi <b>f4</b>"
@Language("HTML") @JvmName("x") fun f5(a: String) = "Hi <b>$a</b>"
@Language("HTML")
@Named("z")
fun f6(@Named("y") a: String, @Named("q") b: String) = "Hi <b>$a $b</b>"
fun g5(@Language("HTML") body: String, @Named("x") t: String = "Hi <i>g5</i>") = body + t
fun g6(@Language("HTML") @Named(v = Foo("x(")) body: String, t: String = "Hi <i>g6</i>") = body + t
fun g7(@Language("HTML") @Named(v = ")") @Named(")") body: String, t: String = "Hi <i>g7</i>") = body + t
@Language("HTML") fun f7(@Named(")") a: String, @Named("(") b: String = "d") = "Hi <b>f7 $a</b>"
@Language("HTML") fun f8(a: String /* "q" */) = "Hi <b>f8 $a</b>"
fun g8(@Language("HTML") /* @Named("x") */ body: String, t: String = "Hi <i>g8</i>") = body + t
fun g9(@Language("HTML") @Named("x") /* c */ body: String, t: String = "Hi <i>g9</i>") = body + t
fun g10(@Language("HTML") @Named("x")
       body: String, t: String = "Hi <i>g10</i>") = body + t
}

// r6review/cases2: d01_marks.kt
object Case266 {
annotation class Named(val v: String)
class C3(@Language("HTML") private val body: String, val t: String = "Hi <b>C3</b>")
fun g11(@Language("HTML") /* c */ body: String, t: String = "Hi <i>g11</i>") = body + t
fun g12(@Language("HTML") @Named("x") // c
        body: String, t: String = "Hi <i>g12</i>") = body + t
fun g13(@Language("HTML") @Named("x") /* c */ body: String, t: String = "Hi <i>g13</i>") = body + t
fun g14(@Language("HTML") @Named("x") @Named("y") body: String, t: String = "Hi <i>g14</i>") = body + t
fun g15(@Language("HTML") @Named("x")body: String, t: String = "Hi <i>g15</i>") = body + t
}

// fresh309-a7f0/a.cases #9
object Case275 {
@RestController
class PageController(private val service: UserService) {
    @GetMapping("/users/{id}", produces = ["text/html"])
    @ResponseBody
    fun user(@PathVariable id: String, @RequestParam(defaultValue = "en") lang: String): String {
        val user = service.find(id)
            ?: return "<p>Not found</p>"
        val name = user.name!!
        val roles = user.roles as List<String>
        for (role in roles) println(role)
        return "<div><h1>$name</h1><p>$lang $roles</p></div>"
    }

    @Language("HTML")
    @GetMapping("/hello")
    @ResponseBody
    fun hello(@RequestParam who: String) = "<p>Hello $who</p>"
}
}

// fresh309-a7f0/a.cases #13
object Case279 {
@Language("HTML")
@Suppress("unused")
@JvmName("x")
val marked: String = "Hi <i>there</i>"
@Language("HTML") @Deprecated("old", ReplaceWith("newer()")) fun old(n: String) = "Old <b>$n</b>"
@Language("HTML")
@Throws(IllegalStateException::class)
fun thrower(n: String): String = "Th <b>$n</b>"
@Language("HTML") @get:JvmName("gx") val gx: String get() = "G <b>x</b>"
@Language("HTML") @field:Suppress("A", "B") var fv = "F <i>v</i>"
}

// fresh309-a7f0/a.cases #14
object Case280 {
class View {
    @Language("HTML")
    @Suppress("FunctionName", "UNUSED_PARAMETER")
    fun Header(title: String, sub: String) = "Head <b>$title</b> $sub"
    fun other(x: String) = "plain $x"
}
}

// fresh309-a7f0/a.cases #15
object Case281 {
fun g(@Language("HTML") @Deprecated("x", ReplaceWith("y")) body: String, title: String = "Hi $x") = body
fun h(@Language("HTML") body: String, title: String = "Hi <b>$x</b>") = body
fun k(@Language("HTML") @Named("b") body: String = "Def <b>$x</b>", t: String = "x") = body
fun m(@Language("HTML") @Named("b") @Size(min = 1) body: String, t: String = "T $x") = body
class C(@Language("HTML") @get:JvmName("b") val body: String = "Cb <b>$x</b>", val t: String = "Ct")
}

// fresh309-a7f0/a.cases #47
object Case313 {
fun outer(n: String) {
    fun inner(n: Int) = @Suppress("x") pad(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #81
object Case347 {
fun outer(name: String) {
    val a = name!!
    @Language("HTML") val b = "<b>$name</b>"
}
}

// fresh309-a7f0/a.cases #117
object Case383 {
@Language("HTML")@Suppress("x")fun f(a: String)="<b>$a</b>"
}

// fresh309-a7f0/a.cases #118
object Case384 {
@Language("HTML")
@Suppress(names = ["a", "b"])
fun f(a: String) = "Arr <b>$a</b>"
}

// fresh309-a7f0/a.cases #119
object Case385 {
// language=HTML
@Suppress("x") val v = "Cm <b>x</b>"
}

// fresh309-a7f0/a.cases #120
object Case386 {
@Language("HTML")
fun f(a: String) = "Fl <b>$a</b>"
}

// fresh309-a7f0/a.cases #121
object Case387 {
class P {
    @Language("HTML")
    @JvmOverloads
    fun f(a: String, b: String = "d") = "Ov <b>$a</b>"
}
}

// fresh309-a7f0/a.cases #122
object Case388 {
@Language("HTML") @Suppress("a") @Deprecated("b") @JvmName("c") @Throws(E::class) @SinceKotlin("1.0") @PublishedApi @JvmStatic @JvmSynthetic @Volatile @Transient @Strictfp @Synchronized fun f() = "Lots <b>x</b>"
}

// fresh309-a7f0/a.cases #123
object Case389 {
fun t(@Language("HTML") @Suppress("x") vararg parts: String, y: String = "Va $x") = parts
}

// fresh309-a7f0/a.cases #124
object Case390 {
fun t(@Language("HTML") @Suppress("x") body: String?, y: String = "Nl $x") = body
}

// fresh309-a7f0/a.cases #125
object Case391 {
fun t(@Language("HTML") @Suppress("x") body: CharSequence, y: String = "Cs $x") = body
}

// fresh309-a7f0/a.cases #126
object Case392 {
fun t(@Language("HTML") @Suppress("x") body: String,
      y: String = "Ml $x") = body
}

// fresh309-a7f0/a.cases #127
object Case393 {
fun t(
    @Language("HTML")
    @Suppress("x")
    body: String,
    y: String = "Ml2 $x",
) = body
}

// fresh309-a7f0/a.cases #128
object Case394 {
val a = listOf(@Language("HTML") @Suppress("x") "Expr <b>a</b>", "two")
}

// fresh309-a7f0/a.cases #129
object Case395 {
fun f() {
    @Language("HTML") @Suppress("UNUSED_VARIABLE") val local = "Lc <b>x</b>"
    @Suppress("A") @Language("HTML") val local2 = "Lc2 <b>x</b>"
}
}

// fresh309-a7f0/a.cases #130
object Case396 {
@Language("HTML") @Foo(@Bar("in") "x") fun f() = "Nest <b>x</b>"
}

// fresh309-a7f0/a.cases #131
object Case397 {
@Language("HTML") @Foo(Bar("in")) fun f() = "Nest2 <b>x</b>"
}

// fresh309-a7f0/a.cases #132
object Case398 {
@Language("HTML") @Foo(value = ")") fun f() = "Paren <b>x</b>"
}

// fresh309-a7f0/a.cases #133
object Case399 {
@Language("HTML") @Foo("""raw "quoted" x""") fun f() = "Raw <b>x</b>"
}

// fresh309-a7f0/a.cases #134
object Case400 {
@Language("HTML") @Foo('"') fun f() = "Ch <b>x</b>"
}

// fresh309-a7f0/a.cases #135
object Case401 {
@Language("HTML") fun f(a: String) = this@Outer.g("Lbl <b>$a</b>")
}

// fresh309-a7f0/a.cases #136
object Case402 {
@Language("HTML") val content: @Composable () -> Unit = { Text("Cmp <b>x</b>") }
}

// fresh309-a7f0/a.cases #138
object Case404 {
@Language("HTML")
fun f(a: String, rows: List<String>): String {
    val head = "<h1>$a</h1>"
    return head + rows.joinToString("") { "<li>$it</li>" }
}
}

// fresh309-a7f0/a.cases #139
object Case405 {
@Suppress("<b>not</b>")
fun f(a: String) = "<b>$a</b>"
}

// fresh309-a7f0/a.cases #140
object Case406 {
@Language("HTML") @Suppress("<b>not</b>") fun f(a: String) = "<b>$a</b>"
}

// fresh309-a7f0/b.cases #51
object Case457 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) @Suppress("x") use(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #106
object Case512 {
class Views {
    @Language("HTML")
    @Suppress("UNUSED")
    override fun toString(): String = "To <b>string</b>"

    @Language("HTML") @Volatile @JvmField
    var page = "Page <b>x</b>"

    // language=HTML
    @Transient
    private val cached = "Cached <b>x</b>"
}
}

// fresh309-a7f0/b.cases #107
object Case513 {
@Language("HTML") @JvmName("a") fun a(x: String) = "<b>$x</b>" + "tail"
@Language("HTML") @JvmName("b") fun b(x: String) = "Mid <b>$x</b>" + "tail"
}

// fresh309-a7f0/b.cases #108
object Case514 {
@Language(value = "HTML") @JvmName("a") fun a(x: String) = "V <b>$x</b>"
@Language("HTML", prefix = "<div>") @JvmName("b") fun b(x: String) = "P <b>$x</b>"
@org.intellij.lang.annotations.Language("HTML") @JvmName("c") fun c(x: String) = "Q <b>$x</b>"
@Language("html") @JvmName("d") fun d(x: String) = "L <b>$x</b>"
}

// fresh309-a7f0/b.cases #109
object Case515 {
@Language("HTML") @JvmName("x") // the name "x"
fun f(a: String) = "Cm2 <b>$a</b>"
}

// fresh309-a7f0/b.cases #110
object Case516 {
@Language("HTML") @JvmName("x") /* "q" */ fun f(a: String) = "Cm3 <b>$a</b>"
}

