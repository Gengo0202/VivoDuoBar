package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each status bar restores its own stock icons (PR #14).
 *
 * Notification/QS header reinflation can replace the icon area while the host still holds the old slot,
 * so every extra bar owns a [StockIconHider] instead of sharing one. Restoring one bar must not touch
 * another bar's views, or putting the lock screen back would also un-hide the home bar's icons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShadeRestorationTest {

    /** A stand-in for One UI's `CombinedStatusView`: no readable slot, so it is matched by class name. */
    private class CombinedStatusView(context: Context) : View(context)

    @Test
    fun `two headers restore stock visibility and dimensions independently`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = FrameLayout(context)
        val second = FrameLayout(context)
        val a = CombinedStatusView(context)
        val b = CombinedStatusView(context)
        first.addView(a, FrameLayout.LayoutParams(60, 24))
        second.addView(b, FrameLayout.LayoutParams(80, 32))

        val firstHider = StockIconHider()
        val secondHider = StockIconHider()
        firstHider.hideReplaced(first, keep = null)
        secondHider.hideReplaced(second, keep = null)
        assertEquals(View.GONE, a.visibility)
        assertEquals(View.GONE, b.visibility)

        // Restoring the first bar must put its own view back and leave the second bar untouched.
        firstHider.restore()
        assertEquals(View.VISIBLE, a.visibility)
        assertEquals(60, a.layoutParams.width)
        assertEquals(View.GONE, b.visibility)
        assertEquals(0, b.layoutParams.width)

        secondHider.restore()
        assertEquals(View.VISIBLE, b.visibility)
        assertEquals(80, b.layoutParams.width)
    }

    @Test
    fun `an unrelated status view remains visible when only replaced icons are hidden`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val row = FrameLayout(context)
        val ordinary = View(context)
        row.addView(ordinary, FrameLayout.LayoutParams(25, 25))

        StockIconHider().hideReplaced(row, keep = null)

        assertEquals(View.VISIBLE, ordinary.visibility)
        assertEquals(25, ordinary.layoutParams.width)
    }
}
