package io.github.kvmy666.duostatusbar.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state → visual mapping, tested here rather than on a phone.
 *
 * This is the half of the element that fails *silently*: a wrong trim fraction or a wrong colour looks
 * like a working status bar until someone notices the battery is 40 % wrong. It is pure maths (no
 * Android types), so it is cheap to pin down: every expectation below is derived from the geometry in
 * `docs/DESIGN-duo.md` and the colour rules in FR-15/FR-25.
 */
class DuoMappingTest {

    private fun visual(
        level: Int,
        charging: Boolean = false,
        saver: Boolean = false,
        airplane: Boolean = false,
        dnd: Boolean = false
    ) = DuoMapping.visual(
        level = level,
        charging = charging,
        saver = saver,
        showPercent = true,
        wifiLevel = 3,
        cellLevel = 4,
        airplane = airplane,
        dnd = dnd
    )

    // ---------------------------------------------------------------------------- ring geometry

    // The percent-on case the reference screenshots show: the gap is open, so there are two halves.
    private val half = DuoMapping.halfArc(charging = false)

    @Test
    fun `ring is empty at zero percent`() {
        assertEquals(0f, DuoMapping.trimLeft(0, charging = false, closed = false), 0.0001f)
        assertEquals(0f, DuoMapping.trimRight(0, charging = false, closed = false), 0.0001f)
    }

    @Test
    fun `left half fills completely at fifty percent`() {
        assertEquals(half, DuoMapping.trimLeft(50, charging = false, closed = false), 0.0001f)
        // The right half only starts moving above 50 %.
        assertEquals(0f, DuoMapping.trimRight(50, charging = false, closed = false), 0.0001f)
    }

    @Test
    fun `ring is full at one hundred percent`() {
        assertEquals(half, DuoMapping.trimLeft(100, charging = false, closed = false), 0.0001f)
        assertEquals(half, DuoMapping.trimRight(100, charging = false, closed = false), 0.0001f)
    }

    @Test
    fun `halfway through the right half is halfway along its arc`() {
        assertEquals(half / 2f, DuoMapping.trimRight(75, charging = false, closed = false), 0.0001f)
    }

    @Test
    fun `out of range levels are clamped, not extrapolated`() {
        assertEquals(0f, DuoMapping.trimLeft(-20, charging = false, closed = false), 0.0001f)
        assertEquals(half, DuoMapping.trimLeft(500, charging = false, closed = false), 0.0001f)
        assertEquals(half, DuoMapping.trimRight(500, charging = false, closed = false), 0.0001f)
    }

    // ---------------------------------------------------------------------------------- top gap

    @Test
    fun `closing the gap gives the left half the whole ring and empties the right`() {
        assertTrue(DuoMapping.gapClosed(charging = false, showPercent = false))
        // No pair of windows meeting at 12 o'clock, so no two round caps overlap there: the left
        // half covers the whole drawn arc on its own and the right half is zero. Two windows that
        // met would put the left body over the right body (user-reported bug).
        assertEquals(DuoMapping.DRAWN_ARC, DuoMapping.leftArc(charging = false, showPercent = false), 0.0001f)
        assertEquals(0f, DuoMapping.rightArc(charging = false, showPercent = false), 0.0001f)
        assertEquals(DuoMapping.DRAWN_ARC / 2f, DuoMapping.trimLeft(50, charging = false, closed = true), 0.0001f)
        assertEquals(0f, DuoMapping.trimRight(50, charging = false, closed = true), 0.0001f)
        assertEquals(DuoMapping.DRAWN_ARC, DuoMapping.trimLeft(100, charging = false, closed = true), 0.0001f)
    }

    @Test
    fun `the digits keep the gap open and the bolt narrows it`() {
        assertEquals(half, DuoMapping.leftArc(charging = false, showPercent = true), 0.0001f)
        assertEquals(half, DuoMapping.rightArc(charging = false, showPercent = true), 0.0001f)
        assertFalse(DuoMapping.gapClosed(charging = true, showPercent = true))
        // A narrower gap means each half is longer.
        assertTrue(DuoMapping.halfArc(charging = true) > half)
        assertEquals(
            half + (DuoMapping.GAP_PERCENT_DEG - DuoMapping.GAP_CHARGING_DEG) / 720f,
            DuoMapping.leftArc(charging = true, showPercent = true),
            0.0001f
        )
    }

