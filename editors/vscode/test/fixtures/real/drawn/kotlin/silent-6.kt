import io.github.markusaugust.streamlord.html.signal
import io.github.markusaugust.streamlord.html.post

fun f() = """<button data-on:click="${post("/x")}" data-text="${signal("running")} ? 'Stop' : 'Go'"></button>"""
