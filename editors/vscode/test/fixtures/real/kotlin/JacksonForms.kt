package forge.views

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

data class JxForm(
    @JsonProperty("jxQ") val jxQuery: String = "",
    val jxPage: Int = 0,
    @JsonIgnore val jxSecret: String = "",
)

fun FlowContent.jxSearch(codec: io.github.markusaugust.streamlord.core.port.driven.SignalsCodec) {
    div { dataSignals(codec.toJsonValue(JxForm())) }
}