    // ------------------------------------------------------------------------------- battery tint

    @Test
    fun `charging is green and beats every other rule`() {
        assertEquals(DuoMapping.GREEN_CHARGING, DuoMapping.tint(5, charging = true, saver = true))
        assertEquals(DuoMapping.GREEN_CHARGING, DuoMapping.tint(100, charging = true, saver = false))
    }

    @Test
    fun `saver is yellow`() {
        assertEquals(DuoMapping.YELLOW_SAVER, DuoMapping.tint(80, charging = false, saver = true))
    }

    @Test
    fun `below twenty percent is red`() {
        assertEquals(DuoMapping.RED_CRITICAL, DuoMapping.tint(19, charging = false, saver = false))
        assertEquals(DuoMapping.WHITE, DuoMapping.tint(20, charging = false, saver = false))
    }

    @Test
    fun `the ring takes the bar's foreground colour when no battery rule applies`() {
        // FR-15b: on a light bar the whole element is black, including the ring's default colour; the
        // charging / saver / low-battery rules still win over it.
        val black = DuoMapping.visual(
            level = 80, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, fgColor = DuoMapping.BLACK
        )
        assertEquals(DuoMapping.BLACK, black.fgColor)
        assertEquals(DuoMapping.BLACK, black.tint)
        val charging = DuoMapping.visual(
            level = 80, charging = true, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, fgColor = DuoMapping.BLACK
        )
        assertEquals(DuoMapping.GREEN_CHARGING, charging.tint)
    }

    // ------------------------------------------------------------------------------------ wifi

    @Test
    fun `wifi layers light up as the signal improves`() {
        assertEquals(Triple(0.3f, 0.3f, 0.3f), DuoMapping.wifiOpacities(0))
        assertEquals(Triple(0.3f, 0.3f, 1f), DuoMapping.wifiOpacities(1))
        assertEquals(Triple(0.3f, 1f, 1f), DuoMapping.wifiOpacities(2))
        assertEquals(Triple(1f, 1f, 1f), DuoMapping.wifiOpacities(3))
    }

    @Test
    fun `wifi never exceeds three layers`() {
        assertEquals(Triple(1f, 1f, 1f), DuoMapping.wifiOpacities(99))
    }

    @Test
    fun `stock wifi bars map onto duo's three levels`() {
        // The stock four-bar icon measured against Duo's two arcs + dot (user-reported mapping).
        assertEquals(0, DuoMapping.wifiBars(0))
        assertEquals(1, DuoMapping.wifiBars(1))
        assertEquals(1, DuoMapping.wifiBars(2))
        assertEquals(2, DuoMapping.wifiBars(3))
        assertEquals(3, DuoMapping.wifiBars(4))
    }

    // -------------------------------------------------------------------------------- cellular

    @Test
    fun `cellular shows one sphere per bar`() {
        assertEquals(listOf(1f, 0.3f, 0.3f, 0.3f), DuoMapping.cellOpacities(1))
        assertEquals(listOf(1f, 1f, 1f, 1f), DuoMapping.cellOpacities(4))
        assertEquals(listOf(0.3f, 0.3f, 0.3f, 0.3f), DuoMapping.cellOpacities(0))
    }

    // ------------------------------------------------------------------------------ the snapshot

    @Test
    fun `the track follows the gap, so the fallback cannot draw a closed ring under the digits`() {
        // The Rive track arcs (ringTrackL/R) read leftArc/rightArc, and so must the Canvas fallback;
        // drawing one full circle drew a closed track under the number when the percentage was off
        // (user-reported). With the digits the track keeps the gap; without, it closes on the left.
        val open = DuoMapping.visual(
            level = 50, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false
        )
        assertEquals(DuoMapping.halfArc(false), open.leftArc, 0.0001f)
        assertEquals(DuoMapping.halfArc(false), open.rightArc, 0.0001f)

        val closed = DuoMapping.visual(
            level = 50, charging = false, saver = false, showPercent = false,
            wifiLevel = 3, cellLevel = 4, airplane = false
        )
        assertEquals(DuoMapping.DRAWN_ARC, closed.leftArc, 0.0001f)
        assertEquals(0f, closed.rightArc, 0.0001f)
        assertEquals(0f, closed.percentOpacity, 0.0001f)
    }

