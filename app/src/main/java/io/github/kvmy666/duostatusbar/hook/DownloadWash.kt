package io.github.kvmy666.duostatusbar.hook

/**
 * The download stroke drawn on top of the battery ring.
 *
 * The white ring stays. Clockwise progress paints from the bottom-left cap toward the bottom-right
 * cap, the same way the battery fills: the left half covers 0–50 %, the right half 50–100 %.
 * Counter-clockwise grows back from the right cap and meets the left cap at 100 %.
 *
 * Lengths and offsets are trim fractions for the same windows the battery shapes use, so the blue
 * sits on the white stroke, including when the top gap closes or narrows for the bolt. Pure, so
 * `DownloadWashTest` can pin it.
 */
data class DownloadWash(
    val leftEnd: Float,
    val leftOffset: Float,
    val rightEnd: Float,
    val rightOffset: Float,
    val opacity: Float,
    val color: Int,
    val gradient: DownloadGradient? = null,
) {
    companion object {
        /** The concept sheet's blue. Callers pass the punch-hole progress colour when they have one. */
        const val COLOR = 0xFF0A84FF.toInt()

        /** Nothing drawn. This is what the ring looks like with no download in progress. */
        val IDLE = DownloadWash(
            leftEnd = 0f,
            leftOffset = RingGeometry.LEFT_START,
            rightEnd = 0f,
            rightOffset = RingGeometry.RIGHT_START,
            opacity = 0f,
            color = COLOR,
            gradient = null,
        )

        /**
         * @param progress 0–100 along the drawn arc.
         * @param clockwise true paints left cap → right cap. False paints the other way.
         * @param active false returns [IDLE] so a finished download cannot leave ink behind.
         */
        fun of(
            progress: Int,
            clockwise: Boolean,
            charging: Boolean,
            showPercent: Boolean,
            active: Boolean,
            color: Int = COLOR,
            opacity: Float = 1f,
            gradient: DownloadGradient? = null,
        ): DownloadWash {
            if (!active || opacity <= 0f) return IDLE.copy(color = color, gradient = gradient)
            val level = progress.coerceIn(0, 100)
            val closed = RingGeometry.gapClosed(charging, showPercent)
            val leftArc = RingGeometry.leftArc(charging, showPercent)
            val rightArc = RingGeometry.rightArc(charging, showPercent)
            val ends = if (clockwise) {
                clockwise(level, charging, closed)
            } else {
                reverse(level, leftArc, rightArc, closed)
            }
            return DownloadWash(
                leftEnd = ends.leftEnd,
                leftOffset = ends.leftOffset,
                rightEnd = ends.rightEnd,
                rightOffset = ends.rightOffset,
                opacity = opacity.coerceIn(0f, 1f),
                color = color,
                gradient = gradient,
            )
        }

        /** Same split as the battery fill, so 28 % covers 28/50 of the left half and none of the right. */
        private fun clockwise(level: Int, charging: Boolean, closed: Boolean) = Ends(
            leftEnd = RingGeometry.trimLeft(level, charging, closed),
            leftOffset = RingGeometry.LEFT_START,
            rightEnd = RingGeometry.trimRight(level, charging, closed),
            rightOffset = RingGeometry.RIGHT_START,
        )

        /**
         * Grow from the far cap backward. With the gap open, 0–50 % walks the right half back from
         * its cap and 50–100 % walks the left half back to the left cap. With the gap closed the
         * whole arc is one window, so the full 0–100 % walks that window back from its far end.
         */
        private fun reverse(level: Int, leftArc: Float, rightArc: Float, closed: Boolean): Ends {
            if (closed || rightArc <= 0f) {
                val length = leftArc * level / 100f
                val far = RingGeometry.LEFT_START + leftArc
                return Ends(
                    leftEnd = length,
                    leftOffset = far - length,
                    rightEnd = 0f,
                    rightOffset = RingGeometry.RIGHT_START,
                )
            }
            val leftFar = RingGeometry.LEFT_START + leftArc
            val rightCap = RingGeometry.RIGHT_START + rightArc
            if (level <= 50) {
                val length = rightArc * level / 50f
                return Ends(
                    leftEnd = 0f,
                    leftOffset = leftFar,
                    rightEnd = length,
                    rightOffset = rightCap - length,
                )
            }
            val length = leftArc * (level - 50) / 50f
            return Ends(
                leftEnd = length,
                leftOffset = leftFar - length,
                rightEnd = rightArc,
                rightOffset = RingGeometry.RIGHT_START,
            )
        }
    }

    private data class Ends(
        val leftEnd: Float,
        val leftOffset: Float,
        val rightEnd: Float,
        val rightOffset: Float,
    )
}
