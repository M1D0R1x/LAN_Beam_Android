package com.example.lanbeam.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUtilTest {

    private fun partial(h: String?, total: Long) = HttpUtil.parseRange(h, total) as HttpUtil.RangeResult.Partial

    @Test fun noHeaderIsFull() = assertEquals(HttpUtil.RangeResult.Full, HttpUtil.parseRange(null, 100))

    @Test fun openEndedRange() {
        val r = partial("bytes=96-", 100).range
        assertEquals(96, r.start); assertEquals(99, r.end); assertEquals(4, r.length)
    }

    @Test fun closedRangeClampedToEnd() {
        val r = partial("bytes=10-1000", 100).range
        assertEquals(10, r.start); assertEquals(99, r.end)
    }

    @Test fun suffixRange() {
        val r = partial("bytes=-10", 100).range
        assertEquals(90, r.start); assertEquals(99, r.end)
        assertEquals(0, partial("bytes=-500", 100).range.start)
    }

    @Test fun multiRangeUsesFirst() = assertEquals(0L, partial("bytes=0-9, 20-29", 100).range.start)

    @Test fun unsatisfiable() {
        assertEquals(HttpUtil.RangeResult.Unsatisfiable, HttpUtil.parseRange("bytes=100-", 100))
        assertEquals(HttpUtil.RangeResult.Unsatisfiable, HttpUtil.parseRange("bytes=0-", 0))
    }

    @Test fun garbageIsIgnored() {
        assertEquals(HttpUtil.RangeResult.Full, HttpUtil.parseRange("items=0-5", 100))
        assertEquals(HttpUtil.RangeResult.Full, HttpUtil.parseRange("bytes=abc", 100))
        assertEquals(HttpUtil.RangeResult.Full, HttpUtil.parseRange("bytes=50-10", 100))
    }

    @Test fun dispositionEncodesUnicodeAndQuotes() {
        val d = HttpUtil.contentDisposition("旅行 \"final\".mov", attachment = true)
        assertTrue(d.startsWith("attachment; filename=\"__ _final_.mov\""))
        assertTrue(d.contains("filename*=UTF-8''%E6%97%85%E8%A1%8C%20%22final%22.mov"))
    }

    @Test fun jsonEscapesControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", HttpUtil.jsonStr("a\"b\\c\nd\u0001"))
    }

    @Test fun sanitizeStripsPathsAndDots() {
        assertEquals("passwd", HttpUtil.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.txt", HttpUtil.sanitizeFileName("C:\\x\\..\\evil.txt"))
        assertEquals("hidden", HttpUtil.sanitizeFileName("..hidden"))
        assertEquals("file", HttpUtil.sanitizeFileName(""))
        assertEquals("file", HttpUtil.sanitizeFileName(".."))
        val long = HttpUtil.sanitizeFileName("x".repeat(300) + ".mov")
        assertTrue(long.length <= 180 && long.endsWith(".mov"))
        assertFalse(HttpUtil.sanitizeFileName("a\u0000b\nc.txt").any { it.code < 0x20 })
    }
}
