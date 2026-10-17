package io.github.markusaugust.streamlord.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.util.AttributeKey
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function

/*
 * Ktor's Tomcat and Jetty engines end a response whose writer failed as if it had finished: the
 * last chunk goes out and the connection stays open. Datastar's client retries a stream that
 * breaks and not one that ends, so a block that throws would leave the page without its stream.
 * No API Ktor has cuts the connection there, so this reaches into each engine by reflection, which
 * keeps them off the classpath: the fields it needs are private anyway, and Jetty 11 and 12 have
 * different classes of one name. On an engine it does not know, or one whose insides have moved,
 * it does nothing, and the stream ends as the engine ends it. CIO and Netty cut the connection
 * themselves.
 */

/** Cut the connection under [call]'s response, which has started. Whether a cut was set going. */
internal fun cutConnection(
    call: ApplicationCall,
    cause: Throwable,
): Boolean {
    val response = call.attributes.getOrNull(BaseApplicationResponse.EngineResponseAttributeKey) ?: return false
    // Whatever goes wrong here, a native image's missing reflection metadata included, the failure
    // the caller rethrows is the block's, not this one.
    return try {
        tomcat(response) || jettyCore(response, cause) || jettyServlet(response, cause)
    } catch (e: Throwable) {
        false
    }
}

private fun servletRequest(response: BaseApplicationResponse): Any? {
    val request = response.call.request
    val getter = request.javaClass.methods.firstOrNull { it.name == "getServletRequest" && it.parameterCount == 0 } ?: return null
    return getter.invoke(request)
}

private fun field(
    type: Class<*>,
    name: String,
): Field {
    val found = generateSequence(type) { it.superclass }.firstNotNullOf { c -> c.declaredFields.firstOrNull { it.name == name } }
    found.isAccessible = true
    return found
}

/*
 * Tomcat 10.1 and 11. The cut waits for Ktor: raising CLOSE_NOW at once has Tomcat finish and
 * recycle the request while Ktor still holds it, which with discardFacades=false reached other
 * requests. Instead the Coyote response's action hook is wrapped, and when the adapter asks whether
 * the request failed, during the dispatch Ktor's complete() set going, on Tomcat's own thread, the
 * processor's error state is raised first. Tomcat then takes its own failure path: no last chunk,
 * an HTTP/1.1 connection closed, an HTTP/2 stream reset. The AsyncContext noted before anything was
 * written tells this request from the next one a reused facade may serve.
 */
private val TomcatRequestKey = AttributeKey<Any>("streamlord.tomcat.async", portableTypeInfo<Any>())

/** Note which Tomcat request serves [call], before anything is written. Nothing on another engine. */
internal fun markEngine(call: ApplicationCall) {
    try {
        val response = call.attributes.getOrNull(BaseApplicationResponse.EngineResponseAttributeKey) ?: return
        val facade = servletRequest(response) ?: return
        if (facade.javaClass.name != "org.apache.catalina.connector.RequestFacade") return
        val async = facade.javaClass.getMethod("getAsyncContext").invoke(facade) ?: return
        call.attributes.put(TomcatRequestKey, async)
    } catch (e: Throwable) {
        // Not async, or not a Tomcat this knows: no cut.
    }
}

