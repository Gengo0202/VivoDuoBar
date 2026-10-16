package io.github.kvmy666.duostatusbar.hook

/** Visible hit regions inside the taller-than-wide Duo artboard. */
internal object ElementHitGeometry {

    fun ring(x: Float, y: Float, width: Int, height: Int): Boolean {
        if (!insideView(x, y, width, height)) return false
        val side = width.toFloat()
        val dx = x - side / 2f
        val dy = y - (height - side / 2f)
        return dx * dx + dy * dy <= side * side / 4f
    }

    fun indicators(x: Float, y: Float, width: Int, height: Int): Boolean {
        if (!insideView(x, y, width, height)) return false
        val side = width.toFloat()
        val cy = height - side / 2f
        return x in side * 0.25f..side * 0.75f &&
            y in cy - side * 0.25f..cy + side * 0.25f
    }

    private fun insideView(x: Float, y: Float, width: Int, height: Int): Boolean =
        x >= 0f && x <= width && y >= 0f && y <= height
}
