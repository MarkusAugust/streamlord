package forge.routes

import io.github.markusaugust.streamlord.ktor.*
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.forge() {
    routing {
        get("/") { call.respondDatastar { patchElements("<main id=\"shop\"></main>") } }
        route("/cart") {
            get { call.respondDatastar { patchSignals("total" to 0) } }
            post("/{id}") {
                val id = call.parameters["id"]
                val qty = call.request.queryParameters["qty"]
                call.respondDatastar { patchElements("<li id=\"item-$id\">$qty</li>", selector = "#cart", mode = ElementPatchMode.APPEND) }
            }
            delete("/{id?}") { call.respondDatastar { removeElements("#item-${call.parameters["id"]}") } }
            route("/coupons") {
                put("/{code}") { }
                patch { }
            }
        }
        sse("/feed") { }
        get("/search/{query...}") { }
    }
}
