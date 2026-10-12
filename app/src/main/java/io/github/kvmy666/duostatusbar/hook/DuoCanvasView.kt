package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import io.github.kvmy666.duostatusbar.L

/**
 * The element drawn with plain Android Canvas — no Rive, no native code, nothing that can fault.
 *
 * It exists for two reasons:
 *
 *  1. **Stage 1** of the on-device test: inserting into `system_icons` and hiding the stock icons can
 *     be verified with zero native risk, separately from Rive.
 *  2. **Fallback**: if Rive fails to come up, the status bar still shows a right-looking element
 *     instead of a hole (FR-21).
 *
 * It draws what is known exactly — ring with the top gap, the track, the battery colour and the
 * percentage (or the bolt while charging). The four signal dots and the crescents that fill them
 * use the same seats as the Rive ellipses, so this fallback agrees with the live element there.
 * The Wi-Fi glyph is drawn here too, from the same seats and opacities the Rive path binds — it used
 * to be Rive-only, which left the middle slot empty (no Wi-Fi icon) whenever this fallback ran
 * (reported: "wifi icon not show" at stage 1). The airplane glyph still remains Rive-only. The
 * mapping is shared with the Rive path via [DuoMapping] so the ring and the colour can never
 * disagree between the two.
 *
 * Canvas angles start at 3 o'clock, Rive trim fractions at 12 o'clock, hence [+TRIM_ORIGIN].
 */
