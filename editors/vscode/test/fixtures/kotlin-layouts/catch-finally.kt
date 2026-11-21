import io.github.markusaugust.streamlord.core.domain.*
import org.intellij.lang.annotations.Language
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// rev309/cases1: b296_try_catch
object Case33 {
fun f(n: String): String {
    val a = try {
        n.toInt()
    }
    catch (e: Exception) {
        0
    }
    finally {
        println("x")
    }
    return "<p>$a</p>"
}
}

// rev309/cases1: c283_expr_try
object Case55 {
fun outer(n: String) {
    fun parse(n: Int) = try {
        n.toString()
    } catch (e: Exception) {
        "<i>$n</i>"
    }
    println("<p>$n</p>")
}
}

// rev309/cases2: r_spring_controller
object Case79 {
@RestController
@RequestMapping("/orders")
class OrderController(
    private val service: OrderService,
    @Value("\${app.title}") private val title: String,
) {
    @GetMapping("/{id}", produces = ["text/html"])
    fun order(@PathVariable("id") id: Long, @RequestParam(name = "q", required = false) q: String?): ResponseEntity<String> {
        val order = service.find(id)
            ?: return ResponseEntity.notFound().build()
        val lines = order.lines!!
        val count = lines.size
        for (line in lines) println(line)
        val total = lines.sumOf { it.amount } as Comparable<Long>
        @Language("HTML")
        val html = "<h1>$title</h1><p>${order.name}</p><p>$count $q</p>"
        return ResponseEntity.ok(html)
    }

    @Language("HTML")
    @GetMapping("/fragment")
    fun fragment(name: String) = "<div id=\"x\">$name</div>"

    @PostMapping("/save")
    fun save(@RequestBody body: String): String = try {
        service.save(body)
        "ok"
    } catch (e: IllegalStateException) {
        "<p>${e.message}</p>"
    } finally {
        println("done")
    }
}
}

// rev309/cases2: r_try_catch_finally
object Case88 {
fun load(path: String): String {
    val text = try {
        read(path)
    } catch (e: java.io.IOException) {
        "<p>${e.message}</p>"
    } finally {
        close()
    }
    val other =
        try { read(path) }
        catch (e: Exception) { "" }
    return "<pre>$text $other</pre>"
}
}

