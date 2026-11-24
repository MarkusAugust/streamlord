import org.intellij.lang.annotations.Language

@Language("CSS")
const val P88_STYLE = "Q94 .a { color: red }"

@Language("JavaScript")
val p88script = "Q95 console.log('<b>x</b>')"

@Language("HTML")
val p88page = "Q96 <style>$P88_STYLE</style><script>$p88script</script>"

// language=CSS
val p88css = """
    Q97 body { margin: 0 }
"""

// language=HTML
val p88html = """
    Q98 <body></body>
"""

// language=JSON
val p88json = "Q99 {\"a\": \"<b>\"}"

@Language("html")
val p88lower = "P880 <p>lower</p>"

// language=SQL
val p88sql = "P881 select '<b>' from t"

@Language("XML")
val p88xml = "P882 <root/>"

@Language("HTML")
fun p88fn(@Language("CSS") css: String = "P883 p{}") = "P884 <style>$css</style>"
