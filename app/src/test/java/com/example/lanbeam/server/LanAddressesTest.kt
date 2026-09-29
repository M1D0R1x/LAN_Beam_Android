package com.example.lanbeam.server

import com.example.lanbeam.server.LanAddresses.Candidate
import com.example.lanbeam.server.LanAddresses.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressesTest {

    @Test fun routerWifiBeatsCellularListedFirst() {
        // Interface order as a phone on home Wi-Fi with "mobile data always active" reports it.
        val c = listOf(
            Candidate("rmnet_data0", "10.71.23.4"),
            Candidate("v4-rmnet_data1", "192.0.0.4"),
            Candidate("wlan0", "192.168.1.37"),
        )
        val r = LanAddresses.rank(c, wifiClientIface = "wlan0")
        assertEquals(listOf("192.168.1.37"), r.map { it.ip })
        assertEquals(Kind.WIFI, r.first().kind)
    }

    @Test fun vpnAndClatAreDropped() {
        val c = listOf(Candidate("tun0", "100.64.0.2"), Candidate("clat4", "192.0.0.4"), Candidate("wlan0", "10.0.0.8"))
        assertEquals(listOf("10.0.0.8"), LanAddresses.rank(c, "wlan0").map { it.ip })
    }

    @Test fun hotspotHostAndWifiClientBothListedWifiFirst() {
        val c = listOf(Candidate("swlan0", "192.168.43.1"), Candidate("wlan0", "192.168.1.5"), Candidate("rmnet0", "100.99.1.1"))
        val r = LanAddresses.rank(c, "wlan0")
        assertEquals(listOf("192.168.1.5", "192.168.43.1"), r.map { it.ip })
        assertEquals(Kind.HOTSPOT, r[1].kind)
    }

    @Test fun hotspotOnlyWhenNoWifiClient() {
        val r = LanAddresses.rank(listOf(Candidate("rmnet_data2", "10.1.1.1"), Candidate("ap0", "192.168.43.1")), null)
        assertEquals(listOf("192.168.43.1"), r.map { it.ip })
        assertEquals(Kind.HOTSPOT, r.first().kind)
    }

    @Test fun linkLocalAndLoopbackDropped() {
        assertTrue(LanAddresses.rank(listOf(Candidate("wlan0", "169.254.3.3"), Candidate("lo", "127.0.0.1")), "wlan0").isEmpty())
    }
}