// rev309/cases4: p_try_catch_newline_brace
object Case146 {
fun outer(n: String) {
    fun f(n: Int) = try {
        "a"
    } catch (e: Exception)
    {
        "<b>$n</b>"
    }
    println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_stmt_try
object Case150 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) try {
        println(n)
    }
    catch (e: Exception) {
        println(n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases6: s_try_allman
object Case187 {
fun outer(n: String) {
    fun f(n: Int) = try
    {
        "<b>$n</b>"
    }
    finally {}
    println("<p>$n</p>")
}
}

// r4mark/cases: m27_try_value.kt
object Case224 {
@Language("HTML")
fun t(n: String) = try { "Try <b>$n</b>" } catch (e: Exception) { "Catch <i>x</i>" }

@Language("HTML")
val l = lazyText { "Lambda <b>x</b>" }
}

// r6review/cases: c01_finally_loop.kt
object Case253 {
fun page(x: Int, xs: List<String>): String {
    var out = ""
    for (x in xs) try { out += x }
    finally { out += "<p>$x</p>" }
    return out
}
}

// r6review/cases: c02_catch_loop.kt
object Case254 {
fun page(x: Int, xs: List<String>): String {
    var out = ""
    for (x in xs) try { out += x.length }
    catch (e: Exception) { out += "<p>$x</p>" }
    return out
}
}

// r6review/cases: c03_catch_call.kt
object Case255 {
fun catch(a: String) {}
fun page(n: String, items: List<Int>): String {
    var out = ""
    for (n in items) out += n
    catch("<p>$n</p>")
    return out
}
}

// r6review/cases: c05_val_catch_finally.kt
object Case257 {
fun page(n: String): String {
    val a = try { n.length }
    catch (e: Exception) { 0 }
    val b = try { n.length }
    finally { println() }
    val c = q!!
    catchCount++
    val d = q!!
    finally++
    return "<p>$a $b $c $d</p>"
}
var q: Int? = 1
var catchCount = 0
var finally = 0
}

// r6review/cases: c06_fun_expr_finally.kt
object Case258 {
fun outer(n: String): String {
    fun size(n: Int) = try { n }
    finally { println("<b>$n</b>") }
    return "<p>$n</p>"
}
fun outer2(n: String): String {
    fun size(n: Int) = try { n }
    catch (e: Exception) { "<b>$n</b>" }
    return "<p>$n</p>"
}
}

// r6review/cases: c07_lambda_try.kt
object Case259 {
fun page(x: Int, xs: List<String>): String =
    xs.joinToString { x ->
        val v = try { x.length }
        finally { }
        "<p>$x $v</p>"
    }
}

// r6review/cases: c13_val_finally_getter.kt
object Case265 {
class P(val q: String) {
    val a = try { q.length }
    finally { }
    val b get() = "<b>$q</b>"
    val c = try { q.length } catch (e: Exception) { 0 }
    finally { }
    val d get() = "<b>$q</b>"
}
}

// r6review/cases2: d02_finally_fun.kt
object Case267 {
fun outer(n: String): String {
    fun size(n: Int) = try { n }
    finally { println("<b>$n</b>") }
    return "<p>$n</p>"
}
}

// r6review/cases2: d04_typed_finally_loop.kt
object Case269 {
fun page(x: Int, xs: List<String>): String {
    var out = ""
    for (x: String in xs) try { out += x }
    finally { out += "<p>$x</p>" }
    return out
}
}

// fresh309-a7f0/a.cases #20
object Case286 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) try {
        println(x)
    } catch (e: Exception) {
        println(e)
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #21
object Case287 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) try {
        println(x)
    }
    catch (e: Exception) {
        println(e)
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #34
object Case300 {
fun outer(n: String) {
    fun inner(n: Int): String = try {
        n.toString()
    }
    catch (e: Exception) {
        "x"
    }
    finally {
        println(n)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #77
object Case343 {
fun outer(n: String, flag: Boolean) {
    val x = try { 1 }
    catch (e: Exception) { 2 }
    val title = "<b>$n</b>"
}
}

// fresh309-a7f0/a.cases #78
object Case344 {
fun outer(n: String, flag: Boolean) {
    val x = try { 1 } finally { println() }
    catchUp()
    val title = "<b>$n</b>"
}
}

// fresh309-a7f0/a.cases #79
object Case345 {
fun outer(n: String) {
    val x = foo()
    catch({ risky() }) { e -> println(e) }
    val title = "<b>$n</b>"
}
}

// fresh309-a7f0/b.cases #1
object Case407 {
fun outer(n: Int) {
    fun inner(n: String): String = try {
        "a"
    }
    finally {
        println("<p>$n</p>")
    }
}
}

// fresh309-a7f0/b.cases #2
object Case408 {
fun outer(n: Int) {
    fun inner(n: String): String = try {
        "a"
    } finally {
        println("<p>$n</p>")
    }
}
}

// fresh309-a7f0/b.cases #3
object Case409 {
fun outer(n: Int, xs: List<String>) {
    for (n in xs) try {
        println(n)
    }
    finally {
        println("<p>$n</p>")
    }
}
}

// fresh309-a7f0/b.cases #4
object Case410 {
fun page(name: String) {
    val v = try { load() }
    finally { close() }
    println("<p>$name $v</p>")
}
}

// review-309/lines.kt: fun-expr-try
object Case576 {
fun outer(n: String) {
    fun size(n: Int) = try {
        n
    } catch (e: Exception) {
        0
    }
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-try-next-line
object Case577 {
fun outer(n: String) {
    fun size(n: Int) = try { n }
        catch (e: Exception) { 0 }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-try
object Case612 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) try {
        println(n)
    } catch (e: Exception) {
        println(e)
    }
    println("<p>$n</p>")
}
}

// r7/hand: arrow_catch_call
object Case742 {
fun page(n: Int): String {
    val v = either { load(n) }
    catch({ risky() }) { e: Throwable -> fallback() }
    return "<p>$v</p>"
}
}

// r7/hand: arrow_catch_call_after_block
object Case743 {
fun page(n: Int): String {
    val v = run {
        load(n)
    }
    catch({ risky() }) { e: Throwable -> fallback() }
    return "<p>$v</p>"
}
}

// r7/hand: fun_expr_lambda_then_catch_call
object Case760 {
    fun case760() {
fun a(n: Int) = run { n }
catch(x)
fun b(n: Int) = "<p>$n</p>"
    }
}

// r7/hand: if_block_then_catch_call
object Case765 {
fun page(n: Int, ok: Boolean): String {
    val v = if (ok) {
        load(n)
    } else {
        "y"
    }
    catch(e)
    return "<p>$v</p>"
}
}

// r7/hand: try_catch_catch
object Case780 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    catch (e: IllegalStateException) {
        "a"
    }
    catch (e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_catch_nospace
object Case782 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    catch(e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_catch_paren_next_line
object Case783 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    catch
    (e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_catch_then_finally_allman
object Case784 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    catch (e: Exception)
    {
        "<b>x</b>"
    }
    finally
    {
        done()
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_finally_brace_next_line
object Case787 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    finally
{
        done()
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_finally_brace_next_line_indented
object Case788 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    finally
    {
        done()
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_finally_brace_next_line_indented8
object Case789 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    finally
        {
        done()
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_finally_same
object Case790 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    finally {
        done()
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_fun_expr
object Case791 {
fun page(n: Int) = try {
    load(n)
}
catch (e: Exception) {
    "<b>x</b>"
}
fun other(n: Int) = "<p>$n</p>"
fun load(n: Int): String = "x"
}