    @Test
    fun `charging hides the percentage and shows the bolt`() {
        val v = visual(42, charging = true)
        assertEquals("", v.percentText.trim())
        assertEquals(0f, v.percentOpacity, 0.0001f)
        assertEquals(1f, v.boltOpacity, 0.0001f)
    }

    @Test
    fun `hiding the charging bolt restores the percentage while the ring stays green`() {
        val v = DuoMapping.visual(
            level = 42, charging = true, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, showCharging = false
        )
        assertEquals("42", v.percentText)
        assertEquals(1f, v.percentOpacity, 0.0001f)
        assertEquals(0f, v.boltOpacity, 0.0001f)
        // The battery is still charging, so the ring keeps its charging colour.
        assertEquals(DuoMapping.GREEN_CHARGING, v.tint)
    }

    @Test
    fun `hiding the charging bolt also permits download digits and keeps their styling`() {
        val gradient = DownloadGradient(
            listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            angle = 45
        )
        val wash = DownloadWash.of(
            progress = 64,
            clockwise = true,
            charging = false,
            showPercent = true,
            active = true,
            color = 0xFF123456.toInt(),
            opacity = 0.6f,
            gradient = gradient
        )
        val v = DuoMapping.visual(
            level = 72, charging = true, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false,
            showCharging = false, download = wash, downloadPercent = 64
        )

        assertEquals("64", v.percentText)
        assertEquals(0f, v.boltOpacity, 0.0001f)
        assertEquals(DuoMapping.GREEN_CHARGING, v.tint)
        assertEquals(wash.leftEnd, v.downloadLeftEnd, 0.0001f)
        assertEquals(wash.rightEnd, v.downloadRightEnd, 0.0001f)
        assertEquals(wash.opacity, v.downloadOpacity, 0.0001f)
        assertEquals(wash.color, v.downloadColor)
        assertEquals(gradient, v.downloadGradient)
    }

    @Test
    fun `a download percentage opens a closed ring and takes the battery seat`() {
        val v = DuoMapping.visual(
            level = 72, charging = false, saver = false, showPercent = false,
            wifiLevel = 3, cellLevel = 4, airplane = false,
            downloadPercent = 28
        )
        assertEquals("28", v.percentText)
        assertEquals(1f, v.percentOpacity, 0.0001f)
        assertEquals(half, v.leftArc, 0.0001f)
        assertEquals(half, v.rightArc, 0.0001f)
    }

    @Test
    fun `a download percentage replaces the battery number`() {
        val v = DuoMapping.visual(
            level = 72, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false,
            downloadPercent = 64
        )
        assertEquals("64", v.percentText)
        assertEquals(1f, v.percentOpacity, 0.0001f)
    }

    @Test
    fun `charging keeps the bolt while a download percentage is available`() {
        val v = DuoMapping.visual(
            level = 72, charging = true, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false,
            downloadPercent = 64
        )
        assertEquals("", v.percentText.trim())
        assertEquals(1f, v.boltOpacity, 0.0001f)
    }

    @Test
    fun `a normal reading shows the number and no bolt`() {
        val v = visual(42)
        assertEquals("42", v.percentText)
        assertEquals(1f, v.percentOpacity, 0.0001f)
        assertEquals(0f, v.boltOpacity, 0.0001f)
    }

    @Test
    fun `three digits shrink so they still fit the gap`() {
        assertEquals(26f, DuoMapping.percentFontSize("100"), 0.0001f)
        assertEquals(32f, DuoMapping.percentFontSize("42"), 0.0001f)
        assertEquals(26f, visual(100).percentFontSize, 0.0001f)
    }

    @Test
    fun `percentage height slides from the original seat to fully raised`() {
        val original = DuoMapping.visual(
            level = 50, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, percentHeight = 0
        )
        val halfway = DuoMapping.visual(
            level = 50, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, percentHeight = 50
        )
        val raised = DuoMapping.visual(
            level = 50, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, percentHeight = 100
        )
        assertEquals(RingGeometry.PERCENT_TOP_Y, original.percentY, 0.0001f)
        assertEquals(RingGeometry.PERCENT_TOP_Y - RingGeometry.PERCENT_LIFT, raised.percentY, 0.0001f)
        assertEquals((original.percentY + raised.percentY) / 2f, halfway.percentY, 0.0001f)
        // The demo morph has to travel the same distance, or the settings preview would lie.
        assertEquals(raised.percentY, original.lerp(raised, 1f).percentY, 0.0001f)
        assertEquals(halfway.percentY, original.lerp(raised, 0.5f).percentY, 0.0001f)
    }

