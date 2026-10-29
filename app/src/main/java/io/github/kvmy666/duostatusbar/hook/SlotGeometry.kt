package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.kvmy666.duostatusbar.L
import io.github.kvmy666.duostatusbar.hook.rom.RomAdapter
import io.github.kvmy666.duostatusbar.hook.rom.RomResources
import kotlin.math.abs

/**
 * The element's size and position inside the icon strip (FR-03/17).
 *
 * The strip is only 61 px tall, and the drawing is `Fit.CONTAIN` (square), so sizing the view to the
 * strip's height capped the element at 61 px and the size setting did nothing above ~75 % - measured on
 * the device: 100 % and 140 % looked identical. The view is now a **square** that follows the setting
 * and is allowed to overflow the strip, capped only by the status bar window's own height.
 *
 * The slot width is captured **once**, at attach, before the stock icons are hidden. Re-measuring it
 * later reads the hidden battery's 0-width and falls back to the strip height, which changes the size
 * mid-session and forces a live relayout of the Rive `TextureView` - the native resize that used to
 * restart System UI and trip the Rive breaker. Pinning the base keeps the size stable for the whole run.
 */
internal class SlotGeometry(private val context: Context, private val rom: RomAdapter) {

    /** The size the element is actually drawn at; captured once at attach, not updated live. */
    var appliedSize = 0
        private set

    /** The slot width captured once, at attach, before the stock icons were hidden. */
    var slotBasePx = 0
        private set

    /**
     * The status bar's own height in px, from the framework `status_bar_height` dimension, read once
     * and cached. 0 means "not probed yet" - [barHeightPx] fills it.
     *
     * This is the trusted bound for everything size-related on a ROM whose icon container measures
     * itself at the window's height for a moment (vivo OriginOS is the reported case): the window
     * height can be 0 or full-screen exactly when a clamp is needed, so the geometry never relies on
     * it alone.
     */
    private var barHeightCache = 0

