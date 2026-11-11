package forge.routes

import io.github.markusaugust.streamlord.spring.*
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/orders")
class OrderController {
    @GetMapping
    fun all() = datastar { patchElements("<ul id=\"orders\"></ul>") }

    @GetMapping("/{id}")
    fun one(@PathVariable id: String, @RequestParam partsnummer: String, @RequestParam(required = false) page: Int?) =
        datastar { patchSignals("order" to id) }

    @PostMapping("/{id}/ship", produces = ["text/event-stream"])
    fun ship(@PathVariable id: String, @RequestHeader("X-Forge") forge: String, @RequestHeader(value = "X-Trace", required = false) trace: String?) =
        datastar { removeElements("#order-$id") }

    @RequestMapping(value = ["/bulk"], method = [RequestMethod.PUT])
    fun bulk(@RequestParam(defaultValue = "10") size: Int) = datastar { }

    @DeleteMapping("/{id}")
    fun cancel(@PathVariable id: String) = datastar { }
}

@RestController
class HealthController {
    @GetMapping("/health")
    fun health() = "ok"
}