    @Test
    fun `edge spacing can give the whole slot back`() {
        assertEquals(0, edgeInsetPx(80, 100))
        assertEquals(-40, edgeInsetPx(80, 50))
        assertEquals(-80, edgeInsetPx(80, 0))
        assertEquals(-83, edgeInsetPx(83, 0))
        assertEquals(0, edgeInsetPx(0, 0))
        assertEquals(0, edgeInsetPx(80, 250))
    }

    @Test
    fun `an empty middle icon gives back the margin outside the ring`() {
        // 4.5 design units of the 120-wide artboard sit outside the ring's ink, on each side.
        assertEquals(3, RingGeometry.outerSlackPx(80))
        assertEquals(0, RingGeometry.outerSlackPx(0))
    }

    @Test
    fun `a locked ring is centered on the status bar and the slider nudges it`() {
        val centered = lockedTranslationX(0, 1272, 0, 0, 80, 0f)
        assertEquals(1272 / 2f - 40f, centered, 0.01f)
        assertEquals(centered + 12f, lockedTranslationX(0, 1272, 0, 0, 80, 12f), 0.01f)
        // A parent that is inset from the bar, with its own padding, still lands on the bar's center.
        assertEquals(456f, lockedTranslationX(10, 1000, 10, 4, 80, 0f), 0.01f)
    }

    @Test
    fun `a locked ring's center is the vertical center of the status bar`() {
        val side = 120
        val y = lockedTranslationY(0, 141, 0, 0, side)
        val ringCenter = y + (RingGeometry.elementHeightPx(side) - side / 2f)
        assertEquals(70.5f, ringCenter, 0.01f)
    }

    @Test
    fun `a part shows one group and hides the other`() {
        val full = visual(50)
        assertEquals(1f, DuoPart.ALL.apply(full).ringOpacity, 0.0001f)
        assertEquals(1f, DuoPart.ALL.apply(full).indicatorsOpacity, 0.0001f)
        val ring = DuoPart.RING.apply(full)
        assertEquals(1f, ring.ringOpacity, 0.0001f)
        assertEquals(0f, ring.indicatorsOpacity, 0.0001f)
        val icons = DuoPart.INDICATORS.apply(full)
        assertEquals(0f, icons.ringOpacity, 0.0001f)
        assertEquals(1f, icons.indicatorsOpacity, 0.0001f)
        // The percentage seat is the ring's, so splitting the icons must not move it.
        assertEquals(full.percentY, ring.percentY, 0.0001f)
        assertEquals(icons.indicatorsOpacity, ring.lerp(icons, 1f).indicatorsOpacity, 0.0001f)
    }

    @Test
    fun `airplane mode blanks the cellular ramps and takes the middle slot`() {
        val v = visual(70, airplane = true)
        assertEquals(DuoMapping.MIDDLE_AIRPLANE, v.middleMode)
        // The glyphs are the state machine's job now, so the host only reports the level: the arcs stay
        // lit here and the Rive MiddleSlot layer retracts them as it morphs.
        v.let { assertEquals(listOf(0.3f, 0.3f, 0.3f, 0.3f), listOf(it.cell1Opacity, it.cell2Opacity, it.cell3Opacity, it.cell4Opacity)) }
    }

