package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.content.res.Configuration
import io.github.kvmy666.duostatusbar.L

/**
 * What colour the status bar's own icons are right now, so the Duo element can match them like a
 * chameleon (black on a light bar, white on a dark one).
 *
 * SystemUI tints its icons through `StatusBarIconView.onDarkChanged(...)` / `setIconColor(...)` as the
 * window behind the bar changes; [install] captures that tint and [fgColor] answers with black or white.
 * The capture is best-effort and ROM-guarded: when it never fires the system day/night setting is the
 * fallback, and a manual black/white override in the app wins over both.
 */
internal object BarTint {

    const val BLACK = DuoMapping.BLACK
    const val WHITE = DuoMapping.WHITE

    /** How many consecutive agreeing readings a new colour needs before it is committed. */
    private const val STABLE_READINGS = 2

    /** Normalised to black or white; 0 until something is captured. */
    @Volatile
    private var normalized: Int = 0

    @Volatile
    private var captured = false

    /** The decision waiting to settle, and how many consecutive readings agreed on it. */
    @Volatile
    private var pending: Int = 0
    @Volatile
    private var pendingCount = 0

    private val logOnce = LogOnce()

    /**
     * Records a tint SystemUI applied to its icons. Returns true only when the black/white decision has
     * **settled**, so a caller redraws once per real transition rather than per frame.
     *
     * `onDarkChanged` fires as the bar's scrim animates, and on some ROMs it alternates black/white every
     * callback (measured: 206 flips in the Samsung Android 17 report). Committing each one repainted the
     * element several times a second — the user-visible flicker. A new decision must therefore be seen
     * [STABLE_READINGS] times in a row before it is committed; an alternating stream never repeats, so it
     * never commits and the bar stays put. The first capture commits immediately (there is nothing to
     * flicker against).
     */
    fun update(color: Int): Boolean {
        if (color == 0) return false
        val next = if (isDark(color)) BLACK else WHITE
        if (captured && next == normalized) {
            pending = 0
            pendingCount = 0
            return false
        }
        if (next == pending) pendingCount++ else {
            pending = next
            pendingCount = 1
        }
        if (!captured || pendingCount >= STABLE_READINGS) {
            val changed = !captured || next != normalized
            normalized = next
            captured = true
            pending = 0
            pendingCount = 0
            if (changed) {
                logOnce.once("bar") {
                    L.i("status bar icons are ${if (next == BLACK) "dark" else "light"} - matching them")
                }
            }
            return changed
        }
        return false
    }

    /** Forgets the captured colour and any pending decision (teardown / tests). */
    fun reset() {
        normalized = 0
        captured = false
        pending = 0
        pendingCount = 0
    }

    /** True once any source has captured a real bar colour. */
    val hasCaptured: Boolean
        get() = captured

    /**
     * Commits a colour from a **single-stable** source — the status-bar clock's own text colour —
     * immediately, without the [STABLE_READINGS] debounce.
     *
     * The debounce exists because `onDarkChanged` can alternate black/white several times a second
     * on some OEM builds. The clock is not that kind of source: SystemUI sets its text colour once
     * per real light/dark transition (it is the colour the user actually sees), so a single reading
     * is already the settled truth, and requiring a second reading would leave the element a
     * transition behind whenever the ROM only sets it once. Feeds the same [normalized] state, so
     * the two sources cannot disagree for longer than one transition.
     */
    fun updateDirect(color: Int): Boolean {
        if (color == 0) return false
        val next = if (isDark(color)) BLACK else WHITE
        if (captured && next == normalized) {
            pending = 0
            pendingCount = 0
            return false
        }
        val changed = !captured || next != normalized
        normalized = next
        captured = true
        pending = 0
        pendingCount = 0
        if (changed) {
            logOnce.once("clock") {
                L.i("status bar clock text is ${if (next == BLACK) "dark" else "light"} - matching it")
            }
        }
        return changed
    }

    /**
     * The resolved icon colour among a hook callback's arguments, or 0 when none can be one.
     *
     * `StatusBarIconView.onDarkChanged(areas, darkIntensity, tint)` is called as the bar's scrim
     * animates, and some OEM builds pass `darkIntensity` as an **int** rather than a float. Taking the
     * first int (the old code) read that 0..255 intensity as a colour, so the element flipped black and
     * white several times a second — exactly the HyperOS 3 report (`icon colour -> black`, then `white`,
     * then `black`, while the bar never changed). A resolved ARGB colour is opaque; an intensity is not,
     * so only ints with a non-zero alpha byte are accepted. The trailing int is the tint either way.
     */
    fun pickTint(arguments: Array<Any?>): Int {
        var color = 0
        for (argument in arguments) {
            if (argument is Int && (argument ushr 24) != 0) color = argument
        }
        return color
    }

    /** Black or white to draw the element in. Captured bar colour first, system day/night as fallback. */
    fun fgColor(context: Context): Int {
        if (captured) return normalized
        return try {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (night) WHITE else BLACK
        } catch (t: Throwable) {
            L.w("bar colour fallback: ${t.javaClass.simpleName}: ${t.message}")
            WHITE
        }
    }

    /** Perceived brightness, so a mid-grey tint still picks the better extreme. */
    private fun isDark(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < 128
    }
}
