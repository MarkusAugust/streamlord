package forge.views

import io.github.markusaugust.streamlord.html.*
import io.github.markusaugust.streamlord.html.pro.*
import kotlinx.html.*

fun FlowContent.dashboard(hero: Hero) {
    section {
        id = "dashboard"
        dataSignals("heroName" to hero.name, "questOpen" to false, "selectedQuest" to 0)
        dataSignals("hero", "{strength: 3, wisdom: 1}")
        dataSignals("{quests: {urgent: 0, done: []}}")
        dataComputed("power", "\$hero.strength * 2")
        dataInit(get("/quests"))
        h1 { dataText("\$heroName") }
        p {
            dataShow("\$questOpen")
            dataClass("urgent", "\$quests.urgent > 0")
            dataStyle("color", "\$quests.urgent > 0 ? 'red' : 'inherit'")
            dataAttr("title", "`\${\$quests.urgent} urgent`")
            +"Quests"
        }
        button {
            dataOnClick(toggle("questOpen"))
            +"Open"
        }
        button {
            dataOnClick(increment("selectedQuest"))
            dataIndicator("loadingQuest")
            +"Next"
        }
        button {
            dataOn("click", post("/quests/accept")) {
                debounce = 300.milliseconds
                once = true
            }
            +"Accept"
        }
        button {
            dataOnClick("@post('/quests', {contentType: 'form', headers: {'X-Hero': \$heroName}})")
            dataOnKeydown("evt.key === 'Enter' && @put('/quests/' + \$selectedQuest)")
            +"Save"
        }
        input {
            dataBind("searchText")
            dataOnInput("@get('/search')") { debounce = 200.milliseconds }
            dataOnChange(set("questOpen", true))
        }
        form {
            dataOnSubmit("@post('/quests')") { prevent = true }
        }
        ul {
            dataOnIntersect("@get('/quests/more')") { once = true }
            dataOnInterval("@get('/quests/poll')")
            dataOnSignalPatch("console.log(patch)")
            dataOnFetch("evt.detail.type === 'finished' && (\$questOpen = false)")
            dataRef("questList")
            dataIgnoreMorph()
        }
        pre { dataJsonSignals() }
        div {
            dataEffect("\$power > 9 && @post('/alerts')")
            dataPreserveAttr("open")
            dataIgnore()
        }
        div {
            attributes["data-on:click__once"] = "\$selectedQuest = 0"
            attributes["data-show"] = "\$questOpen"
            attributes["data-class:done"] = "\$quests.done.length > 0"
        }
        aside {
            dataPersist("forge")
            dataQueryString()
            dataReplaceUrl("`/quests/\${\$selectedQuest}`")
            dataOnRaf("\$tick++")
            dataOnResize("\$width = el.offsetWidth")
            dataCustomValidity("\$searchText.length < 2 ? 'Too short' : ''")
            dataViewTransition("'quest'")
            dataAnimate("opacity", "\$tick % 2")
            dataMatchMedia("narrow", mediaQuery = "(max-width: 600px)")
            dataScrollIntoView()
            dataText(peek("\$tick"))
        }
    }
}