    @Test
    fun `wifi dots follow wifi and vanish when airplane has no wifi`() {
        fun spheres(
            airplane: Boolean,
            wifiOn: Boolean,
            wifiLevel: Int,
            cellLevel: Int,
            wifiDots: Boolean
        ): List<Float> {
            val v = DuoMapping.visual(
                level = 70, charging = false, saver = false, showPercent = true,
                wifiLevel = wifiLevel, cellLevel = cellLevel, airplane = airplane,
                wifiOn = wifiOn, wifiDots = wifiDots
            )
            return listOf(v.cell1Opacity, v.cell2Opacity, v.cell3Opacity, v.cell4Opacity)
        }

        // Off keeps today's look: airplane blanks the cellular spheres to the empty ramp.
        assertEquals(
            listOf(0.3f, 0.3f, 0.3f, 0.3f),
            spheres(airplane = true, wifiOn = false, wifiLevel = 0, cellLevel = 4, wifiDots = false)
        )
        // On, airplane, Wi-Fi off: the spheres have nothing to report, so they disappear.
        assertEquals(
            listOf(0f, 0f, 0f, 0f),
            spheres(airplane = true, wifiOn = false, wifiLevel = 0, cellLevel = 4, wifiDots = true)
        )
        // Airplane mode with Wi-Fi live: the spheres report Wi-Fi.
        // Level 1 covers stock bars 1–2, level 2 is stock bar 3, level 3 is a full four.
        assertEquals(
            listOf(1f, 1f, 0.3f, 0.3f),
            spheres(airplane = true, wifiOn = true, wifiLevel = 1, cellLevel = 0, wifiDots = true)
        )
        assertEquals(
            listOf(1f, 1f, 1f, 0.3f),
            spheres(airplane = true, wifiOn = true, wifiLevel = 2, cellLevel = 4, wifiDots = true)
        )
        assertEquals(
            listOf(1f, 1f, 1f, 1f),
            spheres(airplane = true, wifiOn = true, wifiLevel = 3, cellLevel = 0, wifiDots = true)
        )
        // Out of airplane mode they stay cellular, whether or not Wi-Fi is on.
        assertEquals(
            listOf(1f, 1f, 1f, 1f),
            spheres(airplane = false, wifiOn = true, wifiLevel = 2, cellLevel = 4, wifiDots = true)
        )
        assertEquals(
            listOf(1f, 1f, 0.3f, 0.3f),
            spheres(airplane = false, wifiOn = false, wifiLevel = 0, cellLevel = 2, wifiDots = true)
        )
    }

