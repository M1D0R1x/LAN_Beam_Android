package com.example.lanbeam.server

import com.example.lanbeam.server.LanAddresses.Candidate
import com.example.lanbeam.server.LanAddresses.Kind
import com.example.lanbeam.server.LanAddresses.SystemView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressesTest {

    private fun ips(r: List<LanAddresses.LanAddress>) = r.map { "${it.kind.label} ${it.ip}" }

    @Test fun routerWifiBeatsCellularListedFirst() {
        val c = listOf(Candidate("rmnet_data0", "10.71.23.4"), Candidate("v4-rmnet_data1", "192.0.0.4"), Candidate("wlan0", "192.168.1.37"))
        val v = SystemView(wifiClientIps = setOf("192.168.1.37"), unreachableIps = setOf("10.71.23.4"))
        assertEquals(listOf("Wi-Fi 192.168.1.37"), ips(LanAddresses.rank(c, v)))
    }

    /** Reported on a Nothing phone: Wi-Fi client + hotspot at once; hotspot sat on wlan0. */
    @Test fun wifiAndHotspotLabelledByAddressNotInterfaceName() {
        val c = listOf(Candidate("wlan0", "10.65.2.117"), Candidate("wlan1", "192.168.1.5"), Candidate("rmnet_data2", "100.80.1.9"))
        val v = SystemView(wifiClientIps = setOf("192.168.1.5"), unreachableIps = setOf("100.80.1.9"))
        assertEquals(listOf("Wi-Fi 192.168.1.5", "Hotspot 10.65.2.117"), ips(LanAddresses.rank(c, v)))
    }

    @Test fun hotspotOnlyIsNeverCalledWifi() {
        val c = listOf(Candidate("wlan0", "10.65.2.117"), Candidate("rmnet_data2", "100.80.1.9"))
        val v = SystemView(unreachableIps = setOf("100.80.1.9"))
        assertEquals(listOf("Hotspot 10.65.2.117"), ips(LanAddresses.rank(c, v)))
    }

    /** Mobile data only: nothing is reachable, even if the cellular interface has an odd name. */
    @Test fun mobileDataOnlyShowsNothing() {
        val c = listOf(Candidate("data0", "10.65.2.117"))
        assertTrue(LanAddresses.rank(c, SystemView(unreachableIps = setOf("10.65.2.117"))).isEmpty())
    }

    @Test fun vpnAndClatAreDropped() {
        val c = listOf(Candidate("tun0", "100.64.0.2"), Candidate("clat4", "192.0.0.4"), Candidate("wlan0", "10.0.0.8"))
        assertEquals(listOf("Wi-Fi 10.0.0.8"), ips(LanAddresses.rank(c, SystemView(wifiClientIps = setOf("10.0.0.8")))))
    }

    @Test fun fallbackWithoutSystemViewUsesNames() {
        val c = listOf(Candidate("swlan0", "192.168.43.1"), Candidate("wlan0", "192.168.1.5"), Candidate("rmnet0", "100.99.1.1"))
        assertEquals(listOf("Wi-Fi 192.168.1.5", "Hotspot 192.168.43.1"), ips(LanAddresses.rank(c, null)))
        assertEquals(Kind.HOTSPOT, LanAddresses.rank(listOf(Candidate("ap0", "192.168.43.1")), null).first().kind)
    }

    @Test fun linkLocalAndLoopbackDropped() {
        assertTrue(LanAddresses.rank(listOf(Candidate("wlan0", "169.254.3.3"), Candidate("lo", "127.0.0.1")), SystemView()).isEmpty())
    }
}
