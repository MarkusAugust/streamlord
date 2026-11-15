fun show(gen: ServerSentEventGenerator, name: String) {
    gen.patchElements("<p>$name</p>")
}