internal class DuoCanvasView(
    context: Context,
    part: DuoPart = DuoPart.ALL
) : View(context), DuoElement {

    private var incoming: DuoVisual? = null

    private var visual: DuoVisual = part.apply(
        DuoMapping.visual(
            level = 100, charging = false, saver = false, showPercent = true,
            wifiLevel = 3, cellLevel = 4, airplane = false
        )
    )

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wifiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()

    /** Grows a gray signal dot into the white Do Not Disturb crescent. See [DotMoonMotion]. */
    private val motion = DotMoonMotion()
    private var ticking = false
    private val tick = Runnable {
        ticking = false
        step()
    }

    override var part: DuoPart = part
        set(value) {
            if (field == value) return
            field = value
            val current = incoming ?: return
            incoming = null
            render(current)
        }

    override val ui: View get() = this
    override val rendererName: String get() = "Canvas"
    override val isReady: Boolean get() = true

    override fun start(): Boolean = true

    /** Canvas draws from the first frame, so readiness is never deferred. */
    override fun onReady(action: () -> Unit) = action()

    override fun onFailed(action: () -> Unit) = Unit

    override fun render(v: DuoVisual) {
        try {
            if (v == incoming) return
            incoming = v
            step()
        } catch (t: Throwable) {
            L.w("canvas render: ${t.message}")
        }
    }

    /** Draws the current fill frame and keeps invalidating until a dot has finished becoming a moon. */
    private fun step() {
        val next = incoming ?: return
        visual = part.apply(motion.push(next, SystemClock.uptimeMillis()))
        invalidate()
        if (motion.running && !ticking) {
            ticking = true
            postOnAnimation(tick)
        }
    }

    /** The arrival itself is Rive's to run. The dot fill above is the only motion this view owns. */
    override fun reveal(ms: Int) = Unit

    override fun teardown() {
        removeCallbacks(tick)
        ticking = false
    }

    override fun onDraw(canvas: Canvas) {
        try {
            drawElement(canvas)
        } catch (t: Throwable) {
            // A drawing failure must never repeat: draw nothing rather than throw every frame.
            L.w("canvas onDraw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun drawElement(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f || height <= 0) return
        val k = size / DESIGN_SIZE
        val stroke = STROKE * k
        val radius = (size - stroke) / 2f
        val cx = width / 2f
        // The view is taller than the ring so the percentage can sit above a center punch-hole.
        // The ring stays in the lower square; the extra height is the space above it.
        val cy = height - size / 2f
        ring.strokeWidth = stroke
        val drawRing = visual.ringOpacity > 0.5f
        val drawIndicators = visual.indicatorsOpacity > 0.5f

        // Track: the two halves, dimmed, from the same bound arc lengths Rive's ringTrackL/R read. It
        // used to be one full circle, so with the percentage off the fallback drew a closed track under
        // the digits while the live element closed the ring (reported: "the battery bar goes all the way
        // around, it's under the number"). The track now follows leftArc/rightArc exactly, so the two
        // renderers cannot disagree at the top gap.
        if (drawRing) {
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            ring.color = withAlpha(visual.fgColor, TRACK_ALPHA)
            val leftTrack = visual.leftArc * 360f
            if (leftTrack > MIN_SWEEP) {
                canvas.drawArc(arcBounds, TRIM_ORIGIN + 360f * DuoMapping.LEFT_START, leftTrack, false, ring)
            }
            val rightTrack = visual.rightArc * 360f
            if (rightTrack > MIN_SWEEP) {
                canvas.drawArc(arcBounds, TRIM_ORIGIN + 360f * DuoMapping.RIGHT_START, rightTrack, false, ring)
            }
        }

        // Progress: left arc 0-50 %, right arc 50-100 %, exactly as the .riv splits it. The trim ends
        // are arc *lengths* now, so they are the sweeps directly; the right one is 0 when the gap is
        // closed and the left half covers the whole ring on its own.
        if (drawRing) {
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            ring.color = visual.tint
            val leftSweep = visual.trimLeftEnd * 360f
            if (leftSweep > MIN_SWEEP) {
                canvas.drawArc(arcBounds, TRIM_ORIGIN + 360f * DuoMapping.LEFT_START, leftSweep, false, ring)
            }
            val rightSweep = visual.trimRightEnd * 360f
            if (rightSweep > MIN_SWEEP) {
                canvas.drawArc(arcBounds, TRIM_ORIGIN + 360f * DuoMapping.RIGHT_START, rightSweep, false, ring)
            }
            // Download wash, on top of the white stroke, from the same caps. Opacity 0 draws nothing.
            if (visual.downloadOpacity > 0.01f) {
                val gradient = visual.downloadGradient
                ring.shader =
                    gradient?.let {
                        val points = it.points(
                            arcBounds.left,
                            arcBounds.top,
                            arcBounds.right,
                            arcBounds.bottom,
                        )
                        LinearGradient(
                            points[0],
                            points[1],
                            points[2],
                            points[3],
                            it.colors.toIntArray(),
                            null,
                            Shader.TileMode.CLAMP,
                        )
                    }
                if (gradient == null) {
                    ring.color = withAlpha(visual.downloadColor, visual.downloadOpacity)
                } else {
                    ring.alpha = (visual.downloadOpacity * 255f).toInt().coerceIn(0, 255)
                }
                val downloadLeft = visual.downloadLeftEnd * 360f
                if (downloadLeft > MIN_SWEEP) {
                    canvas.drawArc(
                        arcBounds,
                        TRIM_ORIGIN + 360f * visual.downloadLeftOffset,
                        downloadLeft,
                        false,
                        ring
                    )
                }
                val downloadRight = visual.downloadRightEnd * 360f
                if (downloadRight > MIN_SWEEP) {
                    canvas.drawArc(
                        arcBounds,
                        TRIM_ORIGIN + 360f * visual.downloadRightOffset,
                        downloadRight,
                        false,
                        ring
                    )
                }
                ring.shader = null
                ring.alpha = 255
            }
        }

        // FR-06: the DND crescent takes the middle slot (0, 17 design units below the ring centre).
        if (drawIndicators && visual.middleMode == DuoMapping.MIDDLE_DND) {
            drawMoon(canvas, cx, cy + DND_SLOT_Y * k, k, 1f)
        }

        // FR-06: the Wi-Fi glyph takes the middle slot while the radio is connected. Drawn from the
        // same seats and opacities the Rive path binds, so the fallback shows the same thing.
        if (drawIndicators && visual.middleMode == DuoMapping.MIDDLE_WIFI) {
            drawWifi(canvas, cx, cy, k)
        }

        // Gray circles in the signal-dot seats, then the white crescents that fill them. A strong
        // bar's circle opacity falls as its moon grows, which is the fill. They belong to the ring,
        // so a split layout keeps them with it.
        if (drawRing) {
            val cells = floatArrayOf(
                visual.cell1Opacity, visual.cell2Opacity, visual.cell3Opacity, visual.cell4Opacity
            )
            for (i in cells.indices) {
                if (cells[i] <= 0f) continue
                moonPaint.color = withAlpha(visual.fgColor, cells[i])
                canvas.drawCircle(
                    cx + CELL_DOTS[i][0] * k,
                    cy + CELL_DOTS[i][1] * k,
                    DOT_RADIUS * k,
                    moonPaint
                )
            }
            val moons = floatArrayOf(
                visual.moon1Opacity, visual.moon2Opacity, visual.moon3Opacity, visual.moon4Opacity
            )
            for (i in moons.indices) {
                if (moons[i] <= 0f) continue
                drawMoon(
                    canvas,
                    cx + CELL_DOTS[i][0] * k,
                    cy + CELL_DOTS[i][1] * k,
                    k * DotMoonMotion.DOT_SCALE * moons[i],
                    moons[i]
                )
            }
            if (visual.centerMoonOpacity > 0f) {
                drawMoon(
                    canvas,
                    cx + DotMoonMotion.CENTER_X * k,
                    cy + DotMoonMotion.CENTER_Y * k,
                    k * DotMoonMotion.DOT_SCALE * visual.centerMoonOpacity,
                    visual.centerMoonOpacity
                )
            }
        }

        // FR-06: with Wi-Fi off the slot shows the cellular generation. Same face and weight as the
        // Rive label; the ring's percentage is drawn below and does not overlap it.
        if (drawIndicators && visual.middleMode == DuoMapping.MIDDLE_NETWORK && visual.networkText.isNotEmpty()) {
            label.color = visual.fgColor
            label.textSize = NETWORK_FONT_SIZE * k
            canvas.drawText(
                visual.networkText,
                cx,
                cy + NETWORK_SLOT_Y * k - (label.descent() + label.ascent()) / 2f,
                label
            )
        }

        if (!drawRing) return
        if (visual.boltOpacity > 0f) {
            drawBolt(canvas, cx, cy, size)
            return
        }
        val text = visual.percentText.trim()
        if (text.isEmpty() || visual.percentOpacity <= 0f) return
        label.color = visual.fgColor
        label.textSize = visual.percentFontSize * k
        // The Rive node is placed by its top; the canvas centres the glyphs, so add half the box.
        // Drawing it at the ring centre put it straight on top of the 4G/5G label.
        val percentCenter = visual.percentY + RingGeometry.PERCENT_BOX_HALF
        canvas.drawText(
            text,
            cx,
            cy + percentCenter * k - (label.descent() + label.ascent()) / 2f,
            label
        )
    }

    /** Lightning bolt, unit coordinates scaled to the ring, shown while charging. */
    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val path = boltPath
        val scale = size * BOLT_SCALE
        boltPaint.color = visual.tint
        canvas.save()
        canvas.translate(cx - scale / 2f, cy - scale / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(path, boltPaint)
        canvas.restore()
    }

    /**
     * The Wi-Fi glyph in the middle slot: the dot plus the two arcs, from the same seats and opacities
     * the Rive path binds (outer ellipse r 31.1, mid r 18.15, both ±43.1° about 12 o'clock, dot 3.2
     * above the Wi-Fi centre). The dot is drawn while the radio is connected; the arcs fade in with
     * the signal exactly as Rive's `wifiMidOpacity` / `wifiOuterOpacity` do.
     */
    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        val wx = cx + WIFI_CENTER_X * k
        val wy = cy + WIFI_SLOT_Y * k
        if (visual.wifiLevel > 0) {
            wifiPaint.style = Paint.Style.FILL
            wifiPaint.color = withAlpha(visual.fgColor, 1f)
            canvas.drawCircle(wx, wy - WIFI_DOT_LIFT * k, WIFI_DOT_RADIUS * k, wifiPaint)
        }
        wifiPaint.style = Paint.Style.STROKE
        wifiPaint.strokeWidth = WIFI_STROKE * k
        wifiPaint.strokeCap = Paint.Cap.ROUND
        drawWifiArc(canvas, wx, wy, WIFI_MID_RADIUS, k, visual.wifiMidOpacity)
        drawWifiArc(canvas, wx, wy, WIFI_OUTER_RADIUS, k, visual.wifiOuterOpacity)
    }

    private fun drawWifiArc(canvas: Canvas, x: Float, y: Float, radius: Float, k: Float, opacity: Float) {
        if (opacity <= 0f) return
        arcBounds.set(x - radius * k, y - radius * k, x + radius * k, y + radius * k)
        wifiPaint.color = withAlpha(visual.fgColor, opacity)
        canvas.drawArc(arcBounds, WIFI_ARC_START, WIFI_ARC_SWEEP, false, wifiPaint)
    }

    /** The Do Not Disturb crescent, scaled by [scale] around [x], [y]. */
    private fun drawMoon(canvas: Canvas, x: Float, y: Float, scale: Float, opacity: Float) {
        moonPaint.color = withAlpha(visual.fgColor, opacity)
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.drawPath(moonPath, moonPaint)
        canvas.restore()
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24) and 0xFF) * factor
        return ((alpha.toInt().coerceIn(0, 255)) shl 24) or (color and 0x00FFFFFF)
    }

    private companion object {
        const val TAG = "DuoSB"
        const val DESIGN_SIZE = 103f  // ring diameter in design units: r 51.5
        const val STROKE = 8f
        const val TRIM_ORIGIN = 270f  // Rive fraction 0 == 12 o'clock == canvas 270 degrees
        const val TRACK_ALPHA = 0.22f
        const val MIN_SWEEP = 0.5f
        const val BOLT_SCALE = 0.55f
        val boltPath = Path().apply {
            moveTo(0.58f, 0.02f); lineTo(0.24f, 0.56f); lineTo(0.45f, 0.56f)
            lineTo(0.36f, 0.98f); lineTo(0.76f, 0.40f); lineTo(0.53f, 0.40f)
            close()
        }

        /** Middle slot, design units below the ring centre — the Wi-Fi centre the moon replaces. */
        const val DND_SLOT_Y = 17f

        /**
         * The Wi-Fi glyph, copied from `scene.rml` (`wifiLayer1`, `wifiLayer2`, `wifiDot`) so the
         * fallback and the live element occupy the same pixels. Trim 0.2394 of the circumference is
         * 86.2°, centred on 12 o'clock, which is canvas 226.9° clockwise.
         */
        const val WIFI_CENTER_X = -0.5f
        const val WIFI_SLOT_Y = 17f
        const val WIFI_OUTER_RADIUS = 31.1f
        const val WIFI_MID_RADIUS = 18.15f
        const val WIFI_STROKE = 7.1f
        const val WIFI_ARC_START = 226.9f
        const val WIFI_ARC_SWEEP = 86.2f
        const val WIFI_DOT_RADIUS = 5.5f
        const val WIFI_DOT_LIFT = 3.2f

        /**
         * The four signal dots, design units from the ring centre. Same seats as the Rive ellipses.
         * Each circle is 11 units across ([DOT_RADIUS]).
         */
        val CELL_DOTS = arrayOf(
            floatArrayOf(-27f, 42.7f),
            floatArrayOf(-9.5f, 49.7f),
            floatArrayOf(8.5f, 50.2f),
            floatArrayOf(26f, 44.3f)
        )
        const val DOT_RADIUS = 5.5f

        /** The cellular label's centre, matching the Rive text node (group-relative y 1). */
        const val NETWORK_SLOT_Y = 1f
        const val NETWORK_FONT_SIZE = 30f

        /**
         * The DND crescent, generated from the device's own `drawable/stat_sys_dnd` by
         * `tools/dnd-moon-to-rive.py --android`. Same geometry as the Rive path, so the fallback and
         * the real element cannot disagree (this project does not ship a second, hand-drawn moon).
         */
        val moonPath = Path().apply {
            moveTo(-1.839f, -16.341f)
            cubicTo(-1.539f, -16.791f, -1.509f, -17.361f, -1.809f, -17.841f)
            cubicTo(-2.109f, -18.291f, -2.649f, -18.531f, -3.189f, -18.441f)
            cubicTo(-11.859f, -16.881f, -18.459f, -9.291f, -18.459f, -0.141f)
            cubicTo(-18.459f, 10.119f, -10.119f, 18.459f, 0.141f, 18.459f)
            cubicTo(9.291f, 18.459f, 16.881f, 11.859f, 18.441f, 3.159f)
            cubicTo(18.531f, 2.649f, 18.291f, 2.079f, 17.841f, 1.809f)
            cubicTo(17.361f, 1.509f, 16.791f, 1.509f, 16.341f, 1.839f)
            cubicTo(14.211f, 3.369f, 11.601f, 4.239f, 8.751f, 4.239f)
            cubicTo(1.551f, 4.239f, -4.269f, -1.581f, -4.269f, -8.781f)
            cubicTo(-4.269f, -11.601f, -3.369f, -14.181f, -1.869f, -16.341f)
            cubicTo(-1.869f, -16.341f, -1.839f, -16.341f, -1.839f, -16.341f)
            close()
        }
    }
}
