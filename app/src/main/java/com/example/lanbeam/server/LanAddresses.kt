package com.example.lanbeam.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks the address other devices can actually reach.
 *
 * The old code returned the first non-loopback IPv4 of ANY interface. On a phone that is on a
 * router's Wi-Fi with mobile data still up ("Mobile data always active" is on by default), that is
 * often the cellular interface (rmnet*, ccmni*), the 464XLAT shim (v4-rmnet*, clat4 = 192.0.0.4)
 * or a VPN tunnel (tun*) — so the QR code/URL pointed at an address no LAN peer can reach, while
 * the same phone acting as a hotspot happened to list its hotspot interface first and worked.
 */
object LanAddresses {

    enum class Kind(val label: String) { WIFI("Wi-Fi"), HOTSPOT("Hotspot"), ETHERNET("Ethernet"), OTHER("Network") }

    data class Candidate(val iface: String, val ip: String)

    data class LanAddress(val ip: String, val iface: String, val kind: Kind)

    private val EXCLUDED_PREFIXES = listOf(
        "rmnet", "r_rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "ipsec", "dummy", "lo",
        "radio", "seth", "pdp", "wwan", "usb_rmnet", "epdg", "ifb", "sit", "ip6tnl", "ip_vti", "gre",
    )

    fun isUsable(c: Candidate): Boolean {
        val name = c.iface.lowercase()
        if (EXCLUDED_PREFIXES.any { name.startsWith(it) }) return false
        val parts = c.ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        if (parts[0] == 127) return false
        if (parts[0] == 169 && parts[1] == 254) return false // link-local, no DHCP
        if (parts[0] == 192 && parts[1] == 0 && parts[2] == 0) return false // CLAT 192.0.0.0/29
        return true
    }

    private fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && (p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168))
    }

    fun kindOf(iface: String, wifiClientIface: String?): Kind {
        val n = iface.lowercase()
        return when {
            wifiClientIface != null && n == wifiClientIface.lowercase() -> Kind.WIFI
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wigig") -> Kind.HOTSPOT
            n.startsWith("wlan") -> if (wifiClientIface == null && n == "wlan0") Kind.WIFI else Kind.HOTSPOT
            n.startsWith("eth") -> Kind.ETHERNET
            else -> Kind.OTHER
        }
    }

    /** Usable candidates, best first: Wi-Fi client > hotspot > Ethernet > other; private ranges first. */
    fun rank(candidates: List<Candidate>, wifiClientIface: String?): List<LanAddress> =
        candidates.filter(::isUsable)
            .distinctBy { it.ip }
            .map { LanAddress(it.ip, it.iface, kindOf(it.iface, wifiClientIface)) }
            .sortedWith(compareBy<LanAddress>({ it.kind.ordinal }, { if (isPrivate(it.ip)) 0 else 1 }, { it.iface }))

    fun enumerate(): List<Candidate> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .mapNotNull { a -> a.hostAddress?.let { Candidate(nif.name, it) } }
            }
    } catch (_: Exception) {
        emptyList()
    }
}
