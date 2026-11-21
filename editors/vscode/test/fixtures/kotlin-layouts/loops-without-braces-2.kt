import io.github.markusaugust.streamlord.core.domain.*
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import io.github.markusaugust.streamlord.html.*

// fresh309-a7f0/a.cases #24
object Case290 {
fun f(xs: Map<String, Int>, k: Int, v: Int) {
    for ((k, v) in xs) println("$k $v")
    println("<p>$k $v</p>")
}
}

// fresh309-a7f0/a.cases #25
object Case291 {
fun f(xs: List<Int>, x: String) {
    outer@ for (x: Int in xs) {
        if (x > 0) continue@outer
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #26
object Case292 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs)
    {
        println(x)
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #27
object Case293 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) "lit $x".let(::println)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #28
object Case294 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) x
        .toString()
        .let(::println)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #29
object Case295 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) println(x) ; println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #30
object Case296 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs.filter { it > 0 }.map { it * 2 }) println(x)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #31
object Case297 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs.filter { y ->
        y > 0
    }) println(x)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #32
object Case298 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) {
        println(x)
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #70
object Case336 {
fun outer(n: String) {
    fun inner(n: Int) = do2(n)
    while (n.isEmpty()) println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #71
object Case337 {
fun outer(n: String, k: Int) {
    var m = k
    do m-- while (m > 0)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #72
object Case338 {
fun outer(n: String, k: Int) {
    val x = k
    while (x > 0)
        println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #96
object Case362 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) if (x > 0) {
        println(x)
    } else {
        println(-x)
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #97
object Case363 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) if (x > 0) {
        println(x)
    }
    else {
        println(-x)
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #98
object Case364 {
fun f(xs: List<Int>, x: String, c: Boolean) {
    if (c) for (x: Int in xs) if (x > 0) {
        println(x)
    }
    else {
        println("<p>$x</p>")
    }
}
}

// fresh309-a7f0/a.cases #99
object Case365 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) if (x > 0) println("a") else if (x < 0) println("b")
    else println("c")
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #101
object Case367 {
fun f(xs: List<String>, x: Int) {
    for (x in xs) if (x.isNotEmpty())
        println("<i>$x</i>")
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #102
object Case368 {
fun f(
    xs: List<String>, x: Int) {
    for (x in xs)
        for (y in x.split(","))
            println(y)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #103
object Case369 {
fun f(xs: List<String>, x: Int) {
    for (x in xs) println(x); for (y in xs) println(y)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #104
object Case370 {
fun f(xs: List<String>, verified: Int) {
    for (x in xs)
        verified(x)
    println("<p>$verified</p>")
}
}

// fresh309-a7f0/a.cases #105
object Case371 {
fun f(xs: List<String>, x: Int) {
    for (x in xs) notif(x)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/b.cases #5
object Case411 {
fun outer(x: String, nums: List<Int>) {
    var total = 0
    for (x: Int in nums) total += x
    catching("<p>$x</p>")
}
}

// fresh309-a7f0/b.cases #11
object Case417 {
fun outer(x: String, xs: List<Int>) = interpolate {
    for (x: Int in xs) log(x)
    "<p>${+x}</p>"
}
}

// fresh309-a7f0/b.cases #45
object Case451 {
fun outer(n: String, xs: List<Int>) {
    for (i in xs.indices) println(xs[i])
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #46
object Case452 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) xs[n] = 0
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #47
object Case453 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) (n + 1).let(::println)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #48
object Case454 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) { println(n) }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #49
object Case455 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) -n
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #50
object Case456 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) ++count
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #52
object Case458 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) when {
        n > 0 -> use(n)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #53
object Case459 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) if (n > 0) use(n) else if (n < 0) use(-n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #54
object Case460 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) if (n > 0)
        use(n)
    else if (n < 0)
        use(-n)
    else
        use(0)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #55
object Case461 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs)
        if (n > 0) use(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #56
object Case462 {
fun outer(n: String, xs: List<Int>, ok: Boolean) {
    if (ok) for (n in xs) use(n) else println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #57
object Case463 {
fun outer(n: String, xs: List<Int>, ok: Boolean) {
    val a = if (ok) for (n in xs) use(n)
    else println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #58
object Case464 {
fun outer(n: String, xs: List<Int>, k: Int) {
    when (k) {
        1 -> for (n in xs) use(n)
        else -> println("<p>$n</p>")
    }
}
}

// fresh309-a7f0/b.cases #59
object Case465 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) while (n > 0) use(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #60
object Case466 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) use(n).also {
        println(it)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #61
object Case467 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) use(
        n,
    )
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #62
object Case468 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) total = total +
        n
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #63
object Case469 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs) "x$n".also(::println)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #64
object Case470 {
fun outer(n: String, xs: List<Int>) {
    for (n
         in xs) use(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #65
object Case471 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs.filter { it > 0 }) {
        use(n)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #66
object Case472 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs.filter { it > 0 }) use(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #67
object Case473 {
fun outer(n: String, xs: List<Int>) {
    for (n in xs.map { v -> v * 2 }.filter { it > 0 })
        use(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #68
object Case474 {
fun outer(n: String, xs: List<Int>) {
    for (n: Int in xs) use(n)
}
fun after(n: String) = "<p>$n</p>"
}

// fresh309-a7f0/b.cases #69
object Case475 {
fun outer(n: String, xs: List<Int>) {
    for (n: Int in xs) use(n) }
val x = "<p>$x</p>"
}

// fresh309-a7f0/b.cases #70
object Case476 {
class A(val n: String, val xs: List<Int>) {
    init {
        for (n in xs) println(n)
        println("<p>$n</p>")
    }
}
}

// fresh309-a7f0/b.cases #71
object Case477 {
fun outer(n: String, xs: List<Int>) = buildString {
    for (n in xs) append(n)
    append("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #72
object Case478 {
fun outer(n: String, xs: List<Int>) = buildString {
    for (n in xs) append(n)
}
fun f(n: String) = "<p>$n</p>"
}

// fresh309-a7f0/b.cases #73
object Case479 {
fun FlowContent.list(title: String, rows: List<Int>) {
    for (title in rows) span { +"$title" }
    h1 { +title }
    div { unsafe { +"<b>$title</b>" } }
}
}

// fresh309-a7f0/b.cases #91
object Case497 {
fun f(name: String) {
    val w = while (true)
        break
    val title = "<p>$name $w</p>"
}
}

// fresh309-a7f0/b.cases #92
object Case498 {
fun f(name: String) {
    do {
        println(name)
    } while (name.isEmpty())
    val title = "<p>$name</p>"
}
}

// fresh309-a7f0/b.cases #93
object Case499 {
fun f(name: String) {
    val x = 1
    do {
        println(name)
    }
    while (name.isEmpty())
    val title = "<p>$name</p>"
}
}

// fresh309-a7f0/b.cases #96
object Case502 {
fun f(name: String) {
    val list = listOf(1) as List<Int>
    for (i in list) println(i)
    val title = "<p>$name</p>"
}
}

// fresh309-a7f0/b.cases #120
object Case526 {
fun f() {
    val label = loop@ for (i in 0..3) { if (i > 1) break@loop }
    val a = "x@y(\"<b>z</b>\")"
}
}

// review-309/app.kt: interpolate
object Case530 {
fun card(title: String, count: Int) = interpolate {
    val n = count!!
    "<div><h2>${+title}</h2><p>${+n}</p></div>"
}

fun list(items: List<String>) =
    interpolate {
        for (item in items)
            "<li>${+item}</li>"
    }

fun outer(name: String) {
    fun inner(name: Int) = interpolate { "<b>${+name}</b>" }
    println("<p>$name</p>")
}
}

// review-309/app.kt: dsl
object Case531 {
fun page(title: String, items: List<String>) =
    createHTML().html {
        body {
            h1 { +title }
            ul {
                for (item in items)
                    li { +item }
            }
            div {
                dataSignals("count" to 0)
                dataOn("click", "\$count++")
            }
        }
    }

fun FlowContent.counter(n: Int) = div {
    dataText("\$count")
    +"$n"
}
val greeting = "<b>${page("x", listOf())}</b>"
}

// review-309/lines.kt: increment
object Case544 {
fun f(nums: List<Int>) {
    var n = 0
    for (x in nums) n++
    val title = "<b>$n</b>"
}
}

// review-309/lines.kt: do-while
object Case556 {
fun f(n: Int) {
    var k = n
    do {
        k--
    } while (k > 0)
    val t = "<b>$k</b>"
}
}

