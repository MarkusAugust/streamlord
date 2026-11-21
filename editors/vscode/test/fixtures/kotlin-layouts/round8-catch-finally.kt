import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

// review309/cases/c10
object Case2 {
    fun case2() {
catch
    }
}

// review309/cases/loops
object Case42 {
fun list(n: String, rows: List<Int>) {
    for (n in rows) println(n)
    println("<p>$n</p>")
}
fun list2(n: String, rows: List<Int>) {
    for (n in rows)
        println(n)
    println("<p>$n</p>")
}
fun list3(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) println(n) else println(-n)
    println("<p>$n</p>")
}
fun list4(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0)
        println(n)
    else
        println(-n)
    println("<p>$n</p>")
}
fun list5(n: String, rows: List<Int>) {
    for (n in rows) when (n) {
        1 -> println(n)
        else -> {}
    }
    println("<p>$n</p>")
}
fun list6(n: String, rows: List<Int>) {
    for (n in rows) rows
        .filter { it > n }
        .forEach { println(it) }
    println("<p>$n</p>")
}
fun list7(n: String, rows: List<Int>) {
    for ((n, i) in rows.withIndex()) println(n + i)
    println("<p>$n</p>")
}
fun list8(n: String, rows: List<Int>) {
    for (n in rows.filter { it > 0 }) println(n)
    println("<p>$n</p>")
}
fun list9(n: String, rows: List<Int>) {
    for (n in rows.filter {
        it > 0
    }) println(n)
    println("<p>$n</p>")
}
fun list10(n: String, rows: List<Int>) {
    for (n in rows) for (m in rows) println(n + m)
    println("<p>$n</p>")
}
fun list11(n: String, rows: List<Int>) {
    for (n in rows) "<i>$n</i>".also(::println)
    println("<p>$n</p>")
}
fun list12(n: String, rows: List<Int>) {
    for (n in rows) total += n
    println("<p>$n</p>")
}
fun list13(n: String, rows: List<Int>) {
    for (n in rows) total +=
        n
    println("<p>$n</p>")
}
fun list14(n: String, rows: List<Int>) {
    for (n in rows) (n + 1).let(::println)
    println("<p>$n</p>")
}
fun list15(n: String, rows: List<Int>) {
    for (n in rows) { println(n) }
    println("<p>$n</p>")
}
fun list16(n: String, rows: List<Int>) {
    for (n in rows)
    {
        println(n)
    }
    println("<p>$n</p>")
}
fun list17(n: String, rows: List<Int>) {
    outer@ for (n in rows) if (n > 1) break@outer
    println("<p>$n</p>")
}
fun list18(n: String, rows: List<Int>) {
    for (n in rows) // each
        println(n)
    println("<p>$n</p>")
}
fun list19(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) if (n > 1) println(n) else println(0)
    else println("<b>$n</b>")
    println("<p>$n</p>")
}
fun list20(n: String, rows: List<Int>) {
    while (rows.isEmpty()) println(n)
    for (n in rows) println(n); println("<p>$n</p>")
}
fun list21(n: String, rows: List<Int>) {
    for (n in rows) println(n) // trailing "comment"
    println("<p>$n</p>")
}
fun list22(n: String, rows: List<Int>) {
    for (n in rows) n.let {
        println(it)
    }
    println("<p>$n</p>")
}
fun list23(n: String, rows: List<Int>) {
    for (n in rows) n.let { println(it) }
    println("<p>$n</p>")
}
fun list24(n: String, rows: List<Int>) {
    for (n in rows) -n
    println("<p>$n</p>")
}
fun list25(n: String, rows: List<Int>) {
    for (n in
        rows) println(n)
    println("<p>$n</p>")
}
fun list26(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) {
        println(n)
    } else {
        println(-n)
    }
    println("<p>$n</p>")
}
fun list27(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) {
        println(n)
    }
    else {
        println(-n)
    }
    println("<p>$n</p>")
}
fun list28(n: String, rows: List<Int>, ok: Boolean) {
    if (ok) for (n in rows) println(n) else println("<b>$n</b>")
    println("<p>$n</p>")
}
fun list29(n: String, rows: List<Int>) {
    for (n in rows) println(n)
    "<p>$n</p>".let(::println)
}
fun list30(n: String, rows: List<Int>) {
    for (n in rows) println(n) +
        1
    println("<p>$n</p>")
}
fun list31(n: String, rows: List<Int>) {
    for (n in rows) println(
        n)
    println("<p>$n</p>")
}
fun list32(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) println(n)
    else if (n < 0) println(-n)
    else println(0)
    println("<p>$n</p>")
}
fun list33(n: String, rows: List<Int>) {
    for (n in rows) try { println(n) }
    catch (e: Exception) { }
    println("<p>$n</p>")
}
fun list34(n: String, rows: List<Int>) {
    for (n in rows) n to
        n
    println("<p>$n</p>")
}
fun list35(n: String, rows: List<Int>) {
    for (n in rows) @Suppress("x") println(n)
    println("<p>$n</p>")
}
fun list36(n: String, rows: List<Int>) {
    for (n in rows) /* c */ println(n)
    println("<p>$n</p>")
}
fun list37(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) println(n)
    println("<p>$n</p>")
}
fun list38(n: String, rows: List<Int>) {
    for (n in rows) when {
        n > 0 -> println(n)
    }
    println("<p>$n</p>")
}
fun list39(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0)
        println(n)
    println("<p>$n</p>")
}
fun list40(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) println(n) else if (n < 0)
        println(n)
    println("<p>$n</p>")
}
fun list41(n: String, rows: List<Int>) {
    for (n in rows) println(if (n > 0) "a" else "b")
    println("<p>$n</p>")
}
fun list42(n: String, rows: List<Int>) {
    for (n in rows) println("${if (n > 0) "a" else "b"}")
    println("<p>$n</p>")
}
fun list43(n: String, rows: List<Int>) {
    for (n in rows) total += if (n > 0)
        n
    else
        0
    println("<p>$n</p>")
}
fun list44(n: String, rows: List<Int>) {
    for (n in rows) iff(n)
    else2(n)
    println("<p>$n</p>")
}
}

