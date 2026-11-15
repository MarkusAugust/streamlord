import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
suspend fun DatastarStream.f() = removeElements(selector = "")
