package forge.plain

// No Streamlord import: a call named as the DSL's is somebody else's, and HTML in a string is still HTML.
fun dataText(expression: String) = expression

fun other(count: Int, name: String) {
    dataText("$count")
    println("total: $count")
    val sql = """select * from heroes where strength < 3 and name = $name"""
    val row = "<li data-onn:click=\"x()\">$name</li>"
    val fine = "<li id=\"f\" data-on:click=\"@get('/x')\">x</li>"
}
