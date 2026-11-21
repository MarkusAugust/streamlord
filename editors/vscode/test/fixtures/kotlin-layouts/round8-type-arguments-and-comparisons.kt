// review309/cases/generics_classes
object Case38 {
sealed class Result<out T : Any> {
    data class Ok<out T : Any>(val value: T) : Result<T>()
    data class Err(val message: String) : Result<Nothing>()
}
abstract class Repo<K, V : Comparable<V>>
    : Base<K>(), Iterable<V>
{
    protected abstract val items: MutableMap<K, V>
    open val first: V? get() = items.values.firstOrNull()
    val size: Int
        get() = items.size
    var label: String? = null
        private set
    lateinit var name: String
    override fun iterator(): Iterator<V> = items.values.iterator()
    fun <R> map(f: (V) -> R): List<R> = items.values.map(f)
    inline fun <reified T> cast(x: Any): T? = x as? T
    fun html(label: String) = "<p>$label</p>"
}
class Box<T>(val t: T) where T : CharSequence, T : Comparable<T>
object Single : Repo<String, Int>() {
    override val items = mutableMapOf<String, Int>()
}
enum class Color(val hex: String) {
    RED("#f00"),
    GREEN("#0f0");
    fun html() = "<span style='color:$hex'>$name</span>"
}
typealias Handler<T> = suspend (T) -> Unit
val handlers: Map<String, Handler<Int>> = mapOf()
val nested: List<Map<String, List<Int>>>
    = emptyList()
val pair: Pair<Int, String>? = null
fun check(a: Int, b: Int) = a < b && b > a
fun cmp(a: Int, b: Int): Boolean {
    val x = a<b
    val y = a>b
    val z = a < b == b > a
    return "<p>$x $y $z</p>".isEmpty()
}
class Props {
    val a: String?
        get() = null
    val b: List<String>
        get() = emptyList()
    val c = load()!!
    val d: Int get() = 1
    val e = c as Map<String, Int>
        get() = field
    var f: Map<String, Int>? = null
        set(v) { field = v }
}
}

// review309/cases/values_postfix
object Case46 {
fun f(q: Any?, n: Int, m: Any, xs: List<Int>) {
    val a = q!!
    val title = "<b>x</b>"
    var i = n
    i++
    val b = i++
    val t1 = "<b>$b</b>"
    val c = i--
    val t2 = "<b>$c</b>"
    val d = m as String?
    val t3 = "<b>$d</b>"
    val e = m as List<Int>
    val t4 = "<b>$e</b>"
    val g = m as? Map<String, List<Int>>
    val t5 = "<b>$g</b>"
    val h = m as Map<String, () -> Int>
    val t6 = "<b>$h</b>"
    val j = n <
        3
    val k = n > 3 && n <
        5
    val l = xs.size>
        2
    val o = m is List<*>
    val t7 = "<b>$o</b>"
    val p = n + -
        1
    val r = !
        true
    val s = n --
        1
    val u = q ?:
        "x"
    val v = q
        ?: "y"
    val w = xs.filter { it > 0 }
    val t8 = "<b>$w</b>"
    val x: Map<String, Int>
        = mapOf()
    val y = listOf<Int>()
    val t9 = "<b>$y</b>"
    val z = n.coerceIn(0, 1) as Comparable<Int>
    val t10 = "<b>$z</b>"
    val aa = n < xs.size && xs.size > n
    val t11 = "<b>$aa</b>"
    val ab = n<xs.size && n>
        0
    val ac = q as Pair<Int, String>?
    val t12 = "<b>$ac</b>"
    val ad = q as Array<out Number>
    val t13 = "<b>$ad</b>"
    val ae = q as (Int) -> String
    val t14 = "<b>$ae</b>"
    val af = n >= 1 && n <= 3 ||
        false
    val ag = q as Comparable<in String>
    val t15 = "<b>$ag</b>"
    val ah = n shl 2 >
        1
    val ai = n>xs.size || n<
        2
    val aj = n<xs.size == n>
        0
    val ak = xs.size<n - 1 && n>
        0
    val al = n<m.hashCode() && n>
        0
    val am = n<xs.size && xs.size>n
    val t16 = "<b>$am</b>"
    val an = (n<xs.size) to (n>
        0)
    val ao = q as kotlin.collections.List<Int>
    val t17 = "<b>$ao</b>"
    val ap = n < xs.size
    val t18 = "<b>$ap</b>"
    val aq = xs.size<n || xs.size>
        n
    val ar = n<i && i>
        n
    val at = n<i*2 && i>
        n
    val au = n<i/2 && i>
        n
    val av = xs.get(0)<n && n>
        3
}
}

// review309/cases3/v_and_compare
object Case59 {
fun f(n: Int, m: Int) {
    val ok = n<m and n>
        5
    val title = "<b>$ok</b>"
}
}

