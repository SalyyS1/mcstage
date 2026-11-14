package com.salyvn.mcstage.core

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Probes loopback TCP ports before starting a server so a developer's already-running local server on
 * 25565 does not cause a cryptic port-bind failure inside the child JVM.
 */
object PortProbe {

    fun allocateFreePort(preferred: Int): Int {
        val loopback = InetAddress.getLoopbackAddress()
        if (preferred <= 0) {
            return ephemeralPort(loopback)
        }
        val upper = minOf(65535, preferred + 64)
        for (candidate in preferred..upper) {
            if (isBindable(loopback, candidate)) {
                return candidate
            }
        }
        return ephemeralPort(loopback)
    }

    fun isBindable(address: InetAddress = InetAddress.getLoopbackAddress(), port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            ServerSocket(port, 1, address).use { true }
        } catch (_: IOException) {
            false
        }
    }

    private fun ephemeralPort(address: InetAddress): Int =
        ServerSocket(0, 1, address).use { it.localPort }
}
