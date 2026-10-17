package io.github.markusaugust.streamlord.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.BaseApplicationResponse
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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

/** Cut the connection under [call]'s response, which has started. Whether an engine was recognised. */
internal fun cutConnection(
    call: ApplicationCall,
    cause: Throwable,
): Boolean {
    val response = call.attributes.getOrNull(BaseApplicationResponse.EngineResponseAttributeKey) ?: return false
    return try {
        tomcat(response, cause) || jettyCore(response, cause) || jettyServlet(response, cause)
    } catch (e: ReflectiveOperationException) {
        false
    } catch (e: RuntimeException) {
        false
    } catch (e: LinkageError) {
        false
    }
}

private fun servletRequest(response: BaseApplicationResponse): Any? {
    val request = response.call.request
    val getter = request.javaClass.methods.firstOrNull { it.name == "getServletRequest" && it.parameterCount == 0 } ?: return null
    return getter.invoke(request)
}

// Tomcat 10.1 and 11: CLOSE_NOW on the Coyote request behind the facade. HTTP/1.1 closes, HTTP/2 resets the stream.
private fun tomcat(
    response: BaseApplicationResponse,
    cause: Throwable,
): Boolean {
    val facade = servletRequest(response) ?: return false
    if (facade.javaClass.name != "org.apache.catalina.connector.RequestFacade") return false
    val field = facade.javaClass.getDeclaredField("request")
    field.isAccessible = true
    val catalina = field.get(facade)
    val coyote = catalina.javaClass.getMethod("getCoyoteRequest").invoke(catalina)
    val actionCode = Class.forName("org.apache.coyote.ActionCode", false, coyote.javaClass.classLoader)
    val closeNow = actionCode.getField("CLOSE_NOW").get(null)
    coyote.javaClass.getMethod("action", actionCode, Any::class.java).invoke(coyote, closeNow, cause)
    return true
}

// Ktor 3.3 and newer run Jetty 12 without servlets. The exchange's HttpStream is wrapped so that the
// completion Ktor reports as a success, and the last write that goes with it, fail instead: Jetty
// then aborts the exchange, closing an HTTP/1.1 connection and resetting an HTTP/2 stream.
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
    val failStream = streamClass.getMethod("failed", Throwable::class.java)
    val request = Class.forName("org.eclipse.jetty.server.Response", false, loader).getMethod("getRequest").invoke(jettyResponse)
    val wrap =
        Function<Any, Any> { stream ->
            Proxy.newProxyInstance(loader, arrayOf(streamClass)) { _, method, args ->
                when {
                    // send(MetaData.Request, MetaData.Response, boolean last, ByteBuffer, Callback)
                    method.name == "send" && args?.size == 5 && args[2] == true -> {
                        failCallback.invoke(args[4], cause)
                        null
                    }

                    method.name == "succeeded" && args.isNullOrEmpty() -> {
                        failStream.invoke(stream, cause)
                        null
                    }

                    else -> {
                        try {
                            method.invoke(stream, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
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