// review309/cases3/loops_typed
object Case58 {
fun list(n: String, rows: List<Int>) {
    for (n: Int in rows) println(n)
    println("<p>$n</p>")
}
fun list2(n: String, rows: List<Int>) {
    for (n: Int in rows)
        println(n)
    println("<p>$n</p>")
}
fun list3(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) println(n) else println(-n)
    println("<p>$n</p>")
}
fun list4(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0)
        println(n)
    else
        println(-n)
    println("<p>$n</p>")
}
fun list5(n: String, rows: List<Int>) {
    for (n: Int in rows) when (n) {
        1 -> println(n)
        else -> {}
    }
    println("<p>$n</p>")
}
fun list6(n: String, rows: List<Int>) {
    for (n: Int in rows) rows
        .filter { it > n }
        .forEach { println(it) }
    println("<p>$n</p>")
}
fun list7(n: String, rows: List<Int>) {
    for ((n, i) in rows.withIndex()) println(n + i)
    println("<p>$n</p>")
}
fun list8(n: String, rows: List<Int>) {
    for (n: Int in rows.filter { it > 0 }) println(n)
    println("<p>$n</p>")
}
fun list9(n: String, rows: List<Int>) {
    for (n: Int in rows.filter {
        it > 0
    }) println(n)
    println("<p>$n</p>")
}
fun list10(n: String, rows: List<Int>) {
    for (n: Int in rows) for (m in rows) println(n + m)
    println("<p>$n</p>")
}
fun list11(n: String, rows: List<Int>) {
    for (n: Int in rows) "<i>$n</i>".also(::println)
    println("<p>$n</p>")
}
fun list12(n: String, rows: List<Int>) {
    for (n: Int in rows) total += n
    println("<p>$n</p>")
}
fun list13(n: String, rows: List<Int>) {
    for (n: Int in rows) total +=
        n
    println("<p>$n</p>")
}
fun list14(n: String, rows: List<Int>) {
    for (n: Int in rows) (n + 1).let(::println)
    println("<p>$n</p>")
}
fun list15(n: String, rows: List<Int>) {
    for (n: Int in rows) { println(n) }
    println("<p>$n</p>")
}
fun list16(n: String, rows: List<Int>) {
    for (n: Int in rows)
    {
        println(n)
    }
    println("<p>$n</p>")
}
fun list17(n: String, rows: List<Int>) {
    outer@ for (n: Int in rows) if (n > 1) break@outer
    println("<p>$n</p>")
}
fun list18(n: String, rows: List<Int>) {
    for (n: Int in rows) // each
        println(n)
    println("<p>$n</p>")
}
fun list19(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) if (n > 1) println(n) else println(0)
    else println("<b>$n</b>")
    println("<p>$n</p>")
}
fun list20(n: String, rows: List<Int>) {
    while (rows.isEmpty()) println(n)
    for (n: Int in rows) println(n); println("<p>$n</p>")
}
fun list21(n: String, rows: List<Int>) {
    for (n: Int in rows) println(n) // trailing "comment"
    println("<p>$n</p>")
}
fun list22(n: String, rows: List<Int>) {
    for (n: Int in rows) n.let {
        println(it)
    }
    println("<p>$n</p>")
}
fun list23(n: String, rows: List<Int>) {
    for (n: Int in rows) n.let { println(it) }
    println("<p>$n</p>")
}
fun list24(n: String, rows: List<Int>) {
    for (n: Int in rows) -n
    println("<p>$n</p>")
}
fun list25(n: String, rows: List<Int>) {
    for (n: Int in
        rows) println(n)
    println("<p>$n</p>")
}
fun list26(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) {
        println(n)
    } else {
        println(-n)
    }
    println("<p>$n</p>")
}
fun list27(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) {
        println(n)
    }
    else {
        println(-n)
    }
    println("<p>$n</p>")
}
fun list28(n: String, rows: List<Int>, ok: Boolean) {
    if (ok) for (n: Int in rows) println(n) else println("<b>$n</b>")
    println("<p>$n</p>")
}
fun list29(n: String, rows: List<Int>) {
    for (n: Int in rows) println(n)
    "<p>$n</p>".let(::println)
}
fun list30(n: String, rows: List<Int>) {
    for (n: Int in rows) println(n) +
        1
    println("<p>$n</p>")
}
fun list31(n: String, rows: List<Int>) {
    for (n: Int in rows) println(
        n)
    println("<p>$n</p>")
}
fun list32(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) println(n)
    else if (n < 0) println(-n)
    else println(0)
    println("<p>$n</p>")
}
fun list33(n: String, rows: List<Int>) {
    for (n: Int in rows) try { println(n) }
    catch (e: Exception) { }
    println("<p>$n</p>")
}
fun list34(n: String, rows: List<Int>) {
    for (n: Int in rows) n to
        n
    println("<p>$n</p>")
}
fun list35(n: String, rows: List<Int>) {
    for (n: Int in rows) @Suppress("x") println(n)
    println("<p>$n</p>")
}
fun list36(n: String, rows: List<Int>) {
    for (n: Int in rows) /* c */ println(n)
    println("<p>$n</p>")
}
fun list37(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) println(n)
    println("<p>$n</p>")
}
fun list38(n: String, rows: List<Int>) {
    for (n: Int in rows) when {
        n > 0 -> println(n)
    }
    println("<p>$n</p>")
}
fun list39(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0)
        println(n)
    println("<p>$n</p>")
}
fun list40(n: String, rows: List<Int>) {
    for (n: Int in rows) if (n > 0) println(n) else if (n < 0)
        println(n)
    println("<p>$n</p>")
}
fun list41(n: String, rows: List<Int>) {
    for (n: Int in rows) println(if (n > 0) "a" else "b")
    println("<p>$n</p>")
}
fun list42(n: String, rows: List<Int>) {
    for (n: Int in rows) println("${if (n > 0) "a" else "b"}")
    println("<p>$n</p>")
}
fun list43(n: String, rows: List<Int>) {
    for (n: Int in rows) total += if (n > 0)
        n
    else
        0
    println("<p>$n</p>")
}
fun list44(n: String, rows: List<Int>) {
    for (n: Int in rows) iff(n)
    else2(n)
    println("<p>$n</p>")
}
}