    @Test
    fun `dnd takes the middle slot and leaves the spheres lit`() {
        val v = visual(70, dnd = true)
        assertEquals(DuoMapping.MIDDLE_DND, v.middleMode)
        // Cellular is not the middle slot: the spheres stay lit while DND is on.
        assertEquals(listOf(1f, 1f, 1f, 1f), listOf(v.cell1Opacity, v.cell2Opacity, v.cell3Opacity, v.cell4Opacity))
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(v.moon1Opacity, v.moon2Opacity, v.moon3Opacity, v.moon4Opacity))
    }

    @Test
    fun `dnd on the signal dots leaves the middle alone and paints moons`() {
        val weak = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 2, airplane = false, dnd = true,
            showDnd = false, dndDots = true
        )
        assertEquals(DuoMapping.MIDDLE_WIFI, weak.middleMode)
        // The first two bars are strong, so those gray dots are gone and the white moons have filled
        // them. The other two stay the original gray circles.
        assertEquals(listOf(0f, 0f, 0.3f, 0.3f), listOf(weak.cell1Opacity, weak.cell2Opacity, weak.cell3Opacity, weak.cell4Opacity))
        assertEquals(listOf(1f, 1f, 0f, 0f), listOf(weak.moon1Opacity, weak.moon2Opacity, weak.moon3Opacity, weak.moon4Opacity))
        assertEquals(0f, weak.centerMoonOpacity)

        // The mode only applies while Do Not Disturb is actually on.
        val quiet = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 2, airplane = false, dnd = false, dndDots = true
        )
        assertEquals(listOf(1f, 1f, 0.3f, 0.3f), listOf(quiet.cell1Opacity, quiet.cell2Opacity, quiet.cell3Opacity, quiet.cell4Opacity))
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(quiet.moon1Opacity, quiet.moon2Opacity, quiet.moon3Opacity, quiet.moon4Opacity))
        assertEquals(0f, quiet.centerMoonOpacity)

        // Airplane still owns the middle. Every bar is lit, so every seat is a white moon.
        val plane = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = true, dnd = true, wifiOn = true,
            showAirplane = true, wifiDots = true, dndDots = true
        )
        assertEquals(DuoMapping.MIDDLE_AIRPLANE, plane.middleMode)
        assertEquals(listOf(1f, 1f, 1f, 1f), listOf(plane.moon1Opacity, plane.moon2Opacity, plane.moon3Opacity, plane.moon4Opacity))
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(plane.cell1Opacity, plane.cell2Opacity, plane.cell3Opacity, plane.cell4Opacity))
        assertEquals(0f, plane.centerMoonOpacity)
    }

    @Test
    fun `a hidden signal row keeps one moon in the middle of the dots`() {
        val hidden = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 0, cellLevel = 4, airplane = true, dnd = true, wifiOn = false,
            wifiDots = true, showDnd = false, dndDots = true
        )
        assertEquals(DuoMapping.MIDDLE_AIRPLANE, hidden.middleMode)
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(hidden.cell1Opacity, hidden.cell2Opacity, hidden.cell3Opacity, hidden.cell4Opacity))
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(hidden.moon1Opacity, hidden.moon2Opacity, hidden.moon3Opacity, hidden.moon4Opacity))
        assertEquals(1f, hidden.centerMoonOpacity)

        // The same radios without the hide (Wi-Fi dots off) only dim the spheres, so the gray
        // circles stay and the lone moon does not appear.
        val dimmed = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 0, cellLevel = 4, airplane = true, dnd = true, wifiOn = false,
            wifiDots = false, showDnd = false, dndDots = true
        )
        assertEquals(listOf(0.3f, 0.3f, 0.3f, 0.3f), listOf(dimmed.cell1Opacity, dimmed.cell2Opacity, dimmed.cell3Opacity, dimmed.cell4Opacity))
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(dimmed.moon1Opacity, dimmed.moon2Opacity, dimmed.moon3Opacity, dimmed.moon4Opacity))
        assertEquals(0f, dimmed.centerMoonOpacity)

        // Do Not Disturb off leaves the hidden row empty.
        val off = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 0, cellLevel = 4, airplane = true, dnd = false, wifiOn = false,
            wifiDots = true, dndDots = true
        )
        assertEquals(0f, off.centerMoonOpacity)
        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(off.cell1Opacity, off.cell2Opacity, off.cell3Opacity, off.cell4Opacity))
    }

    @Test
    fun `a strong bar eases from its gray dot into a white moon`() {
        val gray = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 0, airplane = false, dnd = true,
            showDnd = false, dndDots = true
        )
        val filled = DuoMapping.visual(
            level = 40, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, dnd = true,
            showDnd = false, dndDots = true
        )
        val motion = DotMoonMotion()
        assertEquals(gray.moon1Opacity, motion.push(gray, 0L).moon1Opacity)
        assertFalse(motion.running)

        val start = motion.push(filled, 1_000L)
        assertTrue(motion.running)
        assertEquals(0.3f, start.cell1Opacity, 0.001f)
        assertEquals(0f, start.moon1Opacity, 0.001f)
        // The rest of the snapshot is already the new one; only the dots are mid-fill.
        assertEquals(filled.percentText, start.percentText)

        val mid = motion.push(filled, 1_140L)
        assertTrue(motion.running)
        assertTrue(mid.moon1Opacity in 0.4f..0.6f)
        assertTrue(mid.cell1Opacity in 0.1f..0.2f)

        // A battery change during the fill keeps the same ease instead of starting it over.
        val battery = filled.copy(percentText = "39")
        val carried = motion.push(battery, 1_140L)
        assertEquals(mid.moon1Opacity, carried.moon1Opacity, 0.001f)
        assertEquals("39", carried.percentText)

        val done = motion.push(battery, 1_000L + DotMoonMotion.DURATION_MS)
        assertFalse(motion.running)
        assertEquals(1f, done.moon1Opacity)
        assertEquals(0f, done.cell1Opacity)
        assertEquals("39", done.percentText)
    }

    @Test
    fun `the middle slot shows the Wi-Fi by default`() {
        assertEquals(DuoMapping.MIDDLE_WIFI, visual(70).middleMode)
    }

    @Test
    fun `airplane and do not disturb can each be kept out of the middle slot`() {
        // Airplane on, Do Not Disturb off: the plane takes the slot, and DND never does.
        assertEquals(
            DuoMapping.MIDDLE_AIRPLANE,
            DuoMapping.middleMode(
                airplane = true, dnd = true, showAirplane = true, showDnd = false
            )
        )
        assertEquals(
            DuoMapping.MIDDLE_WIFI,
            DuoMapping.middleMode(
                airplane = false, dnd = true, wifiOn = true, hasNetwork = false,
                wifiConnected = true, showDnd = false
            )
        )
        // Do Not Disturb on, airplane off: the moon takes the slot even while the radio is in airplane.
        assertEquals(
            DuoMapping.MIDDLE_DND,
            DuoMapping.middleMode(
                airplane = true, dnd = true, wifiOn = true, wifiConnected = true,
                showAirplane = false, showDnd = true
            )
        )
        // Neither allowed: the slot stays on the connection. No Wi-Fi and no label leaves it empty.
        assertEquals(
            DuoMapping.MIDDLE_OFF,
            DuoMapping.middleMode(
                airplane = true, dnd = true, wifiOn = false, hasNetwork = false,
                wifiConnected = false, showAirplane = false, showDnd = false
            )
        )
        assertEquals(
            DuoMapping.MIDDLE_NETWORK,
            DuoMapping.middleMode(
                airplane = true, dnd = true, wifiOn = false, hasNetwork = true,
                wifiConnected = false, showAirplane = false, showDnd = false
            )
        )
        val network = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = true, dnd = true,
            wifiOn = false, networkText = "5G", showAirplane = false, showDnd = false
        )
        assertEquals(DuoMapping.MIDDLE_NETWORK, network.middleMode)
        assertEquals("5G", network.networkText)
        val plane = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = true, dnd = true,
            wifiOn = false, networkText = "5G", showAirplane = true, showDnd = false
        )
        assertEquals(DuoMapping.MIDDLE_AIRPLANE, plane.middleMode)
        assertEquals("", plane.networkText)
    }

    @Test
    fun `the slot holds exactly one occupant, and airplane wins`() {
        // FR-06/FR-16: one number, one occupant - which is why the state machine can be a star.
        assertEquals(DuoMapping.MIDDLE_AIRPLANE, DuoMapping.middleMode(airplane = true, dnd = true))
        assertEquals(DuoMapping.MIDDLE_DND, DuoMapping.middleMode(airplane = false, dnd = true))
        assertEquals(DuoMapping.MIDDLE_WIFI, DuoMapping.middleMode(airplane = false, dnd = false))
    }

    // --------------------------------------------------------------------------- network generation

    @Test
    fun `the cellular generation maps the common radio types`() {
        assertEquals("5G", DuoMapping.networkGeneration(20))   // NR
        assertEquals("4G", DuoMapping.networkGeneration(13))   // LTE
        assertEquals("4G", DuoMapping.networkGeneration(19))   // LTE_CA
        assertEquals("3G", DuoMapping.networkGeneration(3))    // UMTS
        assertEquals("3G", DuoMapping.networkGeneration(15))   // HSPAP
        assertEquals("2G", DuoMapping.networkGeneration(1))    // GPRS
        assertEquals("2G", DuoMapping.networkGeneration(16))   // GSM
    }

    @Test
    fun `an NR connection reads as 5G even when the data type is LTE`() {
        // 5G NSA: the data network type is LTE, but the radio reports NR connected. Reading only the
        // data type is what made a 5G phone show "4G" (user-reported bug).
        assertEquals("5G", DuoMapping.networkGeneration(13, nrConnected = true))
        assertEquals("5G", DuoMapping.networkGeneration(20, nrConnected = true))
        // Not NR connected: the data type is all we have.
        assertEquals("4G", DuoMapping.networkGeneration(13, nrConnected = false))
    }

    @Test
    fun `an unknown radio type shows no label`() {
        // UNKNOWN and IWLAN (Wi-Fi calling) are not a generation worth naming.
        assertEquals("", DuoMapping.networkGeneration(0))
        assertEquals("", DuoMapping.networkGeneration(18))
    }

    @Test
    fun `mobile data off shows no generation even while the radio is still registered`() {
        // Turning data off leaves voice service up, so the type and NR state still read 5G/4G. The
        // slot names the data path, and with data off it empties.
        assertEquals("", DuoMapping.networkGeneration(20, nrConnected = true, dataEnabled = false))
        assertEquals("", DuoMapping.networkGeneration(13, dataEnabled = false))
        assertEquals("5G", DuoMapping.networkGeneration(13, nrConnected = true, dataEnabled = true))
    }

    @Test
    fun `with wifi off the slot shows the cellular generation`() {
        assertEquals(
            DuoMapping.MIDDLE_NETWORK,
            DuoMapping.middleMode(airplane = false, dnd = false, wifiOn = false, hasNetwork = true)
        )
        // No service either: the slot empties rather than showing a stale label.
        assertEquals(
            DuoMapping.MIDDLE_OFF,
            DuoMapping.middleMode(airplane = false, dnd = false, wifiOn = false, hasNetwork = false)
        )
    }

    @Test
    fun `wifi on but not connected shows the cellular generation`() {
        // The radio is on but there is no Wi-Fi network: the phone is on mobile data, so the slot must
        // name the generation rather than show a dead Wi-Fi glyph (user-reported bug).
        assertEquals(
            DuoMapping.MIDDLE_NETWORK,
            DuoMapping.middleMode(
                airplane = false, dnd = false, wifiOn = true, hasNetwork = true, wifiConnected = false
            )
        )
        // Connected Wi-Fi still wins the slot.
        assertEquals(
            DuoMapping.MIDDLE_WIFI,
            DuoMapping.middleMode(
                airplane = false, dnd = false, wifiOn = true, hasNetwork = true, wifiConnected = true
            )
        )
    }

    @Test
    fun `airplane and dnd beat the network label`() {
        assertEquals(
            DuoMapping.MIDDLE_AIRPLANE,
            DuoMapping.middleMode(airplane = true, dnd = false, wifiOn = false, hasNetwork = true)
        )
        assertEquals(
            DuoMapping.MIDDLE_DND,
            DuoMapping.middleMode(airplane = false, dnd = true, wifiOn = false, hasNetwork = true)
        )
    }

    @Test
    fun `the network label is only carried while the slot holds it`() {
        val shown = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 0, cellLevel = 4, airplane = false, wifiOn = false, networkText = "5G"
        )
        assertEquals(DuoMapping.MIDDLE_NETWORK, shown.middleMode)
        assertEquals("5G", shown.networkText)
        // Wi-Fi on: the slot holds the glyph, so the label is blank even if one was handed in.
        val hidden = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false, wifiOn = true, networkText = "5G"
        )
        assertEquals(DuoMapping.MIDDLE_WIFI, hidden.middleMode)
        assertEquals("", hidden.networkText)
        // Wi-Fi off and no generation (mobile data switched off): the slot is empty.
        val dataOff = DuoMapping.visual(
            level = 70, charging = false, saver = false, showPercent = true,
            wifiLevel = 0, cellLevel = 4, airplane = false, wifiOn = false, networkText = ""
        )
        assertEquals(DuoMapping.MIDDLE_OFF, dataOff.middleMode)
        assertEquals("", dataOff.networkText)
    }

    @Test
    fun `a demo can morph between two snapshots without leaving either state`() {
        val off = visual(72, dnd = false)
        val on = visual(72, airplane = true)
        // The ends are the states themselves, not an approximation of them.
        assertEquals(off, off.lerp(on, 0f))
        assertEquals(on, off.lerp(on, 1f))
        // Half-way is actually half-way for the things that can be half-way...
        val mid = off.lerp(on, 0.5f)
        assertEquals((off.wifiOuterOpacity + on.wifiOuterOpacity) / 2f, mid.wifiOuterOpacity, 0.0001f)
        assertEquals((off.trimLeftEnd + on.trimLeftEnd) / 2f, mid.trimLeftEnd, 0.0001f)
        // ...and the things that cannot are one state or the other, never a third thing.
        assertTrue(mid.middleMode == off.middleMode || mid.middleMode == on.middleMode)
        assertTrue(mid.tint == off.tint || mid.tint == on.tint)
        assertEquals(off.percentText, off.lerp(on, 0.49f).percentText)
        assertEquals(on.percentText, off.lerp(on, 0.51f).percentText)
        // Out-of-range t is clamped rather than extrapolated.
        assertEquals(off, off.lerp(on, -1f))
        assertEquals(on, off.lerp(on, 2f))
    }

    @Test
    fun `airplane beats dnd for the middle slot`() {
        assertEquals(DuoMapping.MIDDLE_AIRPLANE, visual(70, airplane = true, dnd = true).middleMode)
    }

    @Test
    fun `every field of the snapshot is populated for a full battery`() {
        val v = visual(100)
        // The trim ends are arc lengths now, so a full battery fills both halves completely.
        assertEquals(DuoMapping.halfArc(charging = false), v.trimLeftEnd, 0.0001f)
        assertEquals(DuoMapping.halfArc(charging = false), v.trimRightEnd, 0.0001f)
        assertEquals(DuoMapping.halfArc(charging = false), v.leftArc, 0.0001f)
        assertEquals(DuoMapping.halfArc(charging = false), v.rightArc, 0.0001f)
        assertTrue(v.trackOpacity > 0f)
        assertEquals(DuoMapping.WHITE, v.fgColor)
    }
}
