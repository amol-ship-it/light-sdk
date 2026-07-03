package com.amolpurohit.tesla.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test fun `range renders in miles`() = assertEquals("212 mi", formatRange(rangeKm = 341.2))
    @Test fun `temp renders one decimal C`() = assertEquals("21.5°C", formatTemp(21.5))
    @Test fun `updatedAt renders relative minutes`() =
        assertEquals("8 min ago", formatUpdatedAt(nowMs = 1_000_000, updatedAtMs = 1_000_000 - 8 * 60_000))
    @Test fun `updatedAt renders just now under a minute`() =
        assertEquals("just now", formatUpdatedAt(nowMs = 1_000_000, updatedAtMs = 990_000))
    @Test fun `updatedAt renders hours`() =
        assertEquals("3 h ago", formatUpdatedAt(nowMs = 4 * 3_600_000, updatedAtMs = 3_600_000 - 600_000))
}