// review309/cases4/w_dot_catch
object Case70 {
fun f(flow: Flow<Int>) {
    val safe = flow.catch(::handle)
    val title = "<b>x</b>"
}
fun outer(n: String, flow: Flow<Int>) {
    fun g(n: Int) = flow.catch(::handle)
    println("<p>$n</p>")
}
}

// r8-review/hand/H2Exprs
object Case76 {
fun outer1(): String {
    fun size(n: Int) = n to
        n
    val n = "x"
    return "<p>$n</p>"
}

fun outer2(): String {
    fun size(n: Int) = /* c */ n
    val n = "x"
    return "<p>$n</p>"
}

fun outer3(): String {
    fun size(n: Int) = n.inc() and
        1
    val n = "x"
    return "<p>$n</p>"
}

fun outer4(): String {
    fun size(n: Int) = @Suppress("x") n
    val k = "x"
    return "<p>$n $k</p>"
}

fun outer5(xs: List<Int>): String {
    for (n in xs) total
    val m = "x"
    return "<p>$n $m</p>"
}

fun outer6(): String {
    fun size(n: Int) = try {
        n
    } catch (e: Exception) {
        0
    }
    val n = "x"
    return "<p>$n</p>"
}

fun outer7(): String {
    fun size(n: Int) = try {
        n
    }
    // comment
    finally {
        println()
    }
    val n = "x"
    return "<p>$n</p>"
}

fun outer8(): String {
    fun size(n: Int) = try
    {
        n
    }
    catch (e: Exception)
    {
        0
    }
    val n = "x"
    return "<p>$n</p>"
}

fun outer9(): String {
    fun size(n: Int) = run {
        n
    }
    finally()
    val n = "x"
    return "<p>$n</p>"
}

fun outer10(): String {
    fun size(n: Int) = run {
        n
    }
    catch(n)
    return "<p>$n</p>"
}

fun outer11(): String {
    fun size(n: Int) = try { n } catch (e: Exception) { 0 } finally { }
    val n = "x"
    return "<p>$n</p>"
}

fun outer12(): String {
    fun size(n: Int) = try {
        n
    } /* c */ catch /* d */ (e: Exception) {
        0
    }
    val n = "x"
    return "<p>$n</p>"
}

fun outer13(): String {
    fun size(n: Int) = if (n > 0) {
        n
    } else {
        0
    }
    finally {
    }
    return "<p>$n</p>"
}

fun outer14(): String {
    fun size(n: Int) = n
    /* c */ "<p>$n</p>".also(::println)
    return "<p>$n</p>"
}

fun outer15(): String {
    fun size(n: Int) = n
    /* c */"<p>$n</p>".also(::println)
    return "<p>$n</p>"
}

fun outer16(): String {
    fun size(n: Int) = n
    // c
    .plus(1)
    return "<p>$n</p>"
}

fun outer17(): String {
    fun size(n: Int) = n
    "<p>$n</p>".also(::println)
    return "<p>$n</p>"
}

fun outer18(): String {
    fun size(n: Int) = n until
        3
    return "<p>$n</p>"
}

fun outer19(): String {
    fun size(n: Int) = n downTo 0
    return "<p>$n</p>"
}

fun outer20(): String {
    fun size(n: Int) = "a" to "b"
    return "<p>$n</p>"
}

fun outer21(): String {
    fun size(n: Int) = 'a' + n
    return "<p>$n</p>"
}

fun outer22(): String {
    fun size(n: Int) = arrayOf(n)[0] foo
        1
    return "<p>$n</p>"
}

fun outer23(): String {
    fun size(n: Int) = this@Something
    return "<p>$n</p>"
}

fun outer24(): String {
    fun size(n: Int) = super.n
    return "<p>$n</p>"
}

fun outer25(): String {
    fun size(n: Int) = `my name`
    return "<p>$n</p>"
}

fun outer26(): String {
    fun size(n: Int) = n `my infix`
        1
    return "<p>$n</p>"
}

fun outer27(): String {
    fun size(n: Int) = n.let { it } ushr
        1
    return "<p>$n</p>"
}

fun outer28(): String {
    fun size(n: Int) = "${n}" + x
    return "<p>$n</p>"
}

fun outer29(): String {
    fun size(n: Int) = n as Int
    return "<p>$n</p>"
}

fun outer30(): String {
    fun size(n: Int) = n is Int
    return "<p>$n</p>"
}
}

