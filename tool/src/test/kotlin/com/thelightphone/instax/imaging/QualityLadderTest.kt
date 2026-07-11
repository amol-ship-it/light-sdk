package com.thelightphone.instax.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QualityLadderTest {

    @Test
    fun `returns first quality whose output fits`() {
        // stub encoder: quality q -> q*1000 bytes; limit 65535 -> first fit is q=65
        val result = PrintImagePrep.encodeUnderLimit(65_535) { q -> ByteArray(q * 1000) }
        assertEquals(65_000, result.size)
    }

    @Test
    fun `uses best quality when it already fits`() {
        val result = PrintImagePrep.encodeUnderLimit(65_535) { q -> ByteArray(q * 10) }
        assertEquals(950, result.size) // q=95, the ladder's top
    }

    @Test
    fun `throws ImageTooLargeException when even minimum quality exceeds limit`() {
        assertFailsWith<ImageTooLargeException> {
            PrintImagePrep.encodeUnderLimit(65_535) { ByteArray(100_000) }
        }
    }

    @Test
    fun `landscape source crops centered to 3x2 without rotation`() {
        // 4000x3000 -> target ratio 1260:840 = 1.5 -> crop 4000x2666 centered? no:
        // width-limited: keep width 4000, height 4000/1.5 = 2666 -> y offset (3000-2666)/2
        val plan = PrintImagePrep.computeCrop(4000, 3000)
        assertEquals(0, plan.rotateDegrees)
        assertEquals(0, plan.left)
        assertEquals(167, plan.top)
        assertEquals(4000, plan.width)
        assertEquals(2666, plan.height)
    }

    @Test
    fun `tall landscape source crops width instead`() {
        // 3000x2200: 3000/2200 = 1.36 < 1.5 -> keep height, crop width to 3300? no:
        // height-limited: width = 2200*1.5 = 3300 > 3000 -> so width-limited after all.
        // 2000x1500 ratio 1.33: width = 1500*1.5 = 2250 > 2000 -> keep width 2000,
        // height = 2000/1.5 = 1333, top = (1500-1333)/2 = 83
        val plan = PrintImagePrep.computeCrop(2000, 1500)
        assertEquals(0, plan.rotateDegrees)
        assertEquals(2000, plan.width)
        assertEquals(1333, plan.height)
        assertEquals(83, plan.top)
    }

    @Test
    fun `wide panorama crops width`() {
        // 6000x2000 ratio 3.0 > 1.5 -> keep height 2000, width = 3000, left = 1500
        val plan = PrintImagePrep.computeCrop(6000, 2000)
        assertEquals(0, plan.rotateDegrees)
        assertEquals(3000, plan.width)
        assertEquals(2000, plan.height)
        assertEquals(1500, plan.left)
        assertEquals(0, plan.top)
    }

    @Test
    fun `portrait source rotates 90 then crops in rotated space`() {
        // 3000x4000 -> rotate -> 4000x3000 -> same as landscape case
        val plan = PrintImagePrep.computeCrop(3000, 4000)
        assertEquals(90, plan.rotateDegrees)
        assertEquals(4000, plan.width)
        assertEquals(2666, plan.height)
        assertEquals(167, plan.top)
    }

    @Test
    fun `exact target size is identity`() {
        val plan = PrintImagePrep.computeCrop(1260, 840)
        assertEquals(0, plan.rotateDegrees)
        assertEquals(0, plan.left)
        assertEquals(0, plan.top)
        assertEquals(1260, plan.width)
        assertEquals(840, plan.height)
    }
}
