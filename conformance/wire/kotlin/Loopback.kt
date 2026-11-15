package io.github.markusaugust.streamlord.wire

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/*
 * Where the adapters' tests serve and read. On macOS a server on every address may share its port
 * with one another process binds to the loopback address, whose client then reaches that one and
 * waits out its timeout: the 20 s TomcatWriterTest once took. On the loopback address alone, a
 * server on a port taken there fails to start instead, and each module's LoopbackTest holds its
 * servers to it.
 */

/** The address the tests serve on and read from. */
const val LOOPBACK = "127.0.0.1"

/** Whether something answers a connect to [address] and [port] within two seconds. */
fun answers(
    address: InetAddress,
    port: Int,
): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(address, port), 2_000) } }.isSuccess

/**
 * The addresses of this machine other than [LOOPBACK] at which a server on every address answers,
 * tried with one of its own on 0.0.0.0: each IPv4 address of an interface, and 127.0.0.2, which is
 * this machine's on Linux, where all of 127/8 is. A server the tests start must answer at none.
 */
fun otherAddresses(): List<InetAddress> {
    val loopback = InetAddress.getByName(LOOPBACK)
    val candidates =
        (
            NetworkInterface.networkInterfaces().toList().flatMap { it.inetAddresses.toList() } +
                InetAddress.getByName("127.0.0.2")
        ).filter { it is Inet4Address && it != loopback }.distinct()
    return ServerSocket(0, 50, InetAddress.getByName("0.0.0.0")).use { control -> candidates.filter { answers(it, control.localPort) } }
}

/** [port], what a Tomcat connector says it listens on once started, or a failure that says [what] never bound it. */
fun boundPort(
    port: Int,
    what: String,
): Int = port.takeIf { it > 0 } ?: error("$what is not listening on $LOOPBACK: its port is taken, or it failed to start")
