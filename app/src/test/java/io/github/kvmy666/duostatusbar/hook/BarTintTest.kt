package io.github.kvmy666.duostatusbar.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bar-colour capture must read the resolved tint and never a `darkIntensity`.
 *
 * HyperOS 3 passes `onDarkChanged`'s intensity as an int, and the old "first int" rule read that 0..255
 * value as a colour, so the element flipped black/white several times a second. Only an opaque ARGB value
 * may be taken as the tint.
 */
class BarTintTest {

    @Test
    fun `the trailing opaque int is the tint`() {
        // onDarkChanged(areas, darkIntensity, tint): the tint is the last int, and it is opaque.
        assertEquals(
            BarTint.WHITE,
            BarTint.pickTint(arrayOf(arrayListOf<Any>(), 0.5f, BarTint.WHITE))
        )
        assertEquals(
            BarTint.BLACK,
            BarTint.pickTint(arrayOf(arrayListOf<Any>(), 0.5f, BarTint.BLACK))
        )
    }

    @Test
    fun `an int dark-intensity is not mistaken for a colour`() {
        // A 0..255 intensity has a zero alpha byte, so it is skipped; the real tint after it wins.
        assertEquals(BarTint.BLACK, BarTint.pickTint(arrayOf(arrayListOf<Any>(), 200, BarTint.BLACK)))
        // Intensity alone cannot be a colour: nothing is captured.
        assertEquals(0, BarTint.pickTint(arrayOf(arrayListOf<Any>(), 200)))
        assertEquals(0, BarTint.pickTint(arrayOf(1)))
    }

    @Test
    fun `no int arguments means no capture`() {
        assertEquals(0, BarTint.pickTint(arrayOf("x", 0.5f, true)))
        assertEquals(0, BarTint.pickTint(emptyArray()))
    }
}
