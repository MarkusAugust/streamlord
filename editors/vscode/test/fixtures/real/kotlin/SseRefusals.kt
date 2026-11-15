package forge.sse

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream

suspend fun DatastarStream.refused() {
    executeScript("x()", attributes = mapOf("bad name" to "v"))
    executeScript("x()", attributes = mapOf("data-effect" to "y()"))
    executeScript("x()", autoRemove = false, attributes = mapOf("data-effect" to "y()"))
    send(PatchElements.remove(""))
    send(PatchSignals.remove("first-name"))
    send(ExecuteScript.redirect("javascript:x"))
    patchElements("<circle id=\"c\"/>", selector = "#sky", mode = ElementPatchMode.APPEND)
    patchElements("<circle id=\"c\"/>", selector = "#sky", mode = ElementPatchMode.APPEND, namespace = ElementNamespace.SVG)
    patchSignals("[1,2]")
}

val answer = ScriptResponse("go()", attributes = mapOf("type" to "a\nb"))

class Other {
    fun removeElements(s: String) = s

    fun patchElements(
        s: String,
        mode: ElementPatchMode,
    ) = s
}

fun lookAlikes(o: Other) {
    o.removeElements("")
    o.patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
}