    /**
     * The status bar's height in px. Probed from SystemUI's own resources (the module runs inside
     * SystemUI, so `android:dimen/status_bar_height` is the real, cutout-aware value the ROM lays
     * its bar out with), falling back to 24 dp when even that is unreadable. Never returns 0.
     */
    fun barHeightPx(): Int {
        if (barHeightCache > 0) return barHeightCache
        val fromResources = try {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        } catch (_: Throwable) {
            0
        }
        val resolved = if (fromResources > 0) fromResources else
            (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        barHeightCache = resolved
        L.i("status bar height: ${resolved}px (${if (fromResources > 0) "from resources" else "24dp fallback"})")
        return resolved
    }

    /** Pins the size and slot base for this run. Called once, at attach. */
    fun capture(sizePercent: Int, basePx: Int) {
        appliedSize = sizePercent
        slotBasePx = basePx
    }

    /**
     * Switches to a size that was already saved for another orientation. The slot base stays the one
     * captured at attach — re-measuring it after the stock icons are hidden reads 0 and would resize
     * from the wrong width.
     */
    fun applySize(sizePercent: Int) {
        appliedSize = sizePercent
    }

    /** Forgets the captured values (teardown). */
    fun reset() {
        appliedSize = 0
        slotBasePx = 0
    }

    /**
     * The element's side in px: the slot base scaled by the setting, capped so it can never grow past
     * the status bar.
     *
     * The cap is the **smaller** of the window root's height (when it has one) and the framework's
     * `status_bar_height`. The window alone used to be the cap, and that was exactly the hole the
     * giant-element bug fell through: during a strip rebuild or a re-attach the window root can read
     * 0 (not laid out yet) or a full-screen height (a shade-hosted bar), and the old code then
     * returned the element *uncapped* — the occasional blow-up that covered the whole bar. A missing
     * window height now clamps to the resource height instead, and a full-screen window is tightened
     * to it, so the element can never leave the status-bar band. [capToBarHeight] is set only for the
     * main bar's element; the extra bars (keyguard, shade header) keep their own window cap, since
     * those headers are legitimately taller than the status bar.
     *
     * [base] is the per-slot value for the extra bars; the main bar relies on the captured [slotBasePx].
     */
    fun sidePx(
        container: ViewGroup,
        windowRoot: View?,
        base: Int = 0,
        capToBarHeight: Boolean = false
    ): Int {
        val measured = when {
            base > 0 -> base
            slotBasePx > 0 -> slotBasePx
            else -> measuredWidth(container)
        }
        val scaled = (measured * appliedSize / 100).coerceAtLeast(1)
        val windowHeight = (windowRoot?.height ?: 0).takeIf { it > 0 }
        val bar = barHeightPx().takeIf { it > 0 }
        val cap = when {
            capToBarHeight && windowHeight != null && bar != null -> minOf(windowHeight, bar)
            capToBarHeight && bar != null -> bar
            windowHeight != null -> windowHeight
            bar != null -> bar
            else -> (60f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
        return scaled.coerceAtMost(cap).coerceAtLeast(1)
    }

    /** The element's width in px, used for the Canvas fallback's layout params. */
    fun widthPx(container: ViewGroup): Int {
        val base = if (slotBasePx > 0) slotBasePx else measuredWidth(container)
        return (base * appliedSize / 100).coerceAtLeast(1)
    }

    /**
     * The battery slot's column — 83 px measured on the target device — or the best available estimate.
     *
     * Every estimate is now clamped against the status bar's height: a battery view carrying a
     * percentage is at most twice the bar tall, and a strip whose height exceeds the bar is not an
     * icon strip at all but the OEM container mid-rebuild (vivo OriginOS reports its full-height
     * measure there — the source of the giant element). Both fall back to the 27.4 dp estimate
     * rather than feed a bogus base to [sidePx].
     */
    fun measuredWidth(container: ViewGroup): Int {
        val bar = barHeightPx()
        val measured = try {
            val id = RomResources.id(context, rom, rom.batteryId)
            if (id != 0) container.findViewById<View>(id)?.width ?: 0 else 0
        } catch (_: Throwable) {
            0
        }
        if (measured > 0) {
            if (measured <= bar * 2) return measured
            L.w("battery slot ${measured}px is implausible for a ${bar}px bar - using the density estimate")
        }
        // The strip's own height is a valid square estimate only while it really is strip-sized;
        // a container measuring at the window/bar height is mid-rebuild, not a 100+ px icon.
        if (container.height in 1..bar) return container.height
        return (27.4f * context.resources.displayMetrics.density).toInt() // 83 px at density 3.025
    }

    /** How far to move the element so its centre matches its status bar window's centre. */
    fun windowCenterShiftY(container: ViewGroup, windowRoot: View?): Float {
        // The strip is its own bar (the shade header's icon area): nothing to centre against, and the
        // arithmetic below would move it to the window's top instead.
        if (windowRoot == null || windowRoot === container) return 0f
        val height = (windowRoot.height).takeIf { it > 0 } ?: return 0f
        val location = IntArray(2)
        try {
            container.getLocationInWindow(location)
        } catch (_: Throwable) {
            return 0f
        }
        val stripCenter = location[1] + container.height / 2f
        return height / 2f - stripCenter
    }

    /**
     * Vertical translation that seats the **ring's** centre on [anchor]'s centre, both read in window
     * coordinates — the ROM's own idea of where the icon row sits, rather than the window's centre.
     *
     * Used on ROMs whose status-bar window is taller than the visible icon band (vivo OriginOS is the
     * measured case: the punch-hole headroom is inside the window but above the icons), where
     * [windowCenterShiftY] put the ring visibly above the row. The anchor is the clock on ROMs that
     * centre it in the row (see [DuoIconHost.verticalAnchor]).
     *
     * The maths is written against the view's *parent*, so it survives the view being re-parented;
     * `view.top` is only valid once laid out, so callers re-apply after the pending layout pass.
     * Returns NaN whenever the inputs are not trustworthy yet (nothing laid out, or a shift larger
     * than three bar heights, which means the anchor or the parent is mid-layout), and the caller
     * keeps its previous translation instead. When [limitToBarHeight] is set (the main bar), the
     * *result* is additionally verified: the ring's centre must land inside the status-bar band, so a
     * transient measurement (the scrcpy report: the element jumped out of the bar on a display
     * change) can never seat the ring off the bar — the last good translation is kept instead.
     */
    fun anchorShiftY(anchor: View, view: View, side: Int, limitToBarHeight: Boolean = false): Float {
        if (anchor.height <= 0 || view.height <= 0) return Float.NaN
        val parent = view.parent as? ViewGroup ?: return Float.NaN
        return try {
            val anchorLocation = IntArray(2)
            val parentLocation = IntArray(2)
            anchor.getLocationInWindow(anchorLocation)
            parent.getLocationInWindow(parentLocation)
            val anchorCentre = anchorLocation[1] + anchor.height / 2f
            val ringFromTop = RingGeometry.elementHeightPx(side) - side / 2f
            val desiredTopInParent = anchorCentre - parentLocation[1] - ringFromTop
            val shift = desiredTopInParent - view.top
            // An icon row is never far from the bar: a shift bigger than three bar heights means a
            // bogus input, and applying it would throw the element off the screen.
            val bar = barHeightPx()
            if (bar > 0 && abs(shift) > 3f * bar) return Float.NaN
            if (limitToBarHeight && bar > 0) {
                val ringCentre = parentLocation[1] + view.top + shift + ringFromTop
                if (ringCentre < -4f || ringCentre > bar + 4f) {
                    L.w("anchored seat ${ringCentre}px is outside the ${bar}px bar - keeping the last translation")
                    return Float.NaN
                }
            }
            shift
        } catch (_: Throwable) {
            Float.NaN
        }
    }

    /**
     * Whether seating [view] at [translationY] keeps the ring's centre inside the status-bar band —
     * the invariant guard for the fallback (window-centre) path on the main bar, where a transient
     * window height or container position would otherwise throw the element off the bar.
     */
    fun ringCentreWithinBar(view: View, translationY: Float): Boolean {
        val bar = barHeightPx()
        if (bar <= 0) return true
        val side = view.width
        if (side <= 0 || view.height <= 0) return true
        val parent = view.parent as? ViewGroup ?: return true
        return try {
            val parentLocation = IntArray(2)
            parent.getLocationInWindow(parentLocation)
            val ringFromTop = RingGeometry.elementHeightPx(side) - side / 2f
            val ringCentre = parentLocation[1] + view.top + translationY + ringFromTop
            ringCentre >= -4f && ringCentre <= bar + 4f
        } catch (_: Throwable) {
            true
        }
    }
}

/**
 * Margin that leaves [paddingPercent] of [sidePx] in the icon strip.
 *
 * 100 keeps the view's full width, which is the slot between the screen edge and the icons Duo does
 * not replace. 0 returns that width to the strip. The result is zero or negative: a negative start
 * margin overlaps the previous icon without moving this view's end edge.
 */
internal fun edgeInsetPx(sidePx: Int, paddingPercent: Int): Int {
    if (sidePx <= 0) return 0
    val percent = paddingPercent.coerceIn(0, 100)
    return sidePx * percent / 100 - sidePx
}

/**
 * Horizontal translation that puts a [side] px view on the center of the status bar, then nudges it by
 * [offsetPx]. [barLeft] and [parentLeft] are window coordinates. Gravity pins the view to the parent's
 * left padding, so the translation is the whole distance from that edge to the centered seat.
 *
 * The status bar's center does not move when the clock gets wider. A translation written from it stays
 * put with no further layout pass.
 */
internal fun lockedTranslationX(
    barLeft: Int,
    barWidth: Int,
    parentLeft: Int,
    parentPaddingLeft: Int,
    side: Int,
    offsetPx: Float
): Float = barLeft + barWidth / 2f - side / 2f - parentLeft - parentPaddingLeft + offsetPx

/**
 * Vertical translation that puts the ring on the vertical center of the status bar.
 *
 * The view is taller than the ring: the extra artboard space sits above it, so the ring's center is
 * `height - side/2` from the top of the view. Gravity pins that top to the parent's top padding.
 */
internal fun lockedTranslationY(
    barTop: Int,
    barHeight: Int,
    parentTop: Int,
    parentPaddingTop: Int,
    side: Int
): Float {
    val height = RingGeometry.elementHeightPx(side)
    val ringFromTop = height - side / 2f
    return barTop + barHeight / 2f - ringFromTop - parentTop - parentPaddingTop
}
