package com.tsonglew.quodex.data

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenCountFormatTest {
    @Test fun preservesExactModeAndSmallCounts() {
        assertEquals("1,234,567", formatTokenCount(BigInteger("1234567"), false))
        assertEquals("999", formatTokenCount(BigInteger("999"), true))
        assertEquals("0", formatTokenCount(BigInteger.ZERO, true))
    }
    @Test fun roundsAndPromotesUnitsWithoutOverflow() {
        assertEquals("1.2K", formatTokenCount(BigInteger("1234"), true))
        assertEquals("1M", formatTokenCount(BigInteger("999950"), true))
        assertEquals("4.1M", formatTokenCount(BigInteger("4128640"), true))
        assertEquals("9,223,372,036,854,775,807", formatTokenCount(BigInteger.valueOf(Long.MAX_VALUE), false))
        assertEquals("9223372T", formatTokenCount(BigInteger.valueOf(Long.MAX_VALUE), true))
    }
}
