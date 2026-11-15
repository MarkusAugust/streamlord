import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

// dataIndicator after a dataInit that sends at once comes too late; an empty expression throws as the page renders.
fun FlowContent.attributeOrder() {
    div {
        dataInit(get("/x"))
        dataIndicator("busy")
    }
    div {
        dataOn("keyDown", "\$n++")
        dataShow("")
    }
}
