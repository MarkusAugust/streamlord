fun show(gen: ServerSentEventGenerator, items: List<String>) {
    gen.patchElements("<ul>${items.joinToString("") { "<li data-onn:click=\"go()\">$it</li>" }}</ul>")
}