// r8-review/hand/H6More
object Case80 {
fun caught(flow: Flow<Int>, handler: suspend (Throwable) -> Unit, name: String): String {
    fun guarded(n: Int) = flow.catch(handler)
    println("<p>$n</p>")
    val f = flow.catch(handler)
    val g = "<b>x</b>"
    return "<p>$f $g $name</p>"
}

class Caught(val flow: Flow<Int>, val handler: suspend (Throwable) -> Unit) {
    val f = flow.catch(handler)
        get() = flow

    val g = catch(handler)
        get() = flow
}

fun bang(x: String?, name: String): String {
    fun pair(n: Int) = x!! to n
    println("<p>$n</p>")
    val p = x!! to name
    val q = "<b>q</b>"
    return "<p>$p $q</p>"
}

class Bang(val x: String?) {
    val p = x!! to x
        get() = "a" to "b"
}

fun returns(ok: Boolean, name: String): String {
    if (ok)
        return "<b>x</b>"
    else
        return name
}

fun useReturns(name: String) = "<p>${returns(true, name)}</p>"

fun templ(name: String): String {
    val a = "${name}" + name
    val b = "<b>$name</b>"
    return "<p>$a $b</p>"
}

fun crlfLike(a: Int, b: Int): String {
    val p = a to b
    val q = p.first + 1
    return "<p>$p $q</p>"
}

fun labels(xs: List<Int>): String {
    var s = 0
    loop@ for (x in xs) {
        if (x > 2) break@loop
        if (x > 1) continue@loop
        s += x
    }
    val t = this@labels
    return "<p>$s</p>"
}
}

