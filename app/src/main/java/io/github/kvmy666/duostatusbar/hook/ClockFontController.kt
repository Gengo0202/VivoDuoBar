package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.kvmy666.duostatusbar.L
import io.github.kvmy666.duostatusbar.hook.rom.RomAdapter
import io.github.kvmy666.duostatusbar.hook.rom.RomResources
import java.io.File

/**
 * Draws the status-bar clock in the phone's own system font, matching the element's digits (FR-16/28).
 *
 * The module never hides or moves the clock - this only swaps its `Typeface`, remembers the original so
 * switching off restores it exactly, and does nothing when the setting is off or the clock cannot be
 * found. The same OEM font the Rive digits bundle (`SysSans`) is preferred, falling back to the platform
 * `sans-serif` so the feature still works on a ROM that spells its font differently.
 */
internal class ClockFontController(private val context: Context, private val rom: RomAdapter) {

    private var clockView: TextView? = null
    private var clockOriginalTypeface: Typeface? = null

    /** The system typeface, built once: assigning a fresh one every pass forced a relayout loop. */
    private var cachedTypeface: Typeface? = null

    /**
     * Applies the setting: the system font when [enabled], otherwise the remembered original. Safe to
     * call repeatedly (every layout pass); it only logs when the clock view itself changes.
     *
     * The clock view is resolved *regardless* of the font setting: on a ROM whose status-bar window
     * is taller than its icon band (vivo OriginOS — see [RomAdapter.anchorY]) the clock is also the
     * vertical anchor for the element, and on any ROM it is the last-resort colour sample for
     * FR-15b (SystemUI re-tints it with the bar; see `BarTint`). Only the typeface depends on the
     * setting.
     */
    fun apply(root: View?, enabled: Boolean) {
        try {
            val view = root ?: return
            // HyperOS 3 nests several views with the same `clock` id; `findViewById` returns the first,
            // which can be a 0x0 duplicate. The real clock is the visible one, so prefer it.
            val matches = ArrayList<TextView>()
            val id = RomResources.id(context, rom, rom.clockId)
            if (id != 0) collectClocks(view, id, matches)
            val clock = matches.firstOrNull { it.isShown && it.width > 0 }
                ?: matches.firstOrNull()
                // A ROM that spells the id differently still centres a clock in the bar: the first
                // TextView whose id *name* says clock is the same view by role (the vivo report
                // needed this when the id lookup missed).
                ?: findByRole(view)
                ?: return
            if (clockView !== clock) {
                clockView = clock
                clockOriginalTypeface = clock.typeface
                L.i("clock found: ${clock.javaClass.simpleName} id=${rom.clockId}")
            }
            if (!enabled) {
                // Restore the original typeface once, then leave the clock alone; the view reference
                // stays because the anchor and the colour sample still need it.
                val original = clockOriginalTypeface
                if (original != null && clock.typeface !== original) {
                    clock.typeface = original
                    L.i("clock font restored (clock view kept for anchor/colour)")
                }
                return
            }
            // Only touch the typeface when it actually changes. Building a new Typeface and assigning it
            // on every layout pass forced a re-layout each time, which re-fired the layout listener and
            // kept the status bar in a continuous relayout loop - a real battery drain.
            val tf = systemTypeface()
            if (clock.typeface !== tf) {
                clock.typeface = tf
            }
        } catch (t: Throwable) {
            L.w("clock font: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Every `TextView` in the subtree carrying [id], in tree order. */
    private fun collectClocks(view: View, id: Int, out: MutableList<TextView>) {
        if (view.id == id && view is TextView) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectClocks(view.getChildAt(i), id, out)
        }
    }

    /**
     * The clock by role: the first laid-out `TextView` whose id resource name contains "clock",
     * whatever the ROM named it. The status-bar window's tree is small, and the keyguard's big clock
     * lives in the shade window, so a name match here is the bar's own clock.
     */
    private fun findByRole(view: View): TextView? {
        if (view is TextView && view.id != View.NO_ID) {
            val name = try {
                context.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) {
                null
            }
            if (name != null && name.contains("clock", ignoreCase = true) && view.width > 0) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findByRole(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /**
     * The clock this controller last resolved, or null. Valid whether or not the font setting is on —
     * the element's vertical anchoring ([RomAdapter.anchorY]) and the bar-colour sampling read it.
     *
     * A clock that has come off its window is reported as null: vivo rebuilds the status-bar strip
     * at will (measured: "element re-attached after the strip was rebuilt"), and a detached view's
     * `currentTextColor` freezes at its last value — reading it would pin the element's colour to a
     * dead snapshot. Callers fall back to their next anchor / re-resolve instead.
     */
    fun clock(): TextView? = clockView?.takeIf { it.isAttachedToWindow }

    /** Puts the clock's original typeface back but keeps the view reference (font setting turned off). */
    fun restore() {
        val clock = clockView ?: return
        try {
            clockOriginalTypeface?.let { original ->
                if (clock.typeface !== original) clock.typeface = original
            }
        } catch (t: Throwable) {
            L.w("clock restore: ${t.message}")
        }
    }

    /** Forgets the clock view entirely (host teardown). */
    fun teardown() {
        restore()
        clockView = null
        clockOriginalTypeface = null
    }

    private fun systemTypeface(): Typeface {
        cachedTypeface?.let { return it }
        val tf = try {
            val file = File("/system/fonts/SysSans-En-Regular.ttf")
            if (file.exists()) Typeface.createFromFile(file)
            else Typeface.create("sans-serif", Typeface.NORMAL)
        } catch (t: Throwable) {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
        cachedTypeface = tf
        return tf
    }
}
