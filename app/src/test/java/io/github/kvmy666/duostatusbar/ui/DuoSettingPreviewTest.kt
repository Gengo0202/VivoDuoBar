package io.github.kvmy666.duostatusbar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-09's demo timing, pinned down because a loop that never quite reaches either end would show the
 * user a state the setting does not actually produce.
 */
class DuoSettingPreviewTest {

    @Test
    fun `the demo starts and ends at the off state`() {
        assertEquals(0f, demoPhase(0f), 0.0001f)
        assertEquals(0f, demoPhase(0.999f), 0.0001f)
    }

    @Test
    fun `the demo reaches the on state and holds it`() {
        assertEquals(1f, demoPhase(0.5f), 0.0001f)
        assertEquals(1f, demoPhase(0.55f), 0.0001f)
    }

    @Test
    fun `the demo never overshoots either state`() {
        for (step in 0..200) {
            val t = step / 200f
            assertTrue("t=$t", demoPhase(t) in 0f..1f)
        }
    }

    @Test
    fun `the demo moves monotonically out and back`() {
        // Rising half: the phase must not dip back towards off on the way to on.
        var previous = -1f
        for (step in 0..50) {
            val phase = demoPhase(0.2f + 0.2f * step / 50f)
            assertTrue("phase dipped: $previous -> $phase", phase >= previous - 0.0001f)
            previous = phase
        }
    }

    @Test
    fun `locked position preview puts zero in the middle and reaches both edges`() {
        assertEquals(0f, positionPreviewOffset(0f, 360f, 120f), 0.0001f)
        assertEquals(-120f, positionPreviewOffset(-360f, 360f, 120f), 0.0001f)
        assertEquals(120f, positionPreviewOffset(360f, 360f, 120f), 0.0001f)
    }

    @Test
    fun `locked position range follows the screen instead of a fixed two hundred dp cap`() {
        assertEquals(337, positionOffsetLimit(673, centered = true))
    }

    @Test
    fun `position press steps one dp and stops at either limit`() {
        assertEquals(11, stepPositionOffset(10, direction = 1, limitDp = 20))
        assertEquals(9, stepPositionOffset(10, direction = -1, limitDp = 20))
        assertEquals(20, stepPositionOffset(20, direction = 1, limitDp = 20))
        assertEquals(-20, stepPositionOffset(-20, direction = -1, limitDp = 20))
    }
}
