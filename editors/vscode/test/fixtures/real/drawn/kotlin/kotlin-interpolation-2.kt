@Language("HTML")
fun side(tilstand: Tilstand): String = """
  <div data-signals="{count: 0}" data-on:click__debunce.500ms="@post('/x')">
    <span data-text="${'$'}count"></span>
    <p data-text="$navn"></p>
    <i>$title</i>
  </div>
"""
val row = "<li data-onn:click=\"x()\">x</li>"
val notHtml = "count: $count"
val sql = """select * from x where a < 1 and b = $b"""
s.patchElements("""<div data-onn:x="1"></div>""")
dataText("$count")
