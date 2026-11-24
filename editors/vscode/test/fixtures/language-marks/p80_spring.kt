import org.intellij.lang.annotations.Language

annotation class GetMapping(val value: String = "", val produces: String = "")
annotation class RestController
annotation class ResponseBody

@RestController
class P80Controller {
    @GetMapping("/p80", produces = "Q12 text/html")
    @Language("HTML")
    fun index(): String = "Q13 <h1>Hello</h1>"

    @Language("HTML")
    @GetMapping(value = "Q14 /p80b")
    fun other(name: String?): String {
        val safe = name ?: "Q15 anon"
        return "Q16 <p>$safe</p>"
    }

    @ResponseBody
    @GetMapping("Q17 /p80c")
    fun unmarked(): String = "Q18 <p>no mark</p>"

    @GetMapping("/p80d")
    fun withLocal(): String {
        @Language("HTML") val head = "Q19 <head></head>"
        val body = "Q20 <body></body>"
        return head + body
    }
}
