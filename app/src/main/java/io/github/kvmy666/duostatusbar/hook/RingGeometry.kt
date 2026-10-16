package io.github.kvmy666.duostatusbar.hook

/**
 * The ring's arc/trim/gap maths, straight out of docs/DESIGN-duo.md.
 *
 * Pure and unit-tested (`DuoMappingTest`): this is the half that can go wrong silently.
 */
object RingGeometry {

    // The fill runs from the bottom-left endpoint clockwise: left segment covers 0-50 %, right segment
    // covers 50-100 %, and the top gap between them holds the number (or the bolt). The gap is a
    // *variable*: 71.3 deg with the digits, 55.6 deg with the bolt, and 0 (the ring closes) when the
    // number is off - DESIGN §4.
    const val LEFT_START = 0.6631f
    const val RIGHT_FULL = 0.3369f
    const val GAP_PERCENT_DEG = 71.3f
    const val GAP_CHARGING_DEG = 55.6f

    /** The percent-on gap edges; the "closed" case is 0 and 1. */
    val RIGHT_START = GAP_PERCENT_DEG / 720f
    val LEFT_FULL = 1f - RIGHT_START

    /**
     * The two halves' arc lengths, as trim fractions. Each half is one trimmed window anchored by
     * `offset` and sized by `end`, so these are *lengths*, not positions.
     *
     * With the gap open each half covers [LEFT_START]..[LEFT_FULL] / [RIGHT_START]..[RIGHT_FULL].
     * With it closed the left half grows past 12 o'clock to cover the whole [DRAWN_ARC] on its own
     * and the right half goes to zero - there is deliberately no pair of windows meeting at 12
     * o'clock, because each would end in a round cap and the two caps would overlap: the left body
     * painted over the right body (user-reported bug), and the track's two 0.22 caps stacked to 0.46
     * and showed as a bright blob.
     */
    /** The whole drawn arc once the gap is closed: bottom-left endpoint, through 12 o'clock, round. */
    const val DRAWN_ARC = (1f - LEFT_START) + RIGHT_FULL

    /** The top gap is closed exactly when neither the digits nor the bolt occupy it. */
    fun gapClosed(charging: Boolean, showPercent: Boolean): Boolean = !charging && !showPercent

    /** One half's arc length for the current gap: the bolt's narrower gap makes the halves longer. */
    fun halfArc(charging: Boolean): Float =
        1f - (if (charging) GAP_CHARGING_DEG else GAP_PERCENT_DEG) / 720f - LEFT_START

    /** Left half's arc length: half the drawn arc, or all of it once the gap closes. */
    fun leftArc(charging: Boolean, showPercent: Boolean): Float =
        if (gapClosed(charging, showPercent)) DRAWN_ARC else halfArc(charging)

    /** Right half's arc length: half the drawn arc, or nothing once the gap closes. */
    fun rightArc(charging: Boolean, showPercent: Boolean): Float =
        if (gapClosed(charging, showPercent)) 0f else halfArc(charging)

    /**
     * Left half's filled arc: 0 % -> 50 % of [halfArc] normally. With the gap closed it owns the
     * whole [DRAWN_ARC] instead, so 0 % -> 100 % of that.
     */
    fun trimLeft(level: Int, charging: Boolean, closed: Boolean): Float =
        if (closed) DRAWN_ARC * (level.coerceIn(0, 100) / 100f)
        else halfArc(charging) * (level.coerceIn(0, 50) / 50f)

    /** Right half's filled arc: 50 % -> 100 % of [halfArc], and nothing while the gap is closed. */
    fun trimRight(level: Int, charging: Boolean, closed: Boolean): Float =
        if (closed) 0f else halfArc(charging) * ((level.coerceIn(50, 100) - 50) / 50f)

    /**
     * Font size, measured against the reference: the digits' cap height is 0.218 of the ring diameter
     * (`tools/measure-element.py` on `duo-reference-02`: 43 px digits / 197 px ring), so ~22.5 units in
     * the 103-unit ring - a font of 32 for Montserrat (cap height ~0.7 em). Three digits shrink further
     * so `100` still fits the gap.
     */
    fun percentFontSize(text: String): Float = if (text.length >= 3) 26f else 32f

    /**
     * How far the battery percentage can be lifted above its original top-gap seat, in the 120-unit
     * design space. A center punch-hole (Samsung Fold) cuts through the digits when they sit in
     * the gap. The artboard always has room for the full lift; the setting only slides the label
     * between the original seat and this one. `rive/duo/scene.rml` matches: artboard height 136,
     * group y 77.5, and `percentY` runs from [PERCENT_TOP_Y] up to [PERCENT_TOP_Y] minus this.
     */
    const val PERCENT_LIFT = 16f

    /** The text node's y at the original seat. originY is 0, so this is the top of the text box. */
    const val PERCENT_TOP_Y = -60f

    /** Half the 42-unit text box. The canvas centres the glyphs; Rive places the box by its top. */
    const val PERCENT_BOX_HALF = 21f

    /**
     * Text-box top for a percentage-height setting. 0 is the original seat, 100 is fully raised
     * ([PERCENT_LIFT] above that).
     */
    fun percentTopY(heightPercent: Int): Float =
        PERCENT_TOP_Y - PERCENT_LIFT * heightPercent.coerceIn(0, 100) / 100f

    /** The original square artboard. The ring still occupies this square; [ARTBOARD_HEIGHT] is taller. */
    const val ARTBOARD_SIZE = 120f

    /**
     * How far the ring's ink reaches from the artboard centre.
     *
     * The path radius is 51.5 and the stroke is 8, drawn on the path, so the ink ends 4 units outside it.
     * The rest of the artboard, on each side, is empty margin around that ink.
     */
    const val RING_INK_RADIUS = 55.5f

    /** Artboard height after [PERCENT_LIFT] was added above the ring. */
    const val ARTBOARD_HEIGHT = ARTBOARD_SIZE + PERCENT_LIFT

    /** View height for a ring [side] px wide, including the raised percentage. */
    fun elementHeightPx(side: Int): Int =
        (side * ARTBOARD_HEIGHT / ARTBOARD_SIZE).toInt().coerceAtLeast(1)

    /**
     * Pixels of [side] that sit outside the ring's ink, on one side.
     *
     * That margin is the slot's leftover padding once the middle icon is gone. Giving it back lets the
     * neighbouring status icons sit against the ring instead of against an empty box.
     */
    fun outerSlackPx(side: Int): Int {
        if (side <= 0) return 0
        val fraction = (ARTBOARD_SIZE / 2f - RING_INK_RADIUS) / ARTBOARD_SIZE
        return (side * fraction).toInt()
    }

    /**
     * Moves a taller view up so the ring stays where the old square view put it. The added height
     * then sticks out above the ring, which is where the percentage is drawn.
     */
    fun ringAnchorShiftY(side: Int): Float = -(PERCENT_LIFT / 2f) * (side / ARTBOARD_SIZE)
}
