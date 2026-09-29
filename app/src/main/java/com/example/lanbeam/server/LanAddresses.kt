package com.example.lanbeam.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks the address other devices can actually reach, and labels it correctly.
 *
 * v2.1 returned the first non-loopback IPv4 of ANY interface — often mobile data (rmnet*, ccmni*),
 * the 464XLAT shim (192.0.0.4) or a VPN — so the QR code pointed nowhere reachable.
 *
 * Interface NAMES are not reliable for telling Wi-Fi from hotspot (vendors reuse wlan0/wlan1 for
 * either, depending on STA+AP concurrency), so the primary signal is the system's own view:
 * the IPs Android reports for the Wi-Fi network we are a client of, and for cellular/VPN
 * networks. Name heuristics are only the fallback when that view is unavailable.
 */
object LanAddresses {

    enum class Kind(val label: String) { WIFI("Wi-Fi"), HOTSPOT("Hotspot"), ETHERNET("Ethernet"), OTHER("Network") }

    data class Candidate(val iface: String, val ip: String)

    data class LanAddress(val ip: String, val iface: String, val kind: Kind)

    /** What ConnectivityManager says about the networks this phone is on (IPv4 strings). */
    data class SystemView(
        val wifiClientIps: Set<String> = emptySet(),
        val ethernetIps: Set<String> = emptySet(),
        /** Cellular and VPN addresses: never reachable from the LAN. */
        val unreachableIps: Set<String> = emptySet(),
    )

    private val EXCLUDED_PREFIXES = listOf(
        "rmnet", "r_rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "ipsec", "dummy", "lo",
        "radio", "seth", "pdp", "wwan", "usb_rmnet", "epdg", "ifb", "sit", "ip6tnl", "ip_vti", "gre", "wg",
    )
    private val HOTSPOT_PREFIXES = listOf("wlan", "ap", "swlan", "softap", "wigig", "p2p")

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

    fun kindOf(c: Candidate, view: SystemView?): Kind {
        val n = c.iface.lowercase()
        if (view != null) {
            if (c.ip in view.wifiClientIps) return Kind.WIFI
            if (c.ip in view.ethernetIps) return Kind.ETHERNET
            // A Wi-Fi-radio address that is not our Wi-Fi client address is the network we host.
            if (HOTSPOT_PREFIXES.any { n.startsWith(it) }) return Kind.HOTSPOT
            if (n.startsWith("eth")) return Kind.ETHERNET
            return Kind.OTHER
        }
        return when {
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wigig") -> Kind.HOTSPOT
            n == "wlan0" -> Kind.WIFI
            n.startsWith("wlan") -> Kind.HOTSPOT
            n.startsWith("eth") -> Kind.ETHERNET
            else -> Kind.OTHER
        }
    }

    /** Usable candidates, best first: Wi-Fi client > hotspot > Ethernet > other; private ranges first. */
    fun rank(candidates: List<Candidate>, view: SystemView?): List<LanAddress> =
        candidates.filter(::isUsable)
            .filter { view == null || it.ip !in view.unreachableIps }
            .distinctBy { it.ip }
            .map { LanAddress(it.ip, it.iface, kindOf(it, view)) }
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
