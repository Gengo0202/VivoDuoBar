package io.github.kvmy666.duostatusbar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementHitGeometryTest {

    @Test
    fun `ring region follows the lower square in the raised-label artboard`() {
        assertTrue(ElementHitGeometry.ring(x = 60f, y = 76f, width = 120, height = 136))
        assertTrue(ElementHitGeometry.ring(x = 60f, y = 136f, width = 120, height = 136))
        assertFalse(ElementHitGeometry.ring(x = 60f, y = 10f, width = 120, height = 136))
        assertFalse(ElementHitGeometry.ring(x = 121f, y = 76f, width = 120, height = 136))
    }

    @Test
    fun `indicator region is only the visible middle of its artboard`() {
        assertTrue(ElementHitGeometry.indicators(x = 60f, y = 76f, width = 120, height = 136))
        assertFalse(ElementHitGeometry.indicators(x = 10f, y = 76f, width = 120, height = 136))
        assertFalse(ElementHitGeometry.indicators(x = 60f, y = 20f, width = 120, height = 136))
    }
}
