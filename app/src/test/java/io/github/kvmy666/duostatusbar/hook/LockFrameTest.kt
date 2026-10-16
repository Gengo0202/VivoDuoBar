package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import io.github.kvmy666.duostatusbar.hook.rom.RomDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The locked ring is centered in the status bar, not in the icon strip and not in a full-screen window.
 * Picking the wrong frame puts the ring on the clock's cluster or in the middle of the display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockFrameTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val finder = ContainerFinder(context, RomDetection.forThisRom("", "", "", ""), LogOnce())

    @Test
    fun `an unmeasured bar has no lock frame yet`() {
        val bar = FrameLayout(context)
        val strip = LinearLayout(context)
        bar.addView(strip)
        assertNull(finder.findLockFrame(strip, bar))
    }

    @Test
    fun `a short status bar is its own lock frame`() {
        val bar = FrameLayout(context)
        val strip = LinearLayout(context)
        bar.addView(strip, FrameLayout.LayoutParams(300, 40))
        layout(bar, 1080, 140)
        assertSame(bar, finder.findLockFrame(strip, bar))
    }

    @Test
    fun `a full-screen window yields the short status bar inside it`() {
        val window = FrameLayout(context)
        val bar = FrameLayout(context)
        val cluster = FrameLayout(context)
        val strip = LinearLayout(context)
        cluster.addView(strip, FrameLayout.LayoutParams(280, 48))
        bar.addView(cluster, FrameLayout.LayoutParams(320, 80, android.view.Gravity.END))
        window.addView(bar, FrameLayout.LayoutParams(1080, 140))
        layout(window, 1080, 2400)
        assertEquals(1080, bar.width)
        assertEquals(140, bar.height)
        assertSame(bar, finder.findLockFrame(strip, window))
    }

    private fun layout(view: View, width: Int, height: Int) {
        val w = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        view.measure(w, h)
        view.layout(0, 0, width, height)
    }
}
