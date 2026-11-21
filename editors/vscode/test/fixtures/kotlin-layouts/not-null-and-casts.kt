import io.github.markusaugust.streamlord.core.domain.*

// rev309/cases1: b296_bangs
object Case20 {
fun f(q: Any?) {
    val a = q!!
    val title = "<b>x</b>"
}
}

// rev309/cases1: b296_lambda_type_val
object Case27 {
fun f() {
    val g: (Int) -> List<String> = { listOf() }
    val h = g as (Int) -> List<String>
    val t = "<b>$h</b>"
}
}

// rev309/cases1: b296_as_q
object Case32 {
fun f(n: Any): String {
    val a = n as?
        String
    return "<p>$a</p>"
}
}

// rev309/cases2: r_nullable_chain
object Case86 {
fun render(user: User?): String {
    val name = user?.profile?.name
        ?: "anonymous"
    val email = user!!.email!!
    val age = user.age as Int?
    val tags = user.tags as List<String>?
    return "<p>$name $email $age $tags</p>"
}
}

// rev309/cases2: r_expression_bodies
object Case89 {
class Svc(val repo: Repo) {
    fun one(id: Long) = repo.find(id)
    fun two(id: Long) =
        repo.find(id)
            ?.name
            ?: "none"
    fun three(id: Long): String = when (val x = repo.find(id)) {
        null -> "none"
        else -> x.name
    }
    fun four(id: Long) = if (id > 0)
        "pos"
    else
        "neg"
    fun five(id: Long) = repo.all()
        .filter { it.id == id }
        .map { it.name }
    fun six(name: String) = "<b>$name</b>"
}
}

// rev309/cases2: r_property_get_after_typearg
object Case95 {
class A(private val m: Map<String, Any>) {
    val names = m["names"] as List<String>
    val title: String
        get() = "<h1>x</h1>"
}
}

// rev309/cases2: r_property_after_bang
object Case96 {
class A(private val m: Map<String, Any>) {
    val names = m["names"]!!
    val title: String
        get() = "<h1>x</h1>"
}
}

// rev309/cases3: o_bang_eof
object Case104 {
val a = q!!
}

// rev309/cases3: o_lambda_type_eol
object Case122 {
fun f() {
    val g = mutableMapOf<String, () -> Int>()
    val h = g as Map<String, () -> Int>
    val q = "<b>$h</b>"
}
}

// rev309/cases3: o_template_code_end
object Case128 {
fun f(n: String, q: Any?) {
    val a = "${q!!}"
    val b = "<p>$a</p>"
}
}

// rev309/cases4: p_expr_nullable_return
object Case164 {
fun outer(x: String) {
    fun cast(x: Any) = x as String?
    println("<p>$x</p>")
}
}

// rev309/cases4: p_expr_bang
object Case165 {
fun outer(x: String) {
    fun get(x: String?) = x!!
    println("<p>$x</p>")
}
}

// rev309/cases4: p_when_branches_vals
object Case177 {
fun f(x: Any, title: String): String {
    when (x) {
        is String -> {
            val a = x as String?
            println(a)
        }
        else -> {}
    }
    return "<p>$title</p>"
}
}

// r6review/cases: c08_nullable.kt
object Case260 {
fun page(name: String): String {
    val a: Int?
        get() = 1
    val b = name as?
        String
    val c = name.length ?:
        0
    val d = name?.
        length
    val e: List<String>?
    return "<p>$name</p>"
}
class P(val q: String?) {
    val a: Int? = null
    val g get() = "x"
    val r = q as? String
    val s get() = q
}
}

// fresh309-a7f0/a.cases #2
object Case270 {
fun f(q: Any?, name: String) {
    val a = q!!
    val title = "<b>$name</b>"
    println(a)
}
}

// fresh309-a7f0/a.cases #12
object Case278 {
fun card(name: String, count: Int) = interpolate {
    val total = count!!
    "<div><b>${+name}</b> $total</div>"
}
fun row(n: String) = interpolate { "<li>${+n}</li>" }
fun bad(n: String) = "<li>$n</li>"
}

