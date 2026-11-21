// r8-review/hand/H7Issue
object Case81 {
fun pageE(name: String): String {
    fun size(n: Int) = /* padded */ n
    println("<p>$n</p>")
    return name
}

fun pageF(name: String?): String {
    fun pair(n: Int) = name!! to n
    println("<p>$n</p>")
    return "x"
}
}