private fun tomcat(response: BaseApplicationResponse): Boolean {
    val ours = response.call.attributes.getOrNull(TomcatRequestKey) ?: return false
    val facade = servletRequest(response) ?: return false
    val catalina = field(facade.javaClass, "request").get(facade) ?: return false
    val asyncNow = catalina.javaClass.getMethod("getAsyncContextInternal")
    // Tomcat has given this request up already, and the facade may serve another.
    if (asyncNow.invoke(catalina) !== ours) return false
    val coyoteRequest = catalina.javaClass.getMethod("getCoyoteRequest").invoke(catalina)
    val coyote = coyoteRequest.javaClass.getMethod("getResponse").invoke(coyoteRequest)
    val loader = coyote.javaClass.classLoader
    val hookField = field(coyote.javaClass, "hook")
    val processor = hookField.get(coyote) ?: return false
    val errorStateField = field(processor.javaClass, "errorState")
    val errorState = Class.forName("org.apache.coyote.ErrorState", false, loader)
    val closeNow = errorState.getField("CLOSE_NOW").get(null)
    val mostSevere = errorState.getMethod("getMostSevere", errorState)
    val actionCode = Class.forName("org.apache.coyote.ActionCode", false, loader)
    val isError = actionCode.getField("IS_ERROR").get(null)
    val isCompleting = actionCode.getField("ASYNC_IS_COMPLETING").get(null)
    val hookType = Class.forName("org.apache.coyote.ActionHook", false, loader)
    lateinit var hook: Any

    fun unhook() {
        if (hookField.get(coyote) === hook) hookField.set(coyote, processor)
    }
    hook =
        Proxy.newProxyInstance(loader, arrayOf(hookType)) { self, method, args ->
            when (method.name) {
                "equals" -> return@newProxyInstance self === args?.get(0)
                "hashCode" -> return@newProxyInstance System.identityHashCode(self)
                "toString" -> return@newProxyInstance processor.toString()
            }
            if (asyncNow.invoke(catalina) !== ours) {
                // The request ended some other way, and the processor serves another now.
                unhook()
            } else if (args[0] === isError) {
                val completing = AtomicBoolean()
                method.invoke(processor, isCompleting, completing)
                if (completing.get()) {
                    errorStateField.set(processor, mostSevere.invoke(errorStateField.get(processor), closeNow))
                    unhook()
                }
            }
            try {
                method.invoke(processor, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    hookField.set(coyote, hook)
    return true
}

// Ktor 3.3 and newer run Jetty 12 without servlets. The exchange's HttpStream is wrapped so that the
// last write, which Ktor makes as it ends the response, fails instead: Jetty then aborts the
// exchange, closing an HTTP/1.1 connection and resetting an HTTP/2 stream. That write also carries
// the headers of a response that wrote nothing, so such a response gets none, which the client
// retries as it retries a cut.
private fun jettyCore(
    response: BaseApplicationResponse,
    cause: Throwable,
): Boolean {
    if (response.javaClass.name != "io.ktor.server.jetty.jakarta.JettyApplicationCall\$JettyApplicationResponse") return false
    val field = response.javaClass.getDeclaredField("response")
    field.isAccessible = true
    val jettyResponse = field.get(response)
    val loader = jettyResponse.javaClass.classLoader
    val streamClass = Class.forName("org.eclipse.jetty.server.HttpStream", false, loader)
    val failCallback = Class.forName("org.eclipse.jetty.util.Callback", false, loader).getMethod("failed", Throwable::class.java)
    val request = Class.forName("org.eclipse.jetty.server.Response", false, loader).getMethod("getRequest").invoke(jettyResponse)
    val wrap =
        Function<Any, Any> { stream ->
            Proxy.newProxyInstance(loader, arrayOf(streamClass)) { _, method, args ->
                try {
                    // send(MetaData.Request, MetaData.Response, boolean last, ByteBuffer, Callback)
                    if (method.name == "send" && args?.size == 5 && args[2] == true) {
                        failCallback.invoke(args[4], cause)
                        null
                    } else {
                        method.invoke(stream, *(args ?: emptyArray()))
                    }
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
    Class
        .forName("org.eclipse.jetty.server.Request", false, loader)
        .getMethod("addHttpStreamWrapper", Function::class.java)
        .invoke(request, wrap)
    return true
}

// Ktor 3.0 to 3.2 run Jetty 11 behind Ktor's servlet: HttpChannel.abort. Jetty 12's core Request,
// which has the same name, has no getBaseRequest.
private fun jettyServlet(
    response: BaseApplicationResponse,
    cause: Throwable,
): Boolean {
    val servletRequest = servletRequest(response) ?: return false
    val base =
        try {
            Class.forName("org.eclipse.jetty.server.Request", false, servletRequest.javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            return false
        }
    val getBase = base.methods.firstOrNull { it.name == "getBaseRequest" && it.parameterCount == 1 } ?: return false
    val baseRequest = getBase.invoke(null, servletRequest) ?: return false
    val channel = base.getMethod("getHttpChannel").invoke(baseRequest)
    channel.javaClass.getMethod("abort", Throwable::class.java).invoke(channel, cause)
    return true
}