// fresh309-a7f0/a.cases #42
object Case308 {
fun outer(n: String) {
    fun inner(n: Int) = n as? Int
        ?: 0
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #63
object Case329 {
fun outer(n: String) {
    fun inner(n: Int) = listOf(n) as List<Int>
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #64
object Case330 {
fun outer(n: String) {
    fun inner(n: Int) = listOf(n) as List<Map<String, Int?>>
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #65
object Case331 {
fun outer(n: String) {
    fun inner(n: Int) = n.takeIf { it > 0 } as Int?
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #66
object Case332 {
fun outer(n: String) {
    fun inner(n: Int) = n as Comparable<*>
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #67
object Case333 {
fun outer(n: String) {
    fun inner(n: Int) = n as (Int) -> Unit
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #82
object Case348 {
fun f(s: String?) {
    val a = s!!
        .trim()
    val b = s?.length
        ?: 0
    val c = s ?:
        "x"
    val d = s as String?
    val e = s
        ?.length
    println("<p>$a $b $c $d $e</p>")
}
}

// fresh309-a7f0/a.cases #83
object Case349 {
class A {
    val x: String = load()!!
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #84
object Case350 {
class A {
    val x = load() as String?
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #85
object Case351 {
class A {
    val x = load() as List<String>
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #90
object Case356 {
class A(val name: String) {
    val upper: String
        get() = name.uppercase()!!
    val html: String
        get() = "<p>$upper</p>"
}
}

// fresh309-a7f0/a.cases #93
object Case359 {
fun f(a: Map<String, List<Int>>?): String {
    val m = a!!
    val first: List<Int> = m["x"] as List<Int>
    val name = m.keys.first()
    return "<p>$name $first</p>"
}
}

// fresh309-a7f0/a.cases #107
object Case373 {
fun x(list: List<Int>, name: String) {
    val v = list.filter { it > 0 } as List<Int>
    println("<p>$name $v</p>")
}
}

// fresh309-a7f0/b.cases #33
object Case439 {
fun outer(n: String) {
    fun inner(n: Int) = n
        ?.toString()
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #39
object Case445 {
fun outer(n: String) {
    fun inner(n: Int) = n as
        Int
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #75
object Case481 {
fun f(a: List<Int>, name: String) {
    val sorted = a.sortedWith(compareBy<Int> { it })
    val by = a.groupBy { it % 2 }
    val greater = a.filter { it > 1 } as
        List<Int>
    println("<p>$name $sorted $by $greater</p>")
}
}

// fresh309-a7f0/b.cases #79
object Case485 {
fun f(cb: () -> Unit, name: String) {
    val e: () -> Unit = cb
    val g = cb as () -> Unit
    val h = cb as (() -> Unit)?
    println("<p>$name $e $g $h</p>")
}
}

// fresh309-a7f0/b.cases #97
object Case503 {
fun f(name: String?) {
    val list = name!!
    val title = "<p>$list</p>"
}
}

// fresh309-a7f0/b.cases #98
object Case504 {
fun f(name: String?) {
    val bad = name!!.length!!
    val title = "<p>$bad</p>"
}
}

// fresh309-a7f0/b.cases #99
object Case505 {
fun f(name: String?) {
    val x = (name ?: "") as CharSequence?
    val title = "<p>$x</p>"
}
}

// fresh309-a7f0/b.cases #100
object Case506 {
fun f(name: String) {
    val x = name as `My Type`
    val title = "<p>$x</p>"
}
}

// fresh309-a7f0/b.cases #101
object Case507 {
fun f(name: String) {
    val x = name as `in`
        .foo
    val title = "<p>$x</p>"
}
}

// fresh309-a7f0/b.cases #104
object Case510 {
fun f(name: String) {
    val x = name!!; val y = name as String?; val z = 1
    val title = "<p>$x</p>"
}
}

// fresh309-a7f0/b.cases #105
object Case511 {
fun f(name: String) {
    val x = listOf(1).map { it }
        .filter { it > 0 } as List<Int>
    val title = "<p>$x $name</p>"
}
}

// review-309/lines.kt: bangbang
object Case533 {
fun f(q: String?) {
    val a = q!!
    val title = "<b>$a</b>"
}
}

// review-309/lines.kt: nullable-type
object Case535 {
fun f(q: Any?) {
    val a = q as String?
    val title = "<b>$a</b>"
}
}

// review-309/lines.kt: as-end
object Case548 {
fun f(q: Any) {
    val a = q as
        String
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: crlf
object Case593 {
fun f(q: String?) {
    val a = q!!
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: semicolons
object Case594 {
fun f(q: String?) { val a = q!!; val t = "<b>$a</b>" }
}

// r5review/cases: d12_values
object Case713 {
fun f(q: Any?, b: Boolean, n: Int) {
    val a = if (b)
        1
    else
        2
    val c = q as
        String
    val d = n in
        listOf(1)
    val e = q is
        String
    val g = n++
    val h = q as? String
    val i = if (b) 1 else
        2
    val j = when (n)
    {
        1 -> "a"
        else -> "b"
    }
    val k = a<b && c >
        d
    val l = q as?
        String
    val title = "<b>x</b>"
}
}

// r7/probes6: h01-nullable-receiver
object Case850 {
fun a(n: Int) = b
fun ((Int) -> Unit)?.call(n: Int) = "<b>$n</b>"
fun page(n: String) = "<p>$n</p>"
}

