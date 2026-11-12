package com.yepplayer.app

import android.net.Uri
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Keeps playlists, logos and streams away from the household network.
 *
 * A playlist is a list of web addresses chosen by whoever wrote it. Without this check a malicious
 * playlist could make the app open addresses such as 192.168.1.1 (your router) or other devices at
 * home. By default only public internet servers are allowed; a user whose IPTV server really runs at
 * home can switch on "Allow servers on my home network" in Settings.
 */
object NetGuard {
    @Volatile var allowLocal = false

    class Blocked(message: String) : SecurityException(message)

    fun load(prefs: android.content.SharedPreferences) { allowLocal = prefs.getBoolean("allow_local", false) }

    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet6Address) return (b[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique local
        val first = b[0].toInt() and 0xFF; val second = b[1].toInt() and 0xFF
        return (first == 100 && second in 64..127) || first == 0 // carrier-grade NAT, "this network"
    }

    /** Throws [Blocked] when [url] is not http(s) or (unless allowed) resolves to a private address. Call off the main thread. */
    fun check(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw Blocked("Blocked: only http and https addresses are allowed ($scheme)")
        val host = uri.host ?: throw Blocked("Blocked: address has no server name")
        if (allowLocal) return
        val addresses = try { InetAddress.getAllByName(host) } catch (e: Exception) { return } // unresolvable: the connection fails anyway
        if (addresses.any { isPrivate(it) }) throw Blocked("Blocked for your safety: $host is on your home network. If your IPTV server really is at home, allow it in Settings.")
    }
}
